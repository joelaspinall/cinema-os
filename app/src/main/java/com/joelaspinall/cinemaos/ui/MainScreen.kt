package com.joelaspinall.cinemaos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.joelaspinall.cinemaos.data.Film

data class Tab(val id: String, val label: String)

/** Tabs that draw their own full-screen backdrop under the floating menu. */
private val HERO_TABS = setOf("home", "plex")

@Composable
fun MainScreen(vm: AppViewModel, tab: String, onTab: (String) -> Unit, open: (Film) -> Unit) {
    val tabs = buildList {
        add(Tab("home", "Home"))
        add(Tab("plex", "Plex"))
        vm.myServices.forEach { add(Tab("svc:${it.id}", it.label)) }
        add(Tab("search", "Search"))
        add(Tab("settings", "Settings"))
    }
    val selectedTab = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().padding(top = if (tab in HERO_TABS) 0.dp else 96.dp),
        ) {
            when {
                tab == "home" -> HomeTab(vm, open, goSettings = { onTab("settings") })
                tab == "plex" -> PlexTab(vm, open, goSettings = { onTab("settings") })
                tab.startsWith("svc:") -> BrowseTab(vm, tab.removePrefix("svc:"), open)
                tab == "search" -> SearchTab(vm, open)
                tab == "settings" -> SettingsTab(vm)
            }
        }

        // Floating pill menu, centred at the top like Apple TV.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 26.dp)
                .background(Glass, RoundedCornerShape(30.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { t ->
                val selected = t.id == tab
                Tile(
                    onClick = { onTab(t.id) },
                    modifier = if (selected) Modifier.focusRequester(selectedTab) else Modifier,
                    shape = RoundedCornerShape(24.dp),
                    focusedScale = 1.06f,
                    container = if (selected) Color(0x40FFFFFF) else Color.Transparent,
                    focusedContainer = White,
                    focusedContent = Bg,
                ) {
                    Text(t.label, style = Type.label, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { selectedTab.requestFocus() } }
    }
}

/** The big title area at the top of Home and Plex, over the backdrop. */
@Composable
fun Hero(
    film: Film?,
    kicker: String,
    onDetails: () -> Unit,
    onFocus: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().height(500.dp).padding(start = 64.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (film == null) return@Column
        Text(kicker.uppercase(), style = Type.small.copy(color = White.copy(alpha = 0.75f)))
        Gap(6.dp)
        Text(film.title, style = Type.hero, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(900.dp))
        film.year?.let { Text(it.toString(), style = Type.body.copy(color = Dim)) }
        Gap(10.dp)
        Text(
            film.overview ?: "", style = Type.body, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(680.dp),
        )
        Gap(20.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Action("More info", onDetails, Modifier.onFocusChanged { if (it.isFocused) onFocus() }, primary = true)
            extra()
        }
    }
}

@Composable
fun HomeTab(vm: AppViewModel, open: (Film) -> Unit, goSettings: () -> Unit) {
    val rows = vm.homeRows
    val featured = rows.firstOrNull()?.films?.firstOrNull { it.backdrop != null }
    var focused by remember { mutableStateOf<Film?>(null) }
    val needsSetup = !vm.c.tmdb.isConfigured || !vm.c.plex.isSignedIn

    Box(Modifier.fillMaxSize()) {
        Backdrop(focused ?: featured)
        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
            item {
                if (featured != null) {
                    Hero(featured, rows.first().title, onDetails = { open(featured) }, onFocus = { focused = featured })
                } else {
                    Column(Modifier.height(260.dp).padding(start = 64.dp), verticalArrangement = Arrangement.Bottom) {
                        Text("Cinema OS", style = Type.hero)
                        Text(if (vm.loading) "Loading your films…" else "Finish setup in Settings to fill this page.", style = Type.body.copy(color = Dim))
                    }
                }
            }
            if (needsSetup) {
                item {
                    Row(Modifier.padding(horizontal = 64.dp, vertical = 12.dp)) {
                        Action(
                            buildString {
                                append("Finish setting up: ")
                                append(listOfNotNull(
                                    "add your TMDB key".takeIf { !vm.c.tmdb.isConfigured },
                                    "sign in to Plex".takeIf { !vm.c.plex.isSignedIn },
                                ).joinToString(" and "))
                            },
                            goSettings,
                        )
                    }
                }
            }
            items(rows, key = { it.title }) { row ->
                FilmRow(row.title, row.films, onOpen = open, onFocus = { focused = it })
            }
        }
    }
}
