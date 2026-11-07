package com.joelaspinall.cinemaos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.joelaspinall.cinemaos.data.Film
import com.joelaspinall.cinemaos.data.Genre
import com.joelaspinall.cinemaos.data.serviceById
import kotlinx.coroutines.delay

private val SORTS = listOf(
    "Popular" to "popularity.desc",
    "Top rated" to "vote_average.desc",
    "Newest" to "primary_release_date.desc",
)

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Tile(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        focusedScale = 1.06f,
        container = if (selected) Color(0x40FFFFFF) else Glass,
        focusedContainer = White,
        focusedContent = Bg,
    ) {
        Text(text, style = Type.label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
fun PosterGrid(films: List<Film>, open: (Film) -> Unit, onNearEnd: (() -> Unit)? = null, badges: (Film) -> String? = { null }) {
    val state = rememberLazyGridState()
    if (onNearEnd != null) {
        LaunchedEffect(state, films.size) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { if (films.isNotEmpty() && it >= films.size - 14) onNearEnd() }
        }
    }
    Glide(PivotColumn) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 16.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        items(films, key = { (it.tmdbId?.toString() ?: "") + (it.plexKey ?: "") + it.key }) { f ->
            PosterCard(f, onClick = { open(f) }, width = 140.dp, badge = badges(f))
        }
    }
    }
}

/** The full UK catalogue for one service, loaded 20 films at a time as Joel scrolls. */
@Composable
fun BrowseTab(vm: AppViewModel, serviceId: String, open: (Film) -> Unit) {
    val service = serviceById(serviceId) ?: return
    if (!vm.c.tmdb.isConfigured) {
        Centered("Add your TMDB key first", "Settings → Phone companion, then paste it on your phone."); return
    }
    var genre by remember(serviceId) { mutableStateOf<Genre?>(null) }
    var sort by remember(serviceId) { mutableStateOf(SORTS[0]) }
    var unseenOnly by remember(serviceId) { mutableStateOf(false) }
    val films = remember(serviceId, genre, sort) { mutableStateListOf<Film>() }
    var page by remember(serviceId, genre, sort) { mutableIntStateOf(0) }
    var done by remember(serviceId, genre, sort) { mutableStateOf(false) }
    var busy by remember(serviceId, genre, sort) { mutableStateOf(false) }
    var error by remember(serviceId, genre, sort) { mutableStateOf<String?>(null) }
    var wantMore by remember(serviceId, genre, sort) { mutableIntStateOf(1) }
    val genres by produceState(emptyList<Genre>()) { value = runCatching { vm.c.tmdb.genres() }.getOrDefault(emptyList()) }
    val marks = LocalMarks.current

    LaunchedEffect(serviceId, genre, sort, wantMore) {
        if (busy || done) return@LaunchedEffect
        busy = true
        val next = page + 1
        runCatching { vm.c.tmdb.catalogue(serviceId, next, genre?.id, sort.second) }
            .onSuccess { batch ->
                if (batch.isEmpty() || next >= 50) done = true
                val have = films.mapNotNull { it.tmdbId }.toHashSet()
                films.addAll(batch.filter { it.tmdbId !in have })
                page = next
            }
            .onFailure { error = it.message; done = true }
        busy = false
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(SORTS) { s -> Chip(s.first, s == sort) { sort = s } }
            item { Chip(if (unseenOnly) "Not seen ✓" else "Not seen", unseenOnly) { unseenOnly = !unseenOnly } }
            item { Box(Modifier.width(18.dp)) }
            item { Chip("All genres", genre == null) { genre = null } }
            items(genres) { g -> Chip(g.name, g == genre) { genre = g } }
        }
        val shown = if (unseenOnly) films.filter { !marks.seen(it) } else films
        when {
            error != null && films.isEmpty() -> Centered("Couldn't load ${service.label}", error)
            shown.isEmpty() && busy -> Centered("Loading ${service.label}…")
            shown.isEmpty() -> Centered("Nothing here", "Try another genre.")
            else -> PosterGrid(shown, open, onNearEnd = { if (!busy && !done) wantMore++ })
        }
    }
}

@Composable
fun SearchTab(vm: AppViewModel, open: (Film) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Film>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(450)
        searching = true
        val plexHits = vm.c.plex.library.filter { it.title.contains(q, ignoreCase = true) }
        val tmdbHits = if (vm.c.tmdb.isConfigured) runCatching { vm.c.tmdb.search(q) }.getOrDefault(emptyList()) else emptyList()
        // Plex copies first, then TMDB results that aren't the same film.
        val plexIds = plexHits.mapNotNull { it.tmdbId }.toSet()
        results = plexHits + tmdbHits.filter { it.tmdbId !in plexIds }
        searching = false
    }

    Column(Modifier.fillMaxSize()) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = Type.h2,
            cursorBrush = SolidColor(White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
            modifier = Modifier
                .padding(horizontal = 64.dp, vertical = 8.dp)
                .width(720.dp)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .background(Panel, RoundedCornerShape(12.dp))
                .border(2.dp, if (focused) White else PanelHi, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Search films — press OK to type", style = Type.h2.copy(color = Dim))
                    inner()
                }
            },
        )
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val plexKeys = vm.c.plex.library.map { it.key }.toHashSet()
        when {
            query.trim().length < 2 -> Centered("Search Plex and every service at once")
            results.isEmpty() && searching -> Centered("Searching…")
            results.isEmpty() -> Centered("No films found for “${query.trim()}”")
            else -> PosterGrid(results, open, badges = { if (it.plexKey != null || it.key in plexKeys) "Plex" else null })
        }
    }
}
