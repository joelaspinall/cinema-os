package com.joelaspinall.cinemaos.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Letterboxd has no open API, so:
 *  - recent diary entries come from Joel's public RSS feed,
 *  - full history and watchlist come from his data export (uploaded once from his phone),
 *  - new logs go back via a file in Letterboxd's own import format.
 */
class Letterboxd(private val store: Store) {

    /** Reads the latest ~50 diary entries from letterboxd.com/<user>/rss/. */
    suspend fun syncRss(): Int {
        val user = store.settings.value.letterboxdUser.trim().ifBlank { return 0 }
        val xml = Http.getText("https://letterboxd.com/$user/rss/", mapOf("User-Agent" to "CinemaOS/1.0 (Android TV)"))
        val items = parseRss(xml)
        if (items.isNotEmpty()) {
            store.updateData { d ->
                val fresh = items.map { it.title + it.year + it.date }.toSet()
                d.copy(rss = items + d.rss.filterNot { (it.title + it.year + it.date) in fresh }.take(500))
            }
        }
        return items.size
    }

    private fun parseRss(xml: String): List<HistoryItem> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        val out = mutableListOf<HistoryItem>()
        var inItem = false
        var fields = HashMap<String, String>()
        var tag: String? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (p.name == "item") { inItem = true; fields = HashMap() }
                }
                XmlPullParser.TEXT -> if (inItem && tag != null) {
                    fields[tag!!] = (fields[tag!!] ?: "") + p.text
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item") {
                        inItem = false
                        val title = fields["letterboxd:filmTitle"]?.trim()
                        if (!title.isNullOrBlank()) {
                            out += HistoryItem(
                                title = title,
                                year = fields["letterboxd:filmYear"]?.trim()?.toIntOrNull(),
                                rating = fields["letterboxd:memberRating"]?.trim()?.toDoubleOrNull(),
                                tmdbId = fields["tmdb:movieId"]?.trim()?.toIntOrNull(),
                                date = fields["letterboxd:watchedDate"]?.trim(),
                            )
                        }
                    }
                    tag = null
                }
            }
        }
        return out
    }

    data class ImportResult(val watched: Int, val rated: Int, val watchlist: Int)

    /** Reads the .zip from Letterboxd → Settings → Data → Export your data. */
    fun importExport(input: InputStream): ImportResult {
        val files = HashMap<String, String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (!e.isDirectory && name.endsWith(".csv") && !e.name.contains("/")) {
                    files[name] = zip.readBytes().toString(Charsets.UTF_8)
                } else if (!e.isDirectory && name.endsWith(".csv") && name !in files) {
                    // Nested folders (e.g. lists/) are ignored unless no top-level file exists.
                    if (e.name.startsWith("lists/") || e.name.startsWith("deleted/")) continue
                    files[name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return importCsvFiles(files)
    }

    /** Also accepts a single CSV (watched.csv / ratings.csv / diary.csv / watchlist.csv). */
    fun importCsv(name: String, text: String): ImportResult = importCsvFiles(mapOf(name to text))

    private fun importCsvFiles(files: Map<String, String>): ImportResult {
        fun rows(name: String) = files[name]?.let { parseCsv(it) }.orEmpty()

        val history = LinkedHashMap<String, HistoryItem>()
        fun add(r: Map<String, String>, withRating: Boolean) {
            val title = r["Name"]?.takeIf { it.isNotBlank() } ?: return
            val year = r["Year"]?.toIntOrNull()
            val key = filmKey(title, year)
            val old = history[key]
            val rating = if (withRating) r["Rating"]?.toDoubleOrNull() else null
            val date = r["Watched Date"]?.takeIf { it.isNotBlank() } ?: r["Date"]
            history[key] = HistoryItem(title, year, rating ?: old?.rating, old?.tmdbId, old?.date ?: date)
        }
        rows("watched.csv").forEach { add(it, false) }
        rows("diary.csv").forEach { add(it, true) }
        rows("ratings.csv").forEach { add(it, true) }

        val watchlist = rows("watchlist.csv").mapNotNull { r ->
            val title = r["Name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HistoryItem(title, r["Year"]?.toIntOrNull(), date = r["Date"])
        }

        store.updateData { d ->
            d.copy(
                history = if (history.isNotEmpty()) history.values.toList() else d.history,
                watchlist = if (files.containsKey("watchlist.csv")) watchlist else d.watchlist,
                historyImportedAt = java.time.LocalDate.now().toString(),
            )
        }
        return ImportResult(history.size, history.values.count { it.rating != null }, watchlist.size)
    }

    /** Builds a file for letterboxd.com/import from entries not yet sent. */
    fun exportCsv(entries: List<DiaryEntry>): String = buildString {
        append("tmdbID,Title,Year,WatchedDate,Rating,Rewatch,Review\n")
        for (e in entries) {
            append(listOf(
                e.tmdbId?.toString() ?: "",
                e.title,
                e.year?.toString() ?: "",
                e.watchedDate,
                e.rating?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "",
                if (e.rewatch) "true" else "false",
                e.review,
            ).joinToString(",") { csvField(it) })
            append('\n')
        }
    }

    private fun csvField(v: String) =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    companion object {
        fun filmUrl(film: Film) =
            if (film.tmdbId != null) "https://letterboxd.com/tmdb/${film.tmdbId}/"
            else "https://letterboxd.com/search/" + android.net.Uri.encode(film.title) + "/"

        /** Minimal CSV reader that handles quoted fields with commas and newlines. */
        fun parseCsv(text: String): List<Map<String, String>> {
            val records = mutableListOf<List<String>>()
            var field = StringBuilder()
            var record = mutableListOf<String>()
            var quoted = false
            var i = 0
            val t = text.removePrefix("﻿")
            while (i < t.length) {
                val c = t[i]
                when {
                    quoted && c == '"' && i + 1 < t.length && t[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    !quoted && c == ',' -> { record.add(field.toString()); field = StringBuilder() }
                    !quoted && (c == '\n' || c == '\r') -> {
                        if (c == '\r' && i + 1 < t.length && t[i + 1] == '\n') i++
                        record.add(field.toString()); field = StringBuilder()
                        if (record.any { it.isNotEmpty() }) records.add(record)
                        record = mutableListOf()
                    }
                    else -> field.append(c)
                }
                i++
            }
            record.add(field.toString())
            if (record.any { it.isNotEmpty() }) records.add(record)
            if (records.isEmpty()) return emptyList()
            val header = records.first().map { it.trim() }
            return records.drop(1).map { r -> header.indices.associate { header[it] to (r.getOrNull(it)?.trim() ?: "") } }
        }
    }
}
