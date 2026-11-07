package com.joelaspinall.cinemaos.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.UUID

val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

/** Settings and Joel's film data, saved as small JSON files on the TV. */
class Store(context: Context) {
    private val settingsFile = File(context.filesDir, "settings.json")
    private val dataFile = File(context.filesDir, "data.json")

    private val _settings = MutableStateFlow(load(settingsFile) { JSON.decodeFromString<Settings>(it) } ?: Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _data = MutableStateFlow(load(dataFile) { JSON.decodeFromString<StoreData>(it) } ?: StoreData())
    val data: StateFlow<StoreData> = _data.asStateFlow()

    init {
        if (_settings.value.plexClientId.isBlank()) {
            updateSettings { it.copy(plexClientId = "cinema-os-" + UUID.randomUUID()) }
        }
    }

    @Synchronized
    fun updateSettings(change: (Settings) -> Settings) {
        val next = change(_settings.value)
        _settings.value = next
        save(settingsFile, JSON.encodeToString(Settings.serializer(), next))
    }

    @Synchronized
    fun updateData(change: (StoreData) -> StoreData) {
        val next = change(_data.value)
        _data.value = next
        save(dataFile, JSON.encodeToString(StoreData.serializer(), next))
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

    private fun <T> load(file: File, parse: (String) -> T): T? =
        runCatching { if (file.exists()) parse(file.readText()) else null }.getOrNull()

    private fun save(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }
}
