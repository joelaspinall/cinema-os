package com.joelaspinall.cinemaos.data

import com.joelaspinall.cinemaos.data.db.ApiCacheRow
import java.util.concurrent.ConcurrentHashMap

/** IMDb and Rotten Tomatoes scores from OMDb (free key, 1,000 lookups a day). */
class Omdb(private val store: Store) {
    data class Scores(val imdb: String?, val rottenTomatoes: String?, val metacritic: String?) {
        val label: String get() = listOfNotNull(
            imdb?.let { "IMDb $it" },
            rottenTomatoes?.let { "Rotten Tomatoes $it" },
        ).joinToString("   ")
    }

    private val memory = ConcurrentHashMap<String, Scores>()
    private val week = 7L * 24 * 3600 * 1000

    val isConfigured get() = store.settings.value.omdbKey.isNotBlank()

    /** Scores for a film by its IMDb id; saved for a week so the daily allowance lasts. */
    suspend fun scores(imdbId: String): Scores? {
        memory[imdbId]?.let { return it }
        val key = store.settings.value.omdbKey.trim().ifBlank { return null }
        val cacheKey = "omdb:$imdbId"
        val dao = store.db.dao()
        val cached = runCatching { dao.cached(cacheKey) }.getOrNull()
        val text = if (cached != null && System.currentTimeMillis() - cached.savedAt < week) {
            cached.body
        } else {
            runCatching { Http.getText("https://www.omdbapi.com/?i=$imdbId&apikey=$key") }
                .onSuccess { body -> runCatching { dao.cache(ApiCacheRow(cacheKey, body, System.currentTimeMillis())) } }
                .getOrNull() ?: cached?.body ?: return null
        }
        val o = runCatching { JSON.parseToJsonElement(text).obj() }.getOrNull() ?: return null
        if (o.str("Response") == "False") return null
        val ratings = o["Ratings"].arr().mapNotNull { it.obj() }
        fun source(name: String) = ratings.firstOrNull { it.str("Source") == name }.str("Value")
        val s = Scores(
            imdb = o.str("imdbRating")?.takeIf { it != "N/A" },
            rottenTomatoes = source("Rotten Tomatoes"),
            metacritic = o.str("Metascore")?.takeIf { it != "N/A" },
        )
        memory[imdbId] = s
        return s
    }

    /** Checks a key works (used by the phone page when it is saved). */
    suspend fun test(key: String): Boolean = runCatching {
        JSON.parseToJsonElement(Http.getText("https://www.omdbapi.com/?i=tt0468569&apikey=${key.trim()}")).obj().str("Response") == "True"
    }.getOrDefault(false)
}
