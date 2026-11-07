package com.joelaspinall.cinemaos.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityTest {
    private val premium = Quality("4K", listOf("Dolby Vision", "HDR10"), listOf("Dolby Atmos"))

    @Test fun cappedToWhatTheTvCanShow() {
        val hdTv = Quality("1080p", listOf("HDR10"))
        assertEquals(Quality("1080p", listOf("HDR10"), listOf("Dolby Atmos")), premium.cappedTo(hdTv))
        val dvTv = Quality("4K", listOf("Dolby Vision", "HDR10", "HLG"))
        assertEquals(premium, premium.cappedTo(dvTv))
    }

    @Test fun labelsReadNaturally() {
        assertEquals("4K · Dolby Vision · HDR10 · Dolby Atmos", premium.label)
    }

    @Test fun joelsPlansGiveTheRightQuality() {
        assertEquals("1080p", planFor("netflix", "ads")!!.quality.resolution)
        assertEquals("4K", planFor("disney", "premium")!!.quality.resolution)
        // Unknown plan falls back to the top plan.
        assertEquals("premium", planFor("hbo", "nonsense")!!.id)
    }
}
