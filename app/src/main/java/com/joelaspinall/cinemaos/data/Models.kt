package com.joelaspinall.cinemaos.data

import kotlinx.serialization.Serializable

/** One film, wherever it came from (TMDB, Plex, Letterboxd). */
@Serializable
data class Film(
    val tmdbId: Int? = null,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    val plexKey: String? = null,
) {
    val key: String get() = filmKey(title, year)
    val label: String get() = if (year != null) "$title ($year)" else title
}

fun filmKey(title: String, year: Int?): String =
    title.lowercase().replace(Regex("[^a-z0-9]"), "") + "|" + (year ?: 0)

/** A subscription service Joel pays for. */
data class Service(
    val id: String,
    val label: String,
    /** Matches TMDB provider names (lower case, startsWith). */
    val tmdbPrefixes: List<String>,
    /** Matches installed app names on the TV (lower case, contains). */
    val appLabels: List<String>,
    /** Wikidata property holding this service's own ID for a film. */
    val wikidataProp: String?,
    val urlFor: (String) -> String,
)

val SERVICES = listOf(
    Service(
        "netflix", "Netflix", listOf("netflix"), listOf("netflix"), "P1874",
    ) { "https://www.netflix.com/title/$it" },
    Service(
        "prime", "Prime Video", listOf("amazon prime video"), listOf("prime video", "amazon prime"), "P8055",
    ) { id ->
        if (id.startsWith("amzn1.")) "https://www.primevideo.com/detail?gti=$id"
        else "https://www.primevideo.com/detail/$id"
    },
    Service(
        "disney", "Disney+", listOf("disney plus", "disney+"), listOf("disney+", "disney plus"), "P7595",
    ) { "https://www.disneyplus.com/movies/-/$it" },
    Service(
        "hbo", "HBO Max", listOf("hbo max", "max"), listOf("hbo max", "max"), "P8298",
    ) { "https://play.hbomax.com/movie/$it" },
    Service(
        "apple", "Apple TV", listOf("apple tv"), listOf("apple tv"), "P9586",
    ) { "https://tv.apple.com/gb/movie/$it" },
)

fun serviceById(id: String) = SERVICES.firstOrNull { it.id == id }

@Serializable
data class DiaryEntry(
    val id: String,
    val tmdbId: Int? = null,
    val title: String,
    val year: Int? = null,
    val watchedDate: String,
    val rating: Double? = null,
    val rewatch: Boolean = false,
    val review: String = "",
    val source: String = "",
    val exported: Boolean = false,
)

/** A film from Joel's Letterboxd history (export file or RSS). */
@Serializable
data class HistoryItem(
    val title: String,
    val year: Int? = null,
    val rating: Double? = null,
    val tmdbId: Int? = null,
    val date: String? = null,
)

@Serializable
data class PendingWatch(
    val film: Film,
    val serviceId: String,
    val startedAt: Long,
    val runtimeMinutes: Int? = null,
)

@Serializable
data class StoreData(
    val diary: List<DiaryEntry> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val watchlist: List<HistoryItem> = emptyList(),
    val rss: List<HistoryItem> = emptyList(),
    val appleOwned: List<Film> = emptyList(),
    val pending: PendingWatch? = null,
    /** title|year -> TMDB id, so we only look each film up once. */
    val resolved: Map<String, Int> = emptyMap(),
    val historyImportedAt: String? = null,
    /** Apple TV purchases uploaded from the phone page, waiting to be matched to TMDB. */
    val appleImport: List<HistoryItem> = emptyList(),
    val appleImportedAt: String? = null,
)

@Serializable
data class Settings(
    val tmdbToken: String = "",
    /** Free key from omdbapi.com, for IMDb and Rotten Tomatoes scores. */
    val omdbKey: String = "",
    val letterboxdUser: String = "joelaspinall",
    val plexClientId: String = "",
    val plexToken: String = "",
    val plexServerName: String = "",
    val plexServerUri: String = "",
    val plexServerToken: String = "",
    val services: List<String> = listOf("netflix", "prime", "disney", "hbo", "apple"),
    /** Which plan Joel has on each service (service id -> plan id), for quality info. */
    val plans: Map<String, String> = mapOf(
        "netflix" to "ads", "prime" to "ads", "disney" to "premium", "hbo" to "premium",
    ),
    val updateUrl: String = "https://github.com/joelaspinall/cinema-os/releases/latest/download/cinema-os.apk",
)

/** Where a film can be watched right now. */
data class WatchOptions(
    val subscriptions: List<Service> = emptyList(),
    val plex: Film? = null,
    val appleOwned: Boolean = false,
    val rentBuy: List<String> = emptyList(),
)

data class FilmDetails(
    val film: Film,
    val runtime: Int?,
    val genres: List<String>,
    val tagline: String?,
    val rating: Double?,
    val imdbId: String? = null,
)

data class Genre(val id: Int, val name: String)

data class Row(val title: String, val films: List<Film>)
