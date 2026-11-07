package com.joelaspinall.cinemaos.data

import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The Movie Database: film and series info, UK "where to watch" data (from JustWatch),
 * full service catalogues and recommendations. Free API key from themoviedb.org.
 */
class Tmdb(private val store: Store) {
    private val base = "https://api.themoviedb.org/3"
    private val img = "https://image.tmdb.org/t/p"
    private val region = "GB"

    private val providerCache = ConcurrentHashMap<String, ProviderInfo>()
    private val detailsCache = ConcurrentHashMap<String, FilmDetails>()
    private var serviceProviderIds: Map<String, List<Int>>? = null
    private val genresCache = ConcurrentHashMap<Boolean, List<Genre>>()
    private val providerLock = Mutex()

    val isConfigured: Boolean get() = store.settings.value.tmdbToken.isNotBlank()

    data class ProviderInfo(val flatrate: List<String>, val rent: List<String>, val buy: List<String>)

    private fun kind(tv: Boolean) = if (tv) "tv" else "movie"
    private fun cacheKey(id: Int, tv: Boolean) = "${kind(tv)}:$id"

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

    /** Reads a film or a series (series use "name" and "first_air_date"). */
    private fun toFilm(o: JsonObject?, tv: Boolean): Film? {
        o ?: return null
        val title = (if (tv) o.str("name") else o.str("title")) ?: o.str("title") ?: o.str("name") ?: return null
        val date = if (tv) o.str("first_air_date") else o.str("release_date")
        return Film(
            tmdbId = o.int("id"),
            title = title,
            year = date?.take(4)?.toIntOrNull(),
            poster = poster(o.str("poster_path")),
            backdrop = backdrop(o.str("backdrop_path")),
            overview = o.str("overview"),
            tv = tv,
        )
    }

    private fun list(e: JsonElement, tv: Boolean) = e.obj()?.get("results").arr().mapNotNull { toFilm(it.obj(), tv) }

    suspend fun test(): Boolean = runCatching { get("/configuration"); true }.getOrDefault(false)

    /** Films and series together, most relevant first. */
    suspend fun search(query: String): List<Film> =
        get("/search/multi", mapOf("query" to query, "region" to region, "include_adult" to "false"))
            .obj()?.get("results").arr().mapNotNull { e ->
                val o = e.obj()
                when (o.str("media_type")) {
                    "movie" -> toFilm(o, tv = false)
                    "tv" -> toFilm(o, tv = true)
                    else -> null
                }
            }

    suspend fun popular(): List<Film> = list(get("/movie/popular", mapOf("region" to region)), false)

    /** Finds the TMDB id for a Letterboxd / Apple title + year (cached in the store). */
    suspend fun resolve(title: String, year: Int?): Int? {
        val key = filmKey(title, year)
        store.data.value.resolved[key]?.let { return it }
        val params = buildMap {
            put("query", title)
            if (year != null) put("primary_release_year", year.toString())
        }
        val hit = runCatching { list(get("/search/movie", params), false).firstOrNull() }.getOrNull()
            ?: if (year != null) runCatching { list(get("/search/movie", mapOf("query" to title)), false).firstOrNull() }.getOrNull() else null
        val id = hit?.tmdbId ?: return null
        store.updateData { it.copy(resolved = it.resolved + (key to id)) }
        return id
    }

    suspend fun film(id: Int, tv: Boolean = false): Film? = details(id, tv)?.film

    suspend fun details(id: Int, tv: Boolean = false): FilmDetails? {
        val ck = cacheKey(id, tv)
        detailsCache[ck]?.let { return it }
        val o = runCatching { get("/${kind(tv)}/$id").obj() }.getOrNull() ?: return null
        val film = toFilm(o, tv) ?: return null
        val runtime = if (tv) o["episode_run_time"].arr().firstOrNull()?.asStringOrNull()?.toIntOrNull()
        else o.int("runtime")
        val d = FilmDetails(
            film = film,
            runtime = runtime?.takeIf { it > 0 },
            genres = o["genres"].arr().mapNotNull { it.obj().str("name") },
            tagline = o.str("tagline"),
            rating = o.dbl("vote_average"),
            seasons = if (tv) o.int("number_of_seasons") else null,
            status = if (tv) o.str("status") else null,
        )
        detailsCache[ck] = d
        return d
    }

