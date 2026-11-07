package com.joelaspinall.cinemaos.data

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Plex: sign in with a 4-character code at plex.tv/link, then read the movie
 * library from Joel's server (his MacBook), play files and mark them watched.
 */
class Plex(private val store: Store) {
    private val s get() = store.settings.value

    /** Every movie in the library, filled by [loadLibrary]. */
    @Volatile var library: List<Film> = emptyList(); private set
    @Volatile private var byTmdb: Map<Int, Film> = emptyMap()
    @Volatile private var byKey: Map<String, Film> = emptyMap()
    private val partKeys = HashMap<String, String>()
    private val durations = HashMap<String, Long>()
    private val offsets = HashMap<String, Long>()
    private val resolutions = HashMap<String, String>()
    private val qualityCache = HashMap<String, Quality>()

    val isSignedIn get() = s.plexToken.isNotBlank()
    val hasServer get() = s.plexServerUri.isNotBlank()

    private fun headers(token: String? = null) = buildMap {
        put("X-Plex-Product", "Cinema OS")
        put("X-Plex-Client-Identifier", s.plexClientId)
        put("X-Plex-Device", "Android TV")
        put("X-Plex-Platform", "Android")
        put("X-Plex-Version", "1.0")
        if (token != null) put("X-Plex-Token", token)
    }

    // ---- Sign in --------------------------------------------------------------

    data class Pin(val id: Long, val code: String)

    suspend fun createPin(): Pin {
        val o = Http.postJson("https://plex.tv/api/v2/pins?strong=false", headers()).obj()
        return Pin(o.str("id")!!.toLong(), o.str("code")!!)
    }

    /** Returns true once Joel has entered the code at plex.tv/link. */
    suspend fun checkPin(pin: Pin): Boolean {
        val o = Http.getJson("https://plex.tv/api/v2/pins/${pin.id}?code=${pin.code}", headers()).obj()
        val token = o.str("authToken") ?: return false
        store.updateSettings { it.copy(plexToken = token) }
        findServer()
        return true
    }

    /** Picks Joel's server and the fastest address that answers (home Wi-Fi first). */
    suspend fun findServer(): Boolean {
        val token = s.plexToken.ifBlank { return false }
        val resources = Http.getJson(
            "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", headers(token),
        ).arr().mapNotNull { it.obj() }
        val servers = resources.filter { it.str("provides")?.contains("server") == true }
            .sortedByDescending { if (it.bool("owned") == true) 1 else 0 }
        for (srv in servers) {
            val srvToken = srv.str("accessToken") ?: token
            val conns = srv["connections"].arr().mapNotNull { it.obj() }
                .sortedBy { c -> when { c.bool("local") == true -> 0; c.bool("relay") == true -> 2; else -> 1 } }
            for (c in conns) {
                val uri = c.str("uri") ?: continue
                val ok = runCatching { Http.getText("$uri/identity", headers(srvToken), quick = true); true }.getOrDefault(false)
                if (ok) {
                    store.updateSettings {
                        it.copy(plexServerUri = uri, plexServerToken = srvToken, plexServerName = srv.str("name") ?: "Plex")
                    }
                    return true
                }
            }
        }
        return false
    }

    fun signOut() {
        store.updateSettings { it.copy(plexToken = "", plexServerUri = "", plexServerToken = "", plexServerName = "") }
        library = emptyList(); byTmdb = emptyMap(); byKey = emptyMap()
    }

    // ---- Library ---------------------------------------------------------------

    private suspend fun server(path: String, extra: String = ""): JsonObject? {
        val sep = if (path.contains('?')) "&" else "?"
        val url = "${s.plexServerUri}$path${sep}includeGuids=1$extra"
        return Http.getJson(url, headers(s.plexServerToken)).obj()?.get("MediaContainer").obj()
    }

    /** Retries the address lookup if the MacBook's address has changed. */
    private suspend fun <T> withServer(block: suspend () -> T): T =
        try { block() } catch (e: Exception) { if (findServer()) block() else throw e }

    fun image(thumb: String?, w: Int = if (Art.is4k) 780 else 500, h: Int = w * 3 / 2): String? {
        thumb ?: return null
        val u = Uri.parse("${s.plexServerUri}/photo/:/transcode").buildUpon()
            .appendQueryParameter("width", "$w").appendQueryParameter("height", "$h")
            .appendQueryParameter("minSize", "1").appendQueryParameter("upscale", "1")
            .appendQueryParameter("url", thumb)
        // No token in the address: [PlexAuth] adds it as a header when the image loads.
        return u.build().toString()
    }

    private fun toFilm(m: JsonObject): Film? {
        if (m.str("type") != "movie") return null
        val key = m.str("ratingKey") ?: return null
        val tmdb = m["Guid"].arr().mapNotNull { it.obj().str("id") }
            .firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toIntOrNull()
        m["Media"].arr().firstOrNull()?.obj()?.get("Part").arr().firstOrNull()?.obj().str("key")
            ?.let { synchronized(partKeys) { partKeys[key] = it } }
        m.str("duration")?.toLongOrNull()?.let { synchronized(durations) { durations[key] = it } }
        m.str("viewOffset")?.toLongOrNull()?.let { synchronized(offsets) { offsets[key] = it } }
        m["Media"].arr().firstOrNull()?.obj().str("videoResolution")
            ?.let { synchronized(resolutions) { resolutions[key] = resolutionLabel(it) } }
        return Film(
            tmdbId = tmdb,
            title = m.str("title") ?: return null,
            year = m.int("year"),
            poster = image(m.str("thumb")),
            backdrop = image(m.str("art"), if (Art.is4k) 3840 else 1920, if (Art.is4k) 2160 else 1080),
            overview = m.str("summary"),
            plexKey = key,
        )
    }

