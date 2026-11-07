package com.joelaspinall.cinemaos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    @Test fun finishPromptIgnoresShortAndStaleTrips() {
        assertEquals(Rules.Finish.Ignore, Rules.finish(5, 120))
        assertEquals(Rules.Finish.Ignore, Rules.finish(19 * 60, 120))
    }

    @Test fun finishPromptUsesRuntimeWhenKnown() {
        assertEquals(Rules.Finish.Ask(likely = true), Rules.finish(95, 120))   // 79% of runtime
        assertEquals(Rules.Finish.Ask(likely = false), Rules.finish(60, 120))  // half way
        assertEquals(Rules.Finish.Ask(likely = true), Rules.finish(80, null))  // no runtime: 75 min rule
        assertEquals(Rules.Finish.Ask(likely = false), Rules.finish(40, null))
    }

    @Test fun plexFinishedAtNinetyPercent() {
        assertTrue(Rules.plexFinished(9_000, 10_000))
        assertFalse(Rules.plexFinished(8_999, 10_000))
        assertFalse(Rules.plexFinished(5_000, 0))
    }

    @Test fun filmsSuggestedByMoreLovedFilmsRankHigher() {
        val ranked = Rules.rankRecommendations(listOf(
            5.0 to listOf(10, 20, 30),
            4.5 to listOf(20, 40),
            3.0 to listOf(50),
        ))
        assertEquals(20, ranked.first())        // suggested by two highly rated films
        assertEquals(50, ranked.last())         // only from a 3-star film
        assertTrue(ranked.indexOf(10) < ranked.indexOf(30)) // higher TMDB rank wins
    }

    @Test fun readsAppleTvLists() {
        val items = Rules.parseAppleList("""
            Drive (2011)	2011
            Dune: Part Two	2024
            Heat (1995)
            Alien, 1979
            Whiplash
            Dune: Part Two	2024
        """.trimIndent())
        assertEquals(listOf("Drive", "Dune: Part Two", "Heat", "Alien", "Whiplash"), items.map { it.title })
        assertEquals(listOf(2011, 2024, 1995, 1979, null), items.map { it.year })
    }
}
