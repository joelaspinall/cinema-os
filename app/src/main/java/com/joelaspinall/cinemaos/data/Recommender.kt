package com.joelaspinall.cinemaos.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Recommendations from Joel's own ratings: films TMDB links to the ones he rated
 * highly, minus anything he's seen, kept only if they're on one of his services,
 * in his Plex library or owned on Apple TV.
 */
class Recommender(
    private val store: Store,
    private val tmdb: Tmdb,
    private val plex: Plex,
) {
    private val gate = Semaphore(6)

    data class Result(
        val forYou: List<Film>,
        val because: List<Row>,
        val watchlistNow: List<Film>,
    )

    private data class Seed(val id: Int, val title: String, val rating: Double, val recency: Int)

    suspend fun available(film: Film): Boolean {
        if (plex.find(film) != null || store.isAppleOwned(film)) return true
        val id = film.tmdbId ?: return false
        return gate.withPermit { tmdb.subscriptionsFor(id).isNotEmpty() }
    }

    private suspend fun seeds(): List<Seed> = coroutineScope {
        val d = store.data.value
        val rated = buildList {
            d.diary.forEachIndexed { i, e -> if ((e.rating ?: 0.0) >= 3.5) add(Triple(e.title to e.year, e.rating!!, i) to e.tmdbId) }
            d.rss.forEachIndexed { i, e -> if ((e.rating ?: 0.0) >= 3.5) add(Triple(e.title to e.year, e.rating!!, i + 5) to e.tmdbId) }
            d.history.sortedByDescending { it.date ?: "" }.forEachIndexed { i, e ->
                if ((e.rating ?: 0.0) >= 4.0) add(Triple(e.title to e.year, e.rating!!, i + 20) to e.tmdbId)
            }
        }.distinctBy { filmKey(it.first.first.first, it.first.first.second) }
            // Favour recent and highly rated films.
            .sortedByDescending { (t, _) -> t.second * 2 - t.third / 15.0 }
            .take(25)

        rated.map { (t, id) ->
            async {
                val (titleYear, rating, recency) = t
                val tmdbId = id ?: gate.withPermit { tmdb.resolve(titleYear.first, titleYear.second) }
                tmdbId?.let { Seed(it, titleYear.first, rating, recency) }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun build(): Result = coroutineScope {
        val seeds = seeds()
        val seenIds = store.seenTmdbIds()
        val seenKeys = store.seenKeys()
        fun unseen(f: Film) = (f.tmdbId == null || f.tmdbId !in seenIds) && f.key !in seenKeys

        val recsBySeed = seeds.map { s ->
            async { s to gate.withPermit { tmdb.recommendations(s.id) }.filter(::unseen) }
        }.awaitAll()

        // Score each candidate: higher for more seeds, better ratings and higher rank.
        val score = HashMap<Int, Double>()
        val films = HashMap<Int, Film>()
        for ((seed, recs) in recsBySeed) {
            recs.take(15).forEachIndexed { rank, f ->
                val id = f.tmdbId ?: return@forEachIndexed
                films[id] = f
                score[id] = (score[id] ?: 0.0) + (seed.rating - 2.5) * (1.0 / (1 + rank * 0.25))
            }
        }
        val ranked = score.entries.sortedByDescending { it.value }.take(80).mapNotNull { films[it.key] }

        val availability = ranked.map { f -> async { f to available(f) } }.awaitAll().toMap()
        val forYou = ranked.filter { availability[it] == true }.take(30)

        val because = recsBySeed
            .filter { it.first.rating >= 4.0 }
            .sortedBy { it.first.recency }
            .take(3)
            .map { (seed, recs) ->
                val avail = recs.take(20).map { f -> async { f to (availability[f] ?: available(f)) } }.awaitAll()
                Row("Because you loved ${seed.title}", avail.filter { it.second }.map { it.first }.take(15))
            }
            .filter { it.films.size >= 3 }

        val watchlistNow = store.data.value.watchlist.take(80).map { w ->
            async {
                val id = gate.withPermit { tmdb.resolve(w.title, w.year) } ?: return@async null
                val film = gate.withPermit { tmdb.film(id) } ?: return@async null
                if (available(film)) film else null
            }
        }.awaitAll().filterNotNull()

        Result(forYou, because, watchlistNow)
    }
}
