package com.joelaspinall.cinemaos.ui

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.joelaspinall.cinemaos.data.Film
import kotlinx.coroutines.launch

/**
 * Plays a film straight from the Plex server on the MacBook. When it ends (or Joel
 * stops in the last 10%), it's marked watched in Plex and he's asked for a rating.
 */
@Composable
fun PlayerScreen(vm: AppViewModel, film: Film, onExit: () -> Unit) {
    val context = LocalContext.current
    val plex = vm.c.plex
    val scope = rememberCoroutineScope()
    val copy = remember(film) { plex.find(film) ?: film }
    val url = remember(copy) { plex.streamUrl(copy) }
    var finished by remember { mutableStateOf(false) }
    val startedAt = remember { System.currentTimeMillis() }

    if (url == null) {
        LaunchedEffect(Unit) { vm.toast = "Couldn't find the file on Plex — try Refresh on the Plex tab."; onExit() }
        return
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            val resume = plex.resumePosition(copy)
            if (resume > 60_000) seekTo(resume)
            prepare()
            playWhenReady = true
        }
    }

    fun leave() {
        if (finished) return
        finished = true
        val pos = player.currentPosition
        val dur = player.duration
        val nearEnd = dur > 0 && pos >= dur * 0.9
        scope.launch { plex.saveProgress(copy, if (nearEnd) 0 else pos, dur.coerceAtLeast(0)) }
        if (nearEnd) vm.plexFinished(copy, startedAt)
        onExit()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) leave()
            }

            override fun onPlayerError(error: PlaybackException) {
                vm.toast = "This file can't play here — opening it in the Plex app instead."
                val pm = context.packageManager
                (pm.getLeanbackLaunchIntentForPackage("com.plexapp.android")
                    ?: pm.getLaunchIntentForPackage("com.plexapp.android"))
                    ?.let { runCatching { context.startActivity(it) } }
                finished = true
                onExit()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    BackHandler { leave() }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                keepScreenOn = true
                isFocusable = true
                isFocusableInTouchMode = true
                setShowSubtitleButton(true)
                descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                requestFocus()
            }
        },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}
