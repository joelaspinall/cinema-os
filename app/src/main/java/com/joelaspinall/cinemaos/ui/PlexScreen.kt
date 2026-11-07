package com.joelaspinall.cinemaos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.joelaspinall.cinemaos.data.Film

/** Joel's Plex library on its own page: overview rows, or the full A–Z grid. */
@Composable
fun PlexTab(vm: AppViewModel, open: (Film) -> Unit, goSettings: () -> Unit) {
    val plex = vm.c.plex
    if (!plex.isSignedIn) {
        Column(Modifier.fillMaxSize().padding(start = 64.dp, top = 140.dp)) {
            Text("Plex isn't connected yet", style = Type.hero)
            Gap(10.dp)
            Text("Sign in once with a short code at plex.tv/link.", style = Type.body.copy(color = Dim))
            Gap(24.dp)
            Action("Sign in to Plex", goSettings, primary = true)
        }
        return
    }

    var mode by remember { mutableStateOf("overview") } // overview | all | unseen
    var focused by remember { mutableStateOf<Film?>(null) }
    val marks = LocalMarks.current
    val all = plex.library

    if (mode != "overview") {
        Column(Modifier.fillMaxSize().padding(top = 96.dp)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 64.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Chip("‹ Overview", false) { mode = "overview" } }
                item { Chip("All ${all.size}", mode == "all") { mode = "all" } }
                item { Chip("Not seen", mode == "unseen") { mode = "unseen" } }
            }
            val films = if (mode == "unseen") all.filter { !marks.seen(it) } else all
            PosterGrid(films, open, badges = { plex.resolution(it)?.takeIf { r -> r == "4K" } })
        }
        return
    }

    val deck = vm.plexDeck
    val recent = vm.plexRecent
    val uhd = remember(all) { all.filter { plex.resolution(it) == "4K" }.shuffled().take(30) }
    val unseen = remember(all, marks) { all.filter { !marks.seen(it) }.shuffled().take(30) }
    val featured = (deck + recent).firstOrNull { it.backdrop != null }

    Box(Modifier.fillMaxSize()) {
        Backdrop(focused ?: featured)
        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
            item {
                if (featured != null) {
                    Hero(
                        featured,
                        kicker = if (deck.isNotEmpty()) "Continue watching on Plex" else "New on Plex",
                        onDetails = { open(featured) },
                        onFocus = { focused = featured },
                    ) {
                        Action("All ${all.size} films", { mode = "all" })
                        Action("Refresh", { vm.refresh() })
                    }
                } else {
                    Column(Modifier.height(320.dp).padding(start = 64.dp), verticalArrangement = Arrangement.Bottom) {
                        Text(vm.c.store.settings.value.plexServerName.ifBlank { "Plex" }, style = Type.hero)
                        Text(
                            when {
                                vm.plexStatus != null -> vm.plexStatus!!
                                vm.loading -> "Loading your library…"
                                else -> "${all.size} films"
                            },
                            style = Type.body.copy(color = Dim),
                        )
                        Gap(18.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Action("Refresh", { vm.refresh() }, primary = true)
                            if (all.isNotEmpty()) Action("All ${all.size} films", { mode = "all" })
                        }
                    }
                }
            }
            val rows = listOf(
                "Continue watching" to deck,
                "Recently added" to recent,
                "In 4K" to uhd,
                "Not seen yet" to unseen,
            ).filter { it.second.isNotEmpty() }
            items(rows, key = { it.first }) { (title, films) ->
                FilmRow(title, films, onOpen = open, onFocus = { focused = it })
            }
        }
    }
}
