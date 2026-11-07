package com.joelaspinall.cinemaos.data

import android.content.Context
import com.joelaspinall.cinemaos.data.db.CinemaDatabase
import com.joelaspinall.cinemaos.data.db.DiaryRow
import com.joelaspinall.cinemaos.data.db.ListItemRow
import com.joelaspinall.cinemaos.data.db.MetaRow
import com.joelaspinall.cinemaos.data.db.OwnedRow
import com.joelaspinall.cinemaos.data.db.ResolvedRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.UUID

val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

/**
 * Joel's settings (encrypted with the TV's Keystore) and film data (Room database).
 * Screens read the in-memory copy instantly; changes are written to disk in the background.
 */
class Store(context: Context, val db: CinemaDatabase = CinemaDatabase.open(context)) {
    private val dao = db.dao()
    private val secure = SecureFile(File(context.filesDir, "settings.enc"))
    private val legacySettings = File(context.filesDir, "settings.json")
    private val legacyData = File(context.filesDir, "data.json")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val saves = Channel<Pair<StoreData, StoreData>>(Channel.UNLIMITED)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _data = MutableStateFlow(loadData())
    val data: StateFlow<StoreData> = _data.asStateFlow()

    init {
        if (_settings.value.plexClientId.isBlank()) {
            updateSettings { it.copy(plexClientId = "cinema-os-" + UUID.randomUUID()) }
        }
        io.launch { for ((before, after) in saves) persist(before, after) }
        // Keep the offline cache to the last 60 days.
        io.launch { runCatching { dao.pruneCache(System.currentTimeMillis() - 60L * 24 * 3600 * 1000) } }
    }

    @Synchronized
    fun updateSettings(change: (Settings) -> Settings) {
        val next = change(_settings.value)
        _settings.value = next
        val text = JSON.encodeToString(Settings.serializer(), next)
        io.launch { runCatching { secure.write(text) } }
    }

    @Synchronized
    fun updateData(change: (StoreData) -> StoreData) {
        val before = _data.value
        val next = change(before)
        _data.value = next
        saves.trySend(before to next)
    }

    // ---- Loading ------------------------------------------------------------

    private fun loadSettings(): Settings {
        secure.read()?.let { text -> runCatching { return JSON.decodeFromString<Settings>(text) } }
        // First run after the update: move the old plain-text settings into the encrypted file.
        val legacy = runCatching { if (legacySettings.exists()) JSON.decodeFromString<Settings>(legacySettings.readText()) else null }.getOrNull()
        if (legacy != null) {
            runCatching { secure.write(JSON.encodeToString(Settings.serializer(), legacy)); legacySettings.delete() }
            return legacy
        }
        return Settings()
    }

    private fun loadData(): StoreData {
        val fromDb = runCatching { readDb() }.getOrNull()
        if (fromDb != null && (fromDb.diary.isNotEmpty() || fromDb.history.isNotEmpty() || fromDb.rss.isNotEmpty() || fromDb.appleOwned.isNotEmpty())) {
            return fromDb
        }
        // First run after the update: copy the old data.json into the database once.
        val legacy = runCatching { if (legacyData.exists()) JSON.decodeFromString<StoreData>(legacyData.readText()) else null }.getOrNull()
        if (legacy != null) {
            runCatching { persist(StoreData(), legacy); legacyData.renameTo(File(legacyData.parentFile, "data.json.migrated")) }
            return legacy
        }
        return fromDb ?: StoreData()
    }

    private fun readDb(): StoreData {
        val meta = dao.meta().associate { it.key to it.value }
        fun items(list: String) = dao.list(list).map { HistoryItem(it.title, it.year, it.rating, it.tmdbId, it.date) }
        return StoreData(
            diary = dao.diary().map {
                DiaryEntry(it.id, it.tmdbId, it.title, it.year, it.watchedDate, it.rating, it.rewatch, it.review, it.source, it.exported)
            },
            history = items("history"),
            watchlist = items("watchlist"),
            rss = items("rss"),
            appleImport = items("appleImport"),
            appleOwned = dao.owned().mapNotNull { runCatching { JSON.decodeFromString<Film>(it.json) }.getOrNull() },
            resolved = dao.resolved().associate { it.key to it.tmdbId },
            pending = meta["pending"]?.let { runCatching { JSON.decodeFromString<PendingWatch>(it) }.getOrNull() },
            historyImportedAt = meta["historyImportedAt"],
            appleImportedAt = meta["appleImportedAt"],
        )
    }

