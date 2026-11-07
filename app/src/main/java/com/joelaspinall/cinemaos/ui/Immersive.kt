package com.joelaspinall.cinemaos.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.joelaspinall.cinemaos.data.Art
import com.joelaspinall.cinemaos.data.Film
import com.joelaspinall.cinemaos.data.FilmDetails
import kotlinx.coroutines.delay

/** Height of the film panel at the top of Home and Plex. */
private val PanelHeight = 222.dp

/**
 * Apple TV-style page: the focused film fills the screen behind, its title artwork and
 * details sit in a fixed panel at the top, and the rows glide underneath it.
 */
@Composable
fun Immersive(
    vm: AppViewModel,
    rows: List<Pair<String, List<Film>>>,
    open: (Film) -> Unit,
    kicker: String,
    emptyTitle: String,
    emptyLine: String,
    top: (@Composable () -> Unit)? = null,
) {
    val featured = rows.firstOrNull()?.second?.firstOrNull { it.backdrop != null }
    var focused by remember { mutableStateOf<Film?>(null) }
    var focusedRow by remember { mutableStateOf<String?>(null) }
    val film = focused ?: featured
    val context = LocalContext.current

    // Preload title artwork for the first cards of every row.
    LaunchedEffect(rows.map { it.first to it.second.size }) {
        if (vm.c.tmdb.isConfigured) {
            val ids = rows.flatMap { (_, films) -> films.take(12).mapNotNull { it.tmdbId } }
            vm.c.tmdb.prefetchLogos(ids)
            // Download the artwork images too, so they appear instantly.
            ids.mapNotNull { vm.c.tmdb.knownLogo(it)?.takeIf { u -> u.isNotEmpty() } }.forEach { url ->
                context.imageLoader.enqueue(ImageRequest.Builder(context).data(Art.logo(url)).build())
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(film)
        Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
            Box(Modifier.fillMaxWidth().height(PanelHeight).padding(start = SafeX, bottom = 8.dp), contentAlignment = Alignment.BottomStart) {
                if (film != null) {
                    FilmPanel(vm, film, if (focused == null) kicker else focusedRow ?: kicker)
                } else {
                    Column {
                        Text(emptyTitle, style = Type.display)
                        Gap(8.dp)
                        Text(emptyLine, style = Type.body.copy(color = Dim))
                    }
                }
            }
            Glide(0f) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp)) {
                    if (top != null) item { top() }
                    items(rows, key = { it.first }) { (title, films) ->
                        FilmRow(title, films, onOpen = open, onFocus = { focused = it; focusedRow = title }, cardWidth = 250.dp)
                    }
                }
            }
        }
    }
}

/** Title artwork (or the title in large type), a details line and the synopsis; crossfades between films. */
@Composable
fun FilmPanel(vm: AppViewModel, film: Film, kicker: String) {
    AnimatedContent(
        targetState = film,
        contentKey = { it.key },
        transitionSpec = {
            (fadeIn(tween(450, delayMillis = 120, easing = Ease)) + slideInVertically(tween(450, easing = Ease)) { it / 12 }) togetherWith
                fadeOut(tween(200))
        },
        label = "panel",
    ) { f ->
        // Wait a moment before loading details, so flicking along a row doesn't fire a request per card.
        val details by produceState<FilmDetails?>(null, f.tmdbId) {
            val id = f.tmdbId ?: return@produceState
            delay(250)
            value = runCatching { vm.c.tmdb.details(id) }.getOrNull()
        }
        // Title artwork: shown at once if it was preloaded; otherwise the title area stays empty
        // for a moment rather than flashing plain text and then swapping to the artwork.
        val known = remember(f.tmdbId) { f.tmdbId?.let { vm.c.tmdb.knownLogo(it) } }
        val logoState by produceState<String?>(initialValue = known ?: if (f.tmdbId == null || !vm.c.tmdb.isConfigured) "" else null, f.tmdbId) {
            if (value != null) return@produceState
            val id = f.tmdbId ?: return@produceState
            delay(120)
            value = runCatching { vm.c.tmdb.logo(id) }.getOrNull() ?: ""
        }
        val scores by produceState<com.joelaspinall.cinemaos.data.Omdb.Scores?>(null, details?.imdbId) {
            value = details?.imdbId?.let { runCatching { vm.c.omdb.scores(it) }.getOrNull() }
        }
        val marks = LocalMarks.current
        Column(Modifier.width(760.dp)) {
            Text(kicker.uppercase(), style = Type.kicker)
            Gap(8.dp)
            Box(Modifier.height(88.dp), contentAlignment = Alignment.BottomStart) {
                val logo = logoState
                when {
                    logo == null -> {} // still looking: keep the space, show nothing
                    logo.isNotEmpty() -> AsyncImage(
                        model = Art.logo(logo),
                        contentDescription = f.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier.heightIn(max = 88.dp).widthIn(max = 400.dp),
                    )
                    else -> Text(f.title, style = Type.hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Gap(10.dp)
            val meta = listOfNotNull(
                f.year?.toString(),
                details?.runtime?.let { "${it / 60}h ${it % 60}m" },
                details?.genres?.take(2)?.joinToString(" · ")?.takeIf { it.isNotBlank() },
                scores?.imdb?.let { "IMDb $it" },
                scores?.rottenTomatoes?.let { "RT $it" },
                marks.rating(f)?.let { "Your rating ${stars(it)}" },
            ).joinToString("   ")
            if (meta.isNotBlank()) Text(meta, style = Type.small.copy(color = Fg.copy(alpha = 0.85f)))
            Gap(10.dp)
            Text(
                f.overview ?: details?.film?.overview ?: "",
                style = Type.body.copy(color = Fg.copy(alpha = 0.85f)),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(600.dp),
            )
        }
    }
}

