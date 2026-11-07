package com.joelaspinall.cinemaos.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joelaspinall.cinemaos.CinemaApp
import com.joelaspinall.cinemaos.data.Film
import com.joelaspinall.cinemaos.data.PendingWatch
import com.joelaspinall.cinemaos.data.Row
import com.joelaspinall.cinemaos.data.SERVICES
import com.joelaspinall.cinemaos.data.Service
import com.joelaspinall.cinemaos.data.filmKey
import com.joelaspinall.cinemaos.data.serviceById
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/** [watchedDate] is the day Joel started watching, so a film finished after midnight keeps the right diary date. */
data class RateRequest(val film: Film, val source: String, val heading: String, val watchedDate: String? = null)

fun dayOf(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
data class FinishPrompt(val pending: PendingWatch, val likelyFinished: Boolean)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val c = app as CinemaApp

    private val slots = mutableStateMapOf<String, Row>()
    var plexStatus by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var toast by mutableStateOf<String?>(null)
    var rate by mutableStateOf<RateRequest?>(null)
    var finish by mutableStateOf<FinishPrompt?>(null)
    private var refreshJob: Job? = null

    private val order = listOf("forYou", "because0", "watchlist", "because1", "apple", "because2", "diary") +
        SERVICES.map { "svc:${it.id}" }

    val homeRows: List<Row> get() = order.mapNotNull { slots[it] }.filter { it.films.isNotEmpty() }

    /** Plex has its own page, kept off Home. */
    val plexDeck: List<Film> get() = slots["deck"]?.films.orEmpty()
    val plexRecent: List<Film> get() = slots["plexNew"]?.films.orEmpty()

    val myServices: List<Service> get() = c.store.settings.value.services.mapNotNull { serviceById(it) }

    init {
        refresh()
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            c.events.debounce(800).collect { e -> if (e == "apple") importApple() else refresh() }
        }
        if (c.store.data.value.appleImport.isNotEmpty()) importApple()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            loading = true
            coroutineScope {
                val plexJob = launch { loadPlex() }
                launch {
                    runCatching { c.letterboxd.syncRss() }
                    diaryRow()
                }
                if (c.tmdb.isConfigured) {
                    myServices.forEach { s ->
                        launch {
                            runCatching { c.tmdb.catalogue(s.id, 1, null, "popularity.desc") }
                                .onSuccess { slots["svc:${s.id}"] = Row("Popular on ${s.label}", it) }
                        }
                    }
                    launch {
                        plexJob.join() // recommendations need to know what's in Plex
                        runCatching { c.recommender.build() }.onSuccess { r ->
                            slots["forYou"] = Row("Recommended for you", r.forYou)
                            listOf("because0", "because1", "because2").forEach { slots.remove(it) }
                            r.because.forEachIndexed { i, row -> slots["because$i"] = row }
                            slots["watchlist"] = Row("On your watchlist — streaming now", r.watchlistNow)
                        }
                    }
                }
                appleRow()
            }
            loading = false
        }
    }

    private suspend fun loadPlex() {
        val plex = c.plex
        if (!plex.hasServer) {
            plexStatus = if (plex.isSignedIn) "Signed in to Plex, but your server wasn't found. Is the MacBook awake?" else null
            return
        }
        try {
            plex.loadLibrary()
            slots["deck"] = Row("Continue watching on Plex", plex.onDeck())
            slots["plexNew"] = Row("Recently added to Plex", plex.recentlyAdded())
            plexStatus = null
        } catch (e: Exception) {
            plexStatus = "Can't reach Plex on ${c.store.settings.value.plexServerName.ifBlank { "your MacBook" }} — is it awake and running Plex?"
        }
    }

    private suspend fun diaryRow() = coroutineScope {
        val d = c.store.data.value
        val seen = HashSet<String>()
        val recent = (d.diary.map { Triple(it.title, it.year, it.tmdbId) } + d.rss.map { Triple(it.title, it.year, it.tmdbId) })
            .filter { seen.add(filmKey(it.first, it.second)) }
            .take(20)
        val films = recent.map { (title, year, id) ->
            async {
                (if (id != null && c.tmdb.isConfigured) runCatching { c.tmdb.film(id) }.getOrNull() else null)
                    ?: Film(tmdbId = id, title = title, year = year)
            }
        }.awaitAll()
        slots["diary"] = Row("Your recent diary", films)
    }

    /** Matches uploaded Apple TV purchases to TMDB films and adds them to the library. */
    fun importApple() {
        viewModelScope.launch {
            val items = c.store.data.value.appleImport
            if (items.isEmpty() || !c.tmdb.isConfigured) return@launch
            val gate = kotlinx.coroutines.sync.Semaphore(6)
            val found = coroutineScope {
                items.map { item ->
                    async {
                        gate.withPermit {
                            runCatching { c.tmdb.resolve(item.title, item.year)?.let { c.tmdb.film(it) } }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            c.store.updateData { d ->
                val have = d.appleOwned.mapNotNull { it.tmdbId }.toHashSet()
                d.copy(
                    appleOwned = d.appleOwned + found.filter { it.tmdbId !in have }.distinctBy { it.tmdbId },
                    appleImport = emptyList(),
                    appleImportedAt = java.time.LocalDate.now().toString(),
                )
            }
            appleRow()
            val missed = items.size - found.size
            toast = "Added ${found.size} films to your Apple TV library" + if (missed > 0) " ($missed couldn't be matched)" else ""
        }
    }

    private fun appleRow() {
        slots["apple"] = Row("Your Apple TV library", c.store.data.value.appleOwned)
    }

    // ---- Watching and logging ---------------------------------------------------

    fun watchOn(service: Service, film: Film, runtime: Int?) {
        viewModelScope.launch {
            c.store.updateData { it.copy(pending = PendingWatch(film, service.id, System.currentTimeMillis(), runtime)) }
            val msg = c.launcher.open(service, film)
            if (msg != null && (msg.contains("isn't installed") || msg.startsWith("Couldn't"))) {
                c.store.updateData { it.copy(pending = null) }
            }
            if (msg != null) toast = msg
        }
    }

    /** Called whenever the app comes back to the front, e.g. after Netflix. */
    fun checkPending() {
        val p = c.store.data.value.pending ?: return
        if (rate != null || finish != null) return
        val mins = (System.currentTimeMillis() - p.startedAt) / 60_000
        when (val d = com.joelaspinall.cinemaos.data.Rules.finish(mins, p.runtimeMinutes)) {
            com.joelaspinall.cinemaos.data.Rules.Finish.Ignore -> c.store.updateData { it.copy(pending = null) }
            is com.joelaspinall.cinemaos.data.Rules.Finish.Ask -> finish = FinishPrompt(p, d.likely)
        }
    }

    fun answerFinish(finished: Boolean, keepAsking: Boolean = false) {
        val f = finish ?: return
        finish = null
        if (keepAsking) {
            // Ask again next time Joel comes back to the app.
            c.store.updateData { it.copy(pending = f.pending.copy(startedAt = System.currentTimeMillis() - 15 * 60_000)) }
            return
        }
        c.store.updateData { it.copy(pending = null) }
        if (finished) {
            val label = serviceById(f.pending.serviceId)?.label ?: "streaming"
            rate = RateRequest(
                f.pending.film, f.pending.serviceId, "How was ${f.pending.film.title}?  (watched on $label)",
                watchedDate = dayOf(f.pending.startedAt),
            )
        }
    }

    fun askRating(film: Film, source: String) {
        rate = RateRequest(film, source, "Log ${film.title}")
    }

    fun saveRating(req: RateRequest, rating: Double?, rewatch: Boolean) {
        c.store.logWatch(req.film, rating, rewatch, req.source, req.watchedDate)
        rate = null
        val n = c.store.unexported().size
        toast = "Logged ${req.film.title} ${stars(rating)}  ·  $n waiting to send to Letterboxd (Settings → Phone)"
        viewModelScope.launch { diaryRow() }
    }

    fun plexFinished(film: Film, startedAt: Long) {
        viewModelScope.launch { c.plex.markWatched(film) }
        rate = RateRequest(film, "plex", "You finished ${film.title} — how was it?", watchedDate = dayOf(startedAt))
    }

    fun toggleApple(film: Film) {
        c.store.toggleAppleOwned(film)
        toast = if (c.store.isAppleOwned(film)) "Added to your Apple TV library" else "Removed from your Apple TV library"
        appleRow()
    }
}