    /** Writes only the parts that changed. */
    private fun persist(before: StoreData, after: StoreData) = runCatching {
        db.runInTransaction {
            if (before.diary !== after.diary) dao.replaceDiary(after.diary.mapIndexed { i, e ->
                DiaryRow(e.id, i, e.tmdbId, e.title, e.year, e.watchedDate, e.rating, e.rewatch, e.review, e.source, e.exported)
            })
            fun list(name: String, a: List<HistoryItem>, b: List<HistoryItem>) {
                if (a !== b) dao.replaceList(name, b.mapIndexed { i, h -> ListItemRow(0, name, i, h.title, h.year, h.rating, h.tmdbId, h.date) })
            }
            list("history", before.history, after.history)
            list("watchlist", before.watchlist, after.watchlist)
            list("rss", before.rss, after.rss)
            list("appleImport", before.appleImport, after.appleImport)
            if (before.appleOwned !== after.appleOwned) dao.replaceOwned(after.appleOwned.mapIndexed { i, f ->
                OwnedRow(f.key, i, JSON.encodeToString(Film.serializer(), f))
            })
            if (before.resolved !== after.resolved) {
                dao.insertResolved(after.resolved.filter { (k, v) -> before.resolved[k] != v }.map { (k, v) -> ResolvedRow(k, v) })
            }
            fun meta(key: String, a: String?, b: String?) {
                if (a != b) { if (b == null) dao.clearMeta(key) else dao.putMeta(MetaRow(key, b)) }
            }
            meta("pending", before.pending?.let { JSON.encodeToString(PendingWatch.serializer(), it) },
                after.pending?.let { JSON.encodeToString(PendingWatch.serializer(), it) })
            meta("historyImportedAt", before.historyImportedAt, after.historyImportedAt)
            meta("appleImportedAt", before.appleImportedAt, after.appleImportedAt)
        }
    }

    // ---- Diary --------------------------------------------------------------

    fun logWatch(film: Film, rating: Double?, rewatch: Boolean, source: String, watchedDate: String? = null) {
        val entry = DiaryEntry(
            id = UUID.randomUUID().toString(),
            tmdbId = film.tmdbId,
            title = film.title,
            year = film.year,
            watchedDate = watchedDate ?: LocalDate.now().toString(),
            rating = rating,
            rewatch = rewatch,
            source = source,
        )
        updateData { it.copy(diary = listOf(entry) + it.diary, pending = null) }
    }

    fun markExported() = updateData { d -> d.copy(diary = d.diary.map { it.copy(exported = true) }) }

    fun unexported(): List<DiaryEntry> = data.value.diary.filter { !it.exported }

    // ---- What has Joel already seen? ---------------------------------------

    fun seenKeys(): Set<String> {
        val d = data.value
        return buildSet {
            d.history.forEach { add(filmKey(it.title, it.year)) }
            d.rss.forEach { add(filmKey(it.title, it.year)) }
            d.diary.forEach { add(filmKey(it.title, it.year)) }
        }
    }

    fun seenTmdbIds(): Set<Int> {
        val d = data.value
        return buildSet {
            d.history.mapNotNullTo(this) { it.tmdbId ?: d.resolved[filmKey(it.title, it.year)] }
            d.rss.mapNotNullTo(this) { it.tmdbId }
            d.diary.mapNotNullTo(this) { it.tmdbId }
        }
    }

    fun hasSeen(film: Film): Boolean =
        (film.tmdbId != null && film.tmdbId in seenTmdbIds()) || film.key in seenKeys()

    fun ratingFor(film: Film): Double? {
        val d = data.value
        d.diary.firstOrNull { (film.tmdbId != null && it.tmdbId == film.tmdbId) || filmKey(it.title, it.year) == film.key }
            ?.rating?.let { return it }
        d.rss.firstOrNull { (film.tmdbId != null && it.tmdbId == film.tmdbId) || filmKey(it.title, it.year) == film.key }
            ?.rating?.let { return it }
        return d.history.firstOrNull { filmKey(it.title, it.year) == film.key }?.rating
    }

    // ---- Apple TV purchases -------------------------------------------------

    fun isAppleOwned(film: Film) = data.value.appleOwned.any { it.key == film.key || (it.tmdbId != null && it.tmdbId == film.tmdbId) }

    fun toggleAppleOwned(film: Film) = updateData { d ->
        if (d.appleOwned.any { it.key == film.key }) d.copy(appleOwned = d.appleOwned.filterNot { it.key == film.key })
        else d.copy(appleOwned = listOf(film) + d.appleOwned)
    }

}
