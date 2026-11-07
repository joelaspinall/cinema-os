package com.joelaspinall.cinemaos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.joelaspinall.cinemaos.data.Film

data class Tab(val id: String, val label: String)

@Composable
fun MainScreen(vm: AppViewModel, tab: String, onTab: (String) -> Unit, open: (Film) -> Unit) {
    val tabs = buildList {
        add(Tab("home", "Home"))
        vm.myServices.forEach { add(Tab("svc:${it.id}", it.label)) }
        add(Tab("plex", "Plex"))
        add(Tab("search", "Search"))
        add(Tab("settings", "Settings"))
    }
    val first = remember { FocusRequester() }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                Text("Cinema", style = Type.h1, modifier = Modifier.padding(end = 2.dp))
            }
            item {
                Text("OS", style = Type.h1.copy(color = Gold), modifier = Modifier.padding(end = 22.dp))
            }
            items(tabs, key = { it.id }) { t ->
                val selected = t.id == tab
                Tile(
                    onClick = { onTab(t.id) },
                    modifier = if (selected) Modifier.focusRequester(first) else Modifier,
                    shape = RoundedCornerShape(20.dp),
                    container = if (selected) PanelHi else Bg,
                ) {
                    Text(
                        t.label,
                        style = Type.label.copy(color = if (selected) Gold else Fg),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab == "home" -> HomeTab(vm, open, goSettings = { onTab("settings") })
                tab.startsWith("svc:") -> BrowseTab(vm, tab.removePrefix("svc:"), open)
                tab == "plex" -> PlexTab(vm, open, goSettings = { onTab("settings") })
                tab == "search" -> SearchTab(vm, open)
                tab == "settings" -> SettingsTab(vm)
            }
        }
    }
}

@Composable
fun HomeTab(vm: AppViewModel, open: (Film) -> Unit, goSettings: () -> Unit) {
    val rows = vm.homeRows
    val needsSetup = !vm.c.tmdb.isConfigured || !vm.c.plex.isSignedIn
    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
        if (needsSetup) {
            item {
                Tile(onClick = goSettings, modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Finish setting up", style = Type.h2.copy(color = Gold))
                        Text(
                            buildString {
                                if (!vm.c.tmdb.isConfigured) append("Add your TMDB key from your phone. ")
                                if (!vm.c.plex.isSignedIn) append("Sign in to Plex. ")
                                append("Press to open Settings.")
                            },
                            style = Type.body.copy(color = Dim),
                        )
                    }
                }
            }
        }
        vm.plexStatus?.let { msg ->
            item { Text(msg, style = Type.body.copy(color = Gold), modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) }
        }
        items(rows, key = { it.title }) { row ->
            FilmRow(row.title, row.films, onOpen = open)
        }
        if (rows.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (vm.loading) "Loading your films…" else "Nothing to show yet — finish setup in Settings.",
                        style = Type.h2.copy(color = Dim),
                    )
                }
            }
        }
    }
}
