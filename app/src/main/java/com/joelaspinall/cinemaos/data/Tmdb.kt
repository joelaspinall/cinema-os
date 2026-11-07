package com.joelaspinall.cinemaos.data

import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The Movie Database: film info, UK "where to watch" data (from JustWatch),
 * full service catalogues and recommendations. Free API key from themoviedb.org.
 */
class Tmdb(private val store: Store) {
    private val base = "https://api.themoviedb.org/3"
    private val img = "https://image.tmdb.org/t/p"
    private val region = "GB"

    private val providerCache = ConcurrentHashMap<Int, ProviderInfo>()
    private val detailsCache = ConcurrentHashMap<Int, FilmDetails>()
    private var serviceProviderIds: Map<String, List<Int>>? = null
    private var genresCache: List<Genre>? = null
    private val providerLock = Mutex()

    val isConfigured: Boolean get() = store.settings.value.tmdbToken.isNotBlank()

    data class ProviderInfo(val flatrate: List<String>, val rent: List<String>, val buy: List<String>)

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement {
        val token = store.settings.value.tmdbToken.trim()
        require(token.isNotBlank()) { "Add your TMDB key in Settings first." }
        val b = Uri.parse(base + path).buildUpon()
        params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        // Accept either the short "API key" or the long "Read Access Token".
        val headers = if (token.length > 40) {
            mapOf("Authorization" to "Bearer $token")
        } else {
            b.appendQueryParameter("api_key", token); emptyMap()
        }
        return Http.getJson(b.build().toString(), headers)
    }

    fun poster(path: String?, size: String = "w342") = path?.let { "$img/$size$it" }
    fun backdrop(path: String?) = path?.let { "$img/w1280$it" }

    private fun toFilm(o: JsonObject?): Film? {
        o ?: return null
        val title = o.str("title") ?: return null
        return Film(
            tmdbId = o.int("id"),
            title = title,
            year = o.str("release_date")?.take(4)?.toIntOrNull(),
            poster = poster(o.str("poster_path")),
            backdrop = backdrop(o.str("backdrop_path")),
            overview = o.str("overview"),
        )
    }

    private fun films(e: JsonElement) = e.obj()?.get("results").arr().mapNotNull { toFilm(it.obj()) }

    suspend fun test(): Boolean = runCatching { get("/configuration"); true }.getOrDefault(false)

    suspend fun search(query: String): List<Film> =
        films(get("/search/movie", mapOf("query" to query, "region" to region, "include_adult" to "false")))

    suspend fun popular(): List<Film> = films(get("/movie/popular", mapOf("region" to region)))

    /** Finds the TMDB id for a Letterboxd title + year (cached in the store). */
    suspend fun resolve(title: String, year: Int?): Int? {
        val key = filmKey(title, year)
        store.data.value.resolved[key]?.let { return it }
        val params = buildMap {
            put("query", title)
            if (year != null) put("primary_release_year", year.toString())
        }
        val hit = runCatching { films(get("/search/movie", params)).firstOrNull() }.getOrNull()
            ?: if (year != null) runCatching { films(get("/search/movie", mapOf("query" to title))).firstOrNull() }.getOrNull() else null
        val id = hit?.tmdbId ?: return null
        store.updateData { it.copy(resolved = it.resolved + (key to id)) }
        return id
    }

    suspend fun film(id: Int): Film? = details(id)?.film

    suspend fun details(id: Int): FilmDetails? {
        detailsCache[id]?.let { return it }
        val o = runCatching { get("/movie/$id").obj() }.getOrNull() ?: return null
        val film = toFilm(o) ?: return null
        val d = FilmDetails(
            film = film,
            runtime = o.int("runtime")?.takeIf { it > 0 },
            genres = o["genres"].arr().mapNotNull { it.obj().str("name") },
            tagline = o.str("tagline"),
            rating = o.dbl("vote_average"),
        )
        detailsCache[id] = d
        return d
    }

    suspend fun recommendations(id: Int): List<Film> =
        runCatching { films(get("/movie/$id/recommendations")) }.getOrDefault(emptyList())

    suspend fun similar(id: Int): List<Film> =
        runCatching { films(get("/movie/$id/similar")) }.getOrDefault(emptyList())

    /** What's available on which UK service for one film. */
    suspend fun providers(id: Int): ProviderInfo {
        providerCache[id]?.let { return it }
        val gb = runCatching { get("/movie/$id/watch/providers").obj()?.get("results").obj()?.get(region).obj() }.getOrNull()
        fun names(k: String) = gb?.get(k).arr().mapNotNull { it.obj().str("provider_name") }
        val info = ProviderInfo(names("flatrate") + names("ads") + names("free"), names("rent"), names("buy"))
        providerCache[id] = info
        return info
    }

    /** Which of Joel's services a film is included with. */
    suspend fun subscriptionsFor(id: Int): List<Service> {
        val mine = store.settings.value.services.mapNotNull { serviceById(it) }
        val flat = providers(id).flatrate.map { it.lowercase() }
        return mine.filter { s -> flat.any { name -> s.tmdbPrefixes.any { name.startsWith(it) } } }
    }

    /** TMDB's numeric provider ids for each of Joel's services in the UK. */
    private suspend fun providerIds(): Map<String, List<Int>> = providerLock.withLock {
        serviceProviderIds?.let { return it }
        val all = get("/watch/providers/movie", mapOf("watch_region" to region)).obj()?.get("results").arr()
            .mapNotNull { e -> e.obj()?.let { (it.int("provider_id") ?: return@let null) to (it.str("provider_name") ?: "") } }
        val map = SERVICES.associate { s ->
            s.id to all.filter { (_, name) -> s.tmdbPrefixes.any { name.lowercase().startsWith(it) } }.map { it.first }
        }
        serviceProviderIds = map
        map
    }

    /** A full UK catalogue for one service, a page (20 films) at a time. */
    suspend fun catalogue(serviceId: String, page: Int, genreId: Int?, sort: String): List<Film> {
        val ids = providerIds()[serviceId].orEmpty()
        if (ids.isEmpty()) return emptyList()
        val params = buildMap {
            put("watch_region", region)
            put("with_watch_providers", ids.joinToString("|"))
            put("with_watch_monetization_types", "flatrate|free|ads")
            put("sort_by", sort)
            put("page", page.toString())
            put("include_adult", "false")
            if (sort.startsWith("vote_average")) put("vote_count.gte", "300")
            if (genreId != null) put("with_genres", genreId.toString())
        }
        return films(get("/discover/movie", params))
    }

    suspend fun genres(): List<Genre> {
        genresCache?.let { return it }
        val g = get("/genre/movie/list").obj()?.get("genres").arr().mapNotNull { e ->
            val o = e.obj(); val id = o.int("id"); val name = o.str("name")
            if (id != null && name != null) Genre(id, name) else null
        }
        genresCache = g
        return g
    }
}
