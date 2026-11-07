package com.joelaspinall.cinemaos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.joelaspinall.cinemaos.data.Art
import com.joelaspinall.cinemaos.data.Film
import com.joelaspinall.cinemaos.data.FilmDetails
import com.joelaspinall.cinemaos.data.Quality
import com.joelaspinall.cinemaos.data.TvDisplay
import com.joelaspinall.cinemaos.data.planFor
import com.joelaspinall.cinemaos.data.Letterboxd
import com.joelaspinall.cinemaos.data.Service
import com.joelaspinall.cinemaos.data.Tmdb
import com.joelaspinall.cinemaos.data.serviceById

@Composable
fun DetailScreen(vm: AppViewModel, film: Film, open: (Film) -> Unit, play: (Film) -> Unit) {
    val c = vm.c
    val id = film.tmdbId
    val details by produceState<FilmDetails?>(null, film) {
        value = id?.let { runCatching { c.tmdb.details(it) }.getOrNull() }
    }
    val subs by produceState(emptyList<Service>(), film) {
        value = id?.let { runCatching { c.tmdb.subscriptionsFor(it) }.getOrNull() }.orEmpty()
    }
    val providers by produceState<Tmdb.ProviderInfo?>(null, film) {
        value = id?.let { runCatching { c.tmdb.providers(it) }.getOrNull() }
    }
    val related by produceState(emptyList<Film>(), film) {
        value = id?.let { runCatching { c.tmdb.recommendations(it) }.getOrNull() }.orEmpty()
    }
    val plexCopy = remember(film) { c.plex.find(film) }
    val context = LocalContext.current
    val tv = remember { TvDisplay.capabilities(context) }
    val plexQuality by produceState<Quality?>(null, plexCopy) {
        value = plexCopy?.let { runCatching { c.plex.quality(it) }.getOrNull() }
    }
    var owned by remember(film) { mutableStateOf(c.store.isAppleOwned(film)) }
    var showQr by remember(film) { mutableStateOf(false) }
    val marks = LocalMarks.current

    // Prefer TMDB's details, but keep Plex's link to the file.
    val f = details?.film?.copy(plexKey = film.plexKey ?: plexCopy?.plexKey) ?: film
    val first = remember { FocusRequester() }
    val apple = serviceById("apple")
    val logo by produceState<String?>(null, id) {
        value = id?.let { if (c.tmdb.isConfigured) runCatching { c.tmdb.logo(it) }.getOrNull() else null }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(if (f.backdrop != null) f else film, dim = 0.15f)
        Glide(PivotColumn) {
        LazyColumn(contentPadding = PaddingValues(top = 150.dp, bottom = 60.dp)) {
            item {
                Row(Modifier.padding(horizontal = SafeX)) {
                    Column(Modifier.width(760.dp)) {
                        if (logo != null) {
                            AsyncImage(
                                model = Art.logo(logo), contentDescription = f.title,
                                contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                                modifier = Modifier.heightIn(max = 140.dp).widthIn(max = 520.dp),
                            )
                        } else {
                            Text(f.title, style = Type.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Gap(14.dp)
                        val meta = listOfNotNull(
                            f.year?.toString(),
                            details?.runtime?.let { "${it / 60}h ${it % 60}m" },
                            details?.genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() },
                            details?.rating?.takeIf { it > 0 }?.let { "TMDB %.1f".format(it) },
                        ).joinToString("  ·  ")
                        Text(meta, style = Type.body.copy(color = Dim))
                        if (marks.seen(f)) {
                            Gap(6.dp)
                            val r = marks.rating(f)
                            Text(
                                if (r != null) "Your rating  ${stars(r)}" else "You've seen this",
                                style = Type.body.copy(color = Gold),
                            )
                        }
                        details?.tagline?.let { Gap(12.dp); Text(it, style = Type.body.copy(color = White.copy(alpha = 0.8f))) }
                        Gap(10.dp)
                        Text(f.overview ?: "", style = Type.body, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        Gap(22.dp)

                        val where = buildList {
                            if (plexCopy != null) add("Plex")
                            addAll(subs.map { it.label })
                            if (owned) add("your Apple TV library")
                        }
                        Text(
                            when {
                                where.isNotEmpty() -> "Watch on " + where.joinToString(", ")
                                id == null -> ""
                                details == null -> "Checking where it's streaming…"
                                else -> "Not on your services right now" + (providers?.let { p ->
                                    val rb = (p.rent + p.buy).distinct().take(3)
                                    if (rb.isNotEmpty()) " — rent or buy on ${rb.joinToString(", ")}" else ""
                                } ?: "")
                            },
                            style = Type.small,
                        )
                    }
                    if (showQr) {
                        Column(Modifier.padding(start = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            QrCode(Letterboxd.filmUrl(f), 220.dp)
                            Gap(8.dp)
                            Text("Scan to open on Letterboxd", style = Type.small)
                        }
                    }
                }
            }
            item {
                val plans = c.store.settings.value.plans
                val lines = buildList {
                    if (plexCopy != null) {
                        val q = plexQuality
                        add(Triple("Plex", q?.label?.ifBlank { null } ?: "Checking your file…", "your file" + (q?.let { tvNote(it, tv) } ?: "")))
                    }
                    subs.forEach { s ->
                        val plan = planFor(s.id, plans[s.id])
                        if (plan != null) {
                            val q = plan.quality.cappedTo(tv)
                            add(Triple(s.label, "up to ${q.label}", "${plan.label} plan" + tvNote(plan.quality, tv)))
                        }
                    }
                    if (owned && subs.none { it.id == "apple" }) {
                        val q = Quality("4K", listOf("Dolby Vision", "HDR10"), listOf("Dolby Atmos")).cappedTo(tv)
                        add(Triple("Apple TV purchase", "up to ${q.label}", "depends on the version you bought"))
                    }
                }
                if (lines.isNotEmpty()) {
                    Column(Modifier.padding(start = SafeX, top = 4.dp)) {
                        Text("Picture & sound", style = Type.small.copy(color = Fg))
                        Gap(6.dp)
                        lines.forEach { (source, quality, note) ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text(source, style = Type.body.copy(color = White), modifier = Modifier.width(170.dp))
                                Text(quality, style = Type.body, modifier = Modifier.width(420.dp))
                                Text(note, style = Type.small)
                            }
                        }
                    }
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = SafeX, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    var firstSet = false
                    fun firstMod(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier

                    if (plexCopy != null) {
                        val resume = c.plex.resumePosition(plexCopy) > 60_000
                        val m = firstMod()
                        item { Action(if (resume) "Resume on Plex" else "Play from Plex", { play(f) }, m, primary = true) }
                    }
                    subs.forEach { s ->
                        val m = firstMod()
                        item {
                            Action("Watch on ${s.label}", { vm.watchOn(s, f, details?.runtime) }, m, primary = plexCopy == null)
                        }
                    }
                    if (owned && apple != null && subs.none { it.id == "apple" }) {
                        val m = firstMod()
                        item { Action("Watch on Apple TV (owned)", { vm.watchOn(apple, f, details?.runtime) }, m) }
                    }
                    val logMod = firstMod()
                    item { Action(if (marks.seen(f)) "Log again" else "Log & rate", { vm.askRating(f, "manual") }, logMod) }
                    item {
                        Action(if (owned) "Owned on Apple TV ✓" else "I own this on Apple TV", {
                            vm.toggleApple(f); owned = !owned
                        })
                    }
                    item { Action(if (showQr) "Hide Letterboxd code" else "Letterboxd", { showQr = !showQr }) }
                }
                LaunchedEffect(film) { runCatching { first.requestFocus() } }
            }
            if (related.isNotEmpty()) {
                item {
                    Gap(12.dp)
                    FilmRow("More like this", related, onOpen = open)
                }
            }
        }
        }
    }
}

/** Explains when the TV can't show everything a source offers. */
private fun tvNote(q: Quality, tv: Quality): String {
    val missing = buildList {
        if (q.is4k && !tv.is4k) add("4K")
        addAll(q.hdr.filter { it !in tv.hdr })
    }
    return if (missing.isEmpty()) "" else " · your TV can't show ${missing.joinToString(", ")}"
}
