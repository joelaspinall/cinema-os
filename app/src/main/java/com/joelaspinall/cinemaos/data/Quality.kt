package com.joelaspinall.cinemaos.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** Picture and sound quality, e.g. "4K · Dolby Vision · Dolby Atmos". */
data class Quality(
    val resolution: String? = null,      // "4K", "1080p", "720p", "SD"
    val hdr: List<String> = emptyList(), // "Dolby Vision", "HDR10+", "HDR10", "HLG"
    val audio: List<String> = emptyList(), // "Dolby Atmos", "5.1", "DTS-HD MA"
) {
    val label: String get() = (listOfNotNull(resolution) + hdr + audio).joinToString(" · ")
    val is4k get() = resolution == "4K"

    /** Limits a service's best quality to what this TV can show. */
    fun cappedTo(tv: Quality): Quality = copy(
        resolution = if (is4k && !tv.is4k) "1080p" else resolution,
        hdr = hdr.filter { it in tv.hdr },
    )
}

/** A plan on one service and the best quality it streams in the UK. */
data class Plan(val id: String, val label: String, val quality: Quality)

private val DV_4K = listOf("Dolby Vision", "HDR10")
private val ATMOS = listOf("Dolby Atmos")

/**
 * Best quality each UK plan allows. Each film can be lower than this — services
 * don't publish per-film quality — so the app shows it as "up to".
 */
val PLANS: Map<String, List<Plan>> = mapOf(
    "netflix" to listOf(
        Plan("ads", "Standard with adverts", Quality("1080p", audio = listOf("5.1"))),
        Plan("standard", "Standard", Quality("1080p", audio = listOf("5.1"))),
        Plan("premium", "Premium", Quality("4K", DV_4K, ATMOS)),
    ),
    "prime" to listOf(
        Plan("ads", "Prime (with adverts)", Quality("4K", listOf("HDR10", "HDR10+"), listOf("5.1"))),
        Plan("adfree", "Prime + ad-free", Quality("4K", listOf("Dolby Vision", "HDR10", "HDR10+"), ATMOS)),
    ),
    "disney" to listOf(
        Plan("standard_ads", "Standard with adverts", Quality("1080p", audio = listOf("5.1"))),
        Plan("standard", "Standard", Quality("1080p", audio = listOf("5.1"))),
        Plan("premium", "Premium", Quality("4K", DV_4K, ATMOS)),
    ),
    "hbo" to listOf(
        Plan("basic", "Basic with adverts", Quality("1080p", audio = listOf("5.1"))),
        Plan("standard", "Standard", Quality("1080p", audio = listOf("5.1"))),
        Plan("premium", "Premium", Quality("4K", DV_4K, ATMOS)),
    ),
    "apple" to listOf(
        Plan("standard", "Apple TV", Quality("4K", DV_4K, ATMOS)),
    ),
    "iplayer" to listOf(
        // Most of iPlayer is HD; some big dramas and sport are in 4K HLG.
        Plan("free", "Free with TV licence", Quality("4K", listOf("HLG"), listOf("Stereo"))),
    ),
)

fun planFor(serviceId: String, planId: String?): Plan? {
    val plans = PLANS[serviceId] ?: return null
    return plans.firstOrNull { it.id == planId } ?: plans.last()
}

object TvDisplay {
    /** What this TV's screen supports: resolution and HDR formats. */
    @Suppress("DEPRECATION")
    fun capabilities(context: Context): Quality = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val maxWidth = d.supportedModes.maxOfOrNull { maxOf(it.physicalWidth, it.physicalHeight) } ?: 1920
        val hdr = d.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty().mapNotNull {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                else -> null
            }
        }
        Quality(if (maxWidth >= 3800) "4K" else "1080p", hdr)
    }.getOrDefault(Quality("4K", listOf("HDR10")))
}
