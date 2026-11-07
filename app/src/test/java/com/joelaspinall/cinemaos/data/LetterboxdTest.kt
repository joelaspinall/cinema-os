package com.joelaspinall.cinemaos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxdTest {

    @Test fun readsQuotedFieldsWithCommasAndNewLines() {
        val csv = "﻿Date,Name,Year,Rating\n2024-01-02,\"Crouching Tiger, Hidden Dragon\",2000,4.5\n2024-01-03,\"Line one\nline two\",1999,3\n"
        val rows = Letterboxd.parseCsv(csv)
        assertEquals(2, rows.size)
        assertEquals("Crouching Tiger, Hidden Dragon", rows[0]["Name"])
        assertEquals("4.5", rows[0]["Rating"])
        assertEquals("Line one\nline two", rows[1]["Name"])
        assertEquals("2024-01-02", rows[0]["Date"]) // byte-order mark removed from the first header
    }

    @Test fun mergesWatchedDiaryAndRatings() {
        val files = mapOf(
            "watched.csv" to "Date,Name,Year\n2023-05-01,Heat,1995\n2023-05-02,Alien,1979\n",
            "diary.csv" to "Date,Name,Year,Rating,Watched Date\n2023-05-03,Heat,1995,4,2023-05-01\n",
            "ratings.csv" to "Date,Name,Year,Rating\n2024-02-01,Heat,1995,5\n",
            "watchlist.csv" to "Date,Name,Year\n2024-03-01,Ran,1985\n",
        )
        val p = Letterboxd.parseExport(files)
        assertEquals(2, p.history.size)
        val heat = p.history.first { it.title == "Heat" }
        assertEquals(5.0, heat.rating!!, 0.0) // the later rating wins
        assertEquals(null, p.history.first { it.title == "Alien" }.rating)
        assertEquals(listOf("Ran"), p.watchlist.map { it.title })
        assertTrue(p.hasWatchlist)
        assertFalse(Letterboxd.parseExport(mapOf("watched.csv" to "Name,Year\nHeat,1995\n")).hasWatchlist)
    }

    @Test fun buildsLetterboxdImportFile() {
        val csv = Letterboxd.buildImportCsv(listOf(
            DiaryEntry("1", 949, "Heat", 1995, "2026-11-06", 4.0, rewatch = true),
            DiaryEntry("2", null, "Crouching Tiger, Hidden Dragon", 2000, "2026-11-07", 3.5, review = "Said \"wow\""),
            DiaryEntry("3", 1, "No Rating", null, "2026-11-07", null),
        ))
        val lines = csv.trimEnd().lines()
        assertEquals("tmdbID,Title,Year,WatchedDate,Rating,Rewatch,Review", lines[0])
        assertEquals("949,Heat,1995,2026-11-06,4,true,", lines[1])
        assertEquals(",\"Crouching Tiger, Hidden Dragon\",2000,2026-11-07,3.5,false,\"Said \"\"wow\"\"\"", lines[2])
        assertEquals("1,No Rating,,2026-11-07,,false,", lines[3])
        // The file must read back with the same values.
        val back = Letterboxd.parseCsv(csv)
        assertEquals("Crouching Tiger, Hidden Dragon", back[1]["Title"])
        assertEquals("Said \"wow\"", back[1]["Review"])
    }
}