    suspend fun recommendations(id: Int, tv: Boolean = false): List<Film> =
        runCatching { list(get("/${kind(tv)}/$id/recommendations"), tv) }.getOrDefault(emptyList())

    /** What's available on which UK service for one film or series. */
    suspend fun providers(id: Int, tv: Boolean = false): ProviderInfo {
        val ck = cacheKey(id, tv)
        providerCache[ck]?.let { return it }
        val gb = runCatching { get("/${kind(tv)}/$id/watch/providers").obj()?.get("results").obj()?.get(region).obj() }.getOrNull()
        fun names(k: String) = gb?.get(k).arr().mapNotNull { it.obj().str("provider_name") }
        val info = ProviderInfo(names("flatrate") + names("ads") + names("free"), names("rent"), names("buy"))
        providerCache[ck] = info
        return info
    }

    /** Which of Joel's services a film or series is included with. */
    suspend fun subscriptionsFor(id: Int, tv: Boolean = false): List<Service> {
        val mine = store.settings.value.services.mapNotNull { serviceById(it) }
        val flat = providers(id, tv).flatrate.map { it.lowercase() }
        return mine.filter { s -> flat.any { name -> s.tmdbPrefixes.any { name.startsWith(it) } } }
    }

    /** TMDB's numeric provider ids for each of Joel's services in the UK. */
    private suspend fun providerIds(): Map<String, List<Int>> = providerLock.withLock {
        serviceProviderIds?.let { return it }
        val all = listOf("movie", "tv").flatMap { k ->
            runCatching {
                get("/watch/providers/$k", mapOf("watch_region" to region)).obj()?.get("results").arr()
                    .mapNotNull { e -> e.obj()?.let { (it.int("provider_id") ?: return@let null) to (it.str("provider_name") ?: "") } }
            }.getOrDefault(emptyList())
        }.distinct()
        val map = SERVICES.associate { s ->
            s.id to all.filter { (_, name) -> s.tmdbPrefixes.any { name.lowercase().startsWith(it) } }.map { it.first }.distinct()
        }
        serviceProviderIds = map
        map
    }

    /** A full UK catalogue for one service, a page (20 titles) at a time. */
    suspend fun catalogue(serviceId: String, page: Int, genreId: Int?, sort: String, tv: Boolean = false): List<Film> {
        val ids = providerIds()[serviceId].orEmpty()
        if (ids.isEmpty()) return emptyList()
        val sortBy = if (tv && sort == "primary_release_date.desc") "first_air_date.desc" else sort
        val params = buildMap {
            put("watch_region", region)
            put("with_watch_providers", ids.joinToString("|"))
            put("with_watch_monetization_types", "flatrate|free|ads")
            put("sort_by", sortBy)
            put("page", page.toString())
            put("include_adult", "false")
            if (sortBy.startsWith("vote_average")) put("vote_count.gte", if (tv) "150" else "300")
            if (sortBy.endsWith("date.desc")) put(if (tv) "first_air_date.lte" else "primary_release_date.lte", java.time.LocalDate.now().toString())
            if (genreId != null) put("with_genres", genreId.toString())
        }
        return list(get("/discover/${kind(tv)}", params), tv)
    }

    suspend fun genres(tv: Boolean = false): List<Genre> {
        genresCache[tv]?.let { return it }
        val g = get("/genre/${kind(tv)}/list").obj()?.get("genres").arr().mapNotNull { e ->
            val o = e.obj(); val id = o.int("id"); val name = o.str("name")
            if (id != null && name != null) Genre(id, name) else null
        }
        genresCache[tv] = g
        return g
    }
}
