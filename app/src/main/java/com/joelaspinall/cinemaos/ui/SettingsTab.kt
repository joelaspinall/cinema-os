package com.joelaspinall.cinemaos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.joelaspinall.cinemaos.data.Plex
import com.joelaspinall.cinemaos.data.TvDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 48.dp, vertical = 8.dp).fillMaxWidth()
            .background(Panel, RoundedCornerShape(14.dp)).padding(22.dp),
    ) {
        Text(title, style = Type.h2)
        Gap(10.dp)
        content()
    }
}

@Composable
fun SettingsTab(vm: AppViewModel) {
    val c = vm.c
    val settings by c.store.settings.collectAsState()
    val data by c.store.data.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = remember { TvDisplay.capabilities(context) }

    var pin by remember { mutableStateOf<Plex.Pin?>(null) }
    var plexMsg by remember { mutableStateOf<String?>(null) }
    var updateMsg by remember { mutableStateOf<String?>(null) }

    // While a Plex code is on screen, check every 2 seconds whether Joel has entered it.
    LaunchedEffect(pin) {
        val p = pin ?: return@LaunchedEffect
        repeat(300) {
            delay(2000)
            if (runCatching { c.plex.checkPin(p) }.getOrDefault(false)) {
                pin = null
                plexMsg = if (c.plex.hasServer) "Connected to ${c.store.settings.value.plexServerName}."
                else "Signed in, but your server didn't answer. Is the MacBook awake with Plex running?"
                vm.refresh()
                return@LaunchedEffect
            }
        }
        pin = null
        plexMsg = "The code expired — press Sign in to get a new one."
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Section("Phone companion") {
                val url = c.companion.address()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (url != null) QrCode(url, 210.dp)
                    Column(Modifier.padding(start = 28.dp).width(620.dp)) {
                        Text("Scan with your phone (same Wi-Fi) to:", style = Type.body)
                        Gap(6.dp)
                        listOf(
                            "Paste your TMDB key",
                            "Upload your Letterboxd export (for history, ratings and watchlist)",
                            "Download new diary entries for letterboxd.com/import",
                            "Choose your services and plans",
                        ).forEach { Text("•  $it", style = Type.body.copy(color = Dim)) }
                        Gap(10.dp)
                        Text(url ?: "Not connected to a network.", style = Type.small)
                        val waiting = data.diary.count { !it.exported }
                        if (waiting > 0) {
                            Gap(8.dp)
                            Text("$waiting new ${if (waiting == 1) "entry" else "entries"} waiting to send to Letterboxd", style = Type.body.copy(color = Gold))
                        }
                    }
                }
            }
        }
        item {
            Section("Plex") {
                val p = pin
                when {
                    p != null -> {
                        Text("On your phone or MacBook, go to", style = Type.body)
                        Text("plex.tv/link", style = Type.h1.copy(color = White))
                        Text("and enter", style = Type.body)
                        Text(p.code, style = Type.hero.copy(fontSize = 56.sp, color = White))
                        Gap(8.dp)
                        Action("Cancel", { pin = null })
                    }
                    c.plex.isSignedIn -> {
                        Text(
                            if (c.plex.hasServer) "Connected to ${settings.plexServerName} · ${c.plex.library.size} films"
                            else "Signed in · server not found",
                            style = Type.body,
                        )
                        Gap(12.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Action("Reconnect", {
                                scope.launch {
                                    plexMsg = if (runCatching { c.plex.findServer() }.getOrDefault(false)) "Found ${c.store.settings.value.plexServerName}." else "Couldn't reach your server. Is the MacBook awake?"
                                    vm.refresh()
                                }
                            })
                            Action("Sign out", { c.plex.signOut(); plexMsg = "Signed out of Plex."; vm.refresh() })
                        }
                    }
                    else -> {
                        Text("Sign in once with a short code — no password typing.", style = Type.body.copy(color = Dim))
                        Gap(12.dp)
                        Action("Sign in to Plex", {
                            scope.launch {
                                runCatching { c.plex.createPin() }
                                    .onSuccess { pin = it; plexMsg = null }
                                    .onFailure { plexMsg = "Couldn't reach plex.tv: ${it.message}" }
                            }
                        }, primary = true)
                    }
                }
                plexMsg?.let { Gap(10.dp); Text(it, style = Type.body.copy(color = Gold)) }
            }
        }
        item {
            Section("Letterboxd") {
                Text("Username: ${settings.letterboxdUser.ifBlank { "not set" }}", style = Type.body)
                Text(
                    if (data.history.isNotEmpty()) "${data.history.size} films imported from your export (${data.historyImportedAt}) · ${data.watchlist.size} on watchlist"
                    else "Full history not imported yet — upload your export from the phone page for better recommendations.",
                    style = Type.body.copy(color = Dim),
                )
                Text("${data.rss.size} recent diary entries synced · ${data.diary.size} logged in Cinema OS", style = Type.body.copy(color = Dim))
            }
        }
        item {
            Section("Your TV") {
                Text(tv.label.ifBlank { "Unknown" }, style = Type.body)
                Text("Quality shown for each film is capped to what this screen can display.", style = Type.small)
            }
        }
        item {
            Section("App") {
                Text("Cinema OS ${c.launcher.installedVersion()}", style = Type.body)
                Text("TMDB: " + if (settings.tmdbToken.isNotBlank()) "key saved ✓" else "no key yet", style = Type.body.copy(color = Dim))
                Gap(12.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action("Refresh everything", { vm.refresh(); vm.toast = "Refreshing…" })
                    Action("Update app", {
                        scope.launch {
                            runCatching { c.updater.downloadAndInstall { updateMsg = it } }
                                .onFailure { updateMsg = it.message }
                        }
                    })
                }
                updateMsg?.let { Gap(10.dp); Text(it, style = Type.body.copy(color = Gold)) }
            }
        }
    }
}