    suspend fun loadLibrary(): List<Film> = withServer {
        val sections = server("/library/sections")?.get("Directory").arr().mapNotNull { it.obj() }
            .filter { it.str("type") == "movie" }.mapNotNull { it.str("key") }
        val films = coroutineScope {
            sections.map { key ->
                async { server("/library/sections/$key/all", "&type=1")?.get("Metadata").arr().mapNotNull { it.obj()?.let(::toFilm) } }
            }.flatMap { it.await().orEmpty() }
        }.sortedBy { it.title.lowercase().removePrefix("the ") }
        library = films
        byTmdb = films.filter { it.tmdbId != null }.associateBy { it.tmdbId!! }
        byKey = films.associateBy { it.key }
        films
    }

    suspend fun onDeck(): List<Film> = withServer {
        server("/library/onDeck")?.get("Metadata").arr().mapNotNull { it.obj()?.let(::toFilm) }
    }

    suspend fun recentlyAdded(): List<Film> = withServer {
        server("/library/recentlyAdded", "&X-Plex-Container-Start=0&X-Plex-Container-Size=40")
            ?.get("Metadata").arr().mapNotNull { it.obj()?.let(::toFilm) }
    }

    /** The Plex copy of a film, if Joel has one. */
    fun find(film: Film): Film? =
        film.plexKey?.let { k -> library.firstOrNull { it.plexKey == k } ?: film }
            ?: film.tmdbId?.let { byTmdb[it] } ?: byKey[film.key]

    fun streamUrl(film: Film): String? {
        val part = synchronized(partKeys) { partKeys[film.plexKey] } ?: return null
        return "${s.plexServerUri}$part"
    }

    /** Headers the video player sends, so the token never appears in a URL or a log. */
    fun playbackHeaders(): Map<String, String> = headers(s.plexServerToken)

    /** Adds the Plex token as a header to image requests for Joel's server only. */
    inner class PlexAuth : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val req = chain.request()
            val server = s.plexServerUri.toHttpUrlOrNull()
            return if (server != null && req.url.host == server.host && req.url.port == server.port && s.plexServerToken.isNotBlank()) {
                chain.proceed(req.newBuilder().header("X-Plex-Token", s.plexServerToken).build())
            } else chain.proceed(req)
        }
    }

    /** Quick resolution from the library listing ("4K", "1080p"…), for poster badges. */
    fun resolution(film: Film): String? = film.plexKey?.let { synchronized(resolutions) { resolutions[it] } }

    /** Exact picture and sound of Joel's file, read from its video and audio tracks. */
    suspend fun quality(film: Film): Quality? {
        val key = film.plexKey ?: return null
        synchronized(qualityCache) { qualityCache[key] }?.let { return it }
        val m = runCatching { withServer { server("/library/metadata/$key") } }.getOrNull()
            ?.get("Metadata").arr().firstOrNull().obj() ?: return null
        val media = m["Media"].arr().firstOrNull().obj()
        val streams = media?.get("Part").arr().firstOrNull()?.obj()?.get("Stream").arr().mapNotNull { it.obj() }
        val video = streams.firstOrNull { it.int("streamType") == 1 }
        val audio = streams.filter { it.int("streamType") == 2 }
            .let { a -> a.firstOrNull { it.bool("selected") == true } ?: a.firstOrNull() }

        val hdr = buildList {
            if (video?.bool("DOVIPresent") == true) add("Dolby Vision")
            when (video.str("colorTrc")) {
                "smpte2084" -> add("HDR10")
                "arib-std-b67" -> add("HLG")
            }
        }.distinct()
        val audioDesc = (audio.str("extendedDisplayTitle") ?: audio.str("displayTitle") ?: "").lowercase()
        val codec = audio.str("codec")?.lowercase()
        val channels = audio.int("channels")
        val sound = buildList {
            when {
                "atmos" in audioDesc -> add("Dolby Atmos")
                codec == "truehd" -> add("Dolby TrueHD")
                codec == "dca" && ("ma" in audioDesc || "hd" in audioDesc) -> add("DTS-HD MA")
                codec == "dca" -> add("DTS")
            }
            when {
                channels == null -> {}
                channels >= 8 -> add("7.1")
                channels >= 6 -> add("5.1")
                channels == 2 -> add("Stereo")
            }
        }
        val q = Quality(
            resolution = media.str("videoResolution")?.let(::resolutionLabel),
            hdr = hdr,
            audio = sound,
        )
        synchronized(qualityCache) { qualityCache[key] = q }
        return q
    }

    private fun resolutionLabel(r: String) = when (r.lowercase()) {
        "4k", "2160" -> "4K"
        "1080" -> "1080p"
        "720" -> "720p"
        else -> "SD"
    }

    fun resumePosition(film: Film): Long = synchronized(offsets) { offsets[film.plexKey] } ?: 0L

    /** Tells Plex Joel has watched it, so it leaves "Continue watching". */
    suspend fun markWatched(film: Film) {
        val key = film.plexKey ?: return
        runCatching {
            Http.getText(
                "${s.plexServerUri}/:/scrobble?identifier=com.plexapp.plugins.library&key=$key",
                headers(s.plexServerToken),
            )
        }
    }

    /** Saves where Joel stopped, so Plex can resume there. */
    suspend fun saveProgress(film: Film, positionMs: Long, durationMs: Long) {
        val key = film.plexKey ?: return
        synchronized(offsets) { offsets[key] = positionMs }
        runCatching {
            Http.getText(
                "${s.plexServerUri}/:/timeline?ratingKey=$key&key=%2Flibrary%2Fmetadata%2F$key" +
                    "&state=stopped&time=$positionMs&duration=$durationMs",
                headers(s.plexServerToken),
            )
        }
    }
}
