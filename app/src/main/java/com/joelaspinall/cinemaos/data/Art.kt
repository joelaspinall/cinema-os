package com.joelaspinall.cinemaos.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Picks artwork sizes for the screen. A 4K TV gets TMDB's full-size backdrops and larger
 * posters; a 1080p TV gets sizes that look just as sharp there and load faster.
 */
object Art {
    @Volatile var is4k: Boolean = false; private set

    fun init(context: Context) {
        is4k = runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
            (d.supportedModes.maxOfOrNull { maxOf(it.physicalWidth, it.physicalHeight) } ?: 1920) >= 3800
        }.getOrDefault(false)
    }

    private val sizeSegment = Regex("""/t/p/[a-z0-9]+/""")

    /** Re-sizes a TMDB image address; other images (Plex) are returned unchanged. */
    fun tmdb(url: String?, size: String): String? =
        url?.let { if (it.contains("image.tmdb.org")) sizeSegment.replace(it, "/t/p/$size/") else it }

    /** Full-screen backdrop behind Home, Plex and film pages. */
    fun backdrop(url: String?) = tmdb(url, if (is4k) "original" else "w1280")

    /** Wide 16:9 card in a row. */
    fun card(url: String?) = tmdb(url, if (is4k) "w1280" else "w780")

    /** Portrait poster in a grid. */
    fun poster(url: String?) = tmdb(url, if (is4k) "w780" else "w500")

    /** Film title artwork (logo) shown instead of plain text. */
    fun logo(url: String?) = tmdb(url, if (is4k) "original" else "w500")
}
