package com.joelaspinall.cinemaos.data

/**
 * Small decisions kept apart from the screens so automated tests can check them.
 */
object Rules {
    sealed interface Finish {
        /** Too short (under 15 minutes) or too long ago (over 18 hours): forget it. */
        data object Ignore : Finish
        /** Ask "Did you finish it?"; [likely] picks the wording. */
        data class Ask(val likely: Boolean) : Finish
    }

    /** What to do when Joel comes back from a streaming app [minutesAway] after starting a film. */
    fun finish(minutesAway: Long, runtimeMinutes: Int?): Finish = when {
        minutesAway < 15 || minutesAway > 18 * 60 -> Finish.Ignore
        else -> Finish.Ask(runtimeMinutes?.let { minutesAway >= it * 0.75 } ?: (minutesAway >= 75))
    }

    /** A Plex film counts as finished once 90% has played. */
    fun plexFinished(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs >= durationMs * 0.9

    /**
     * Ranks recommended films. Each seed is a film Joel rated, with TMDB's recommendations for it in order.
     * A candidate scores more for every seed that suggests it, for higher seed ratings and for a higher rank.
     */
    fun rankRecommendations(seeds: List<Pair<Double, List<Int>>>, perSeed: Int = 15): List<Int> {
        val score = HashMap<Int, Double>()
        for ((rating, recs) in seeds) {
            recs.take(perSeed).forEachIndexed { rank, id ->
                score[id] = (score[id] ?: 0.0) + (rating - 2.5) * (1.0 / (1 + rank * 0.25))
            }
        }
        return score.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Reads a pasted or uploaded Apple TV list: "Title<TAB>Year", "Title (Year)", "Title, Year" or "Title". */
    fun parseAppleList(text: String): List<HistoryItem> {
        val inBrackets = Regex("""^(.*?)\s*\((\d{4})\)\s*$""")
        val withComma = Regex("""^(.*),\s*(\d{4})$""")
        return text.lines().map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("name\tyear", true) }
            .mapNotNull { line ->
                val tab = line.split('\t')
                val (title, year) = when {
                    // Some Apple titles carry the year in the name too, e.g. "Drive (2011)".
                    tab.size >= 2 -> tab[0].trim().replace(Regex("""\s*\(\d{4}\)$"""), "") to tab[1].trim().take(4).toIntOrNull()
                    inBrackets.matches(line) -> inBrackets.find(line)!!.let { it.groupValues[1].trim() to it.groupValues[2].toInt() }
                    withComma.matches(line) -> withComma.find(line)!!.let { it.groupValues[1].trim().trim('"') to it.groupValues[2].toInt() }
                    else -> line to null
                }
                title.takeIf { it.isNotBlank() }?.let { HistoryItem(it, year) }
            }
            .distinctBy { it.title.lowercase() + it.year }
    }
}
