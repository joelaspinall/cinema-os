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
import coil.compose.AsyncImage
import com.joelaspinall.cinemaos.data.Art
import com.joelaspinall.cinemaos.data.Film
import com.joelaspinall.cinemaos.data.FilmDetails
import kotlinx.coroutines.delay

/** Height of the film panel at the top of Home and Plex. */
private val PanelHeight = 300.dp

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

    Box(Modifier.fillMaxSize()) {
        Backdrop(film)
        Column(Modifier.fillMaxSize().padding(top = HERO_TOP)) {
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
                        FilmRow(title, films, onOpen = open, onFocus = { focused = it; focusedRow = title })
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
        val logo by produceState<String?>(null, f.tmdbId) {
            val id = f.tmdbId ?: return@produceState
            delay(250)
            value = if (vm.c.tmdb.isConfigured) runCatching { vm.c.tmdb.logo(id) }.getOrNull() else null
        }
        val marks = LocalMarks.current
        Column(Modifier.width(760.dp)) {
            Text(kicker.uppercase(), style = Type.kicker)
            Gap(10.dp)
            if (logo != null) {
                AsyncImage(
                    model = Art.logo(logo),
                    contentDescription = f.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.heightIn(max = 120.dp).widthIn(max = 460.dp),
                )
            } else {
                Text(f.title, style = Type.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Gap(14.dp)
            val meta = listOfNotNull(
                f.year?.toString(),
                details?.runtime?.let { "${it / 60}h ${it % 60}m" },
                details?.genres?.take(2)?.joinToString(" · ")?.takeIf { it.isNotBlank() },
                marks.rating(f)?.let { "Your rating ${stars(it)}" },
            ).joinToString("   ")
            if (meta.isNotBlank()) Text(meta, style = Type.small.copy(color = Fg.copy(alpha = 0.85f)))
            Gap(10.dp)
            Text(
                f.overview ?: details?.film?.overview ?: "",
                style = Type.body.copy(color = Fg.copy(alpha = 0.85f)),
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(640.dp),
            )
        }
    }
}

