package com.joelaspinall.cinemaos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/** Star rating with the remote: left/right moves half a star at a time. */
@Composable
fun RateDialog(vm: AppViewModel, req: RateRequest) {
    var rating by remember(req) { mutableDoubleStateOf(3.5) }
    val seenBefore = LocalMarks.current.seen(req.film)
    var rewatch by remember(req) { mutableStateOf(seenBefore) }
    var starsFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    DialogFrame(req.film.poster) {
        Text(req.heading, style = Type.h1)
        Gap(6.dp)
        Text(listOfNotNull(req.film.year?.toString(), "Watched " + (req.watchedDate?.takeIf { it != java.time.LocalDate.now().toString() }?.let { java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) } ?: "today")).joinToString("  ·  "), style = Type.body.copy(color = Dim))
        Gap(26.dp)

        Box(
            Modifier
                .focusRequester(focus)
                .onFocusChanged { starsFocused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { rating = (rating - 0.5).coerceAtLeast(0.5); true }
                        Key.DirectionRight -> { rating = (rating + 0.5).coerceAtMost(5.0); true }
                        Key.DirectionCenter, Key.Enter -> { vm.saveRating(req, rating, rewatch); true }
                        else -> false
                    }
                }
                .focusable()
                .clip(RoundedCornerShape(14.dp))
                .background(if (starsFocused) PanelHi else Panel)
                .border(2.dp, if (starsFocused) Gold else Panel, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val full = rating.toInt()
                val half = rating % 1.0 != 0.0
                Text(
                    "★".repeat(full) + (if (half) "½" else ""),
                    style = Type.hero.copy(fontSize = 48.sp, color = Gold),
                )
                Text(
                    "☆".repeat(5 - full - (if (half) 1 else 0)),
                    style = Type.hero.copy(fontSize = 48.sp, color = Dim),
                )
                Text("  %.1f".format(rating), style = Type.h1.copy(color = Gold))
            }
        }
        Gap(8.dp)
        Text("◀ ▶ to change  ·  OK to save", style = Type.small)
        Gap(24.dp)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("Save ${stars(rating)}", { vm.saveRating(req, rating, rewatch) }, primary = true)
            Action(if (rewatch) "Rewatch ✓" else "Rewatch", { rewatch = !rewatch })
            Action("Log without rating", { vm.saveRating(req, null, rewatch) })
            Action("Cancel", { vm.rate = null })
        }
    }
    LaunchedEffect(req) { runCatching { focus.requestFocus() } }
}

/** "Did you finish it?" — shown when Joel comes back from Netflix and co. */
@Composable
fun FinishDialog(vm: AppViewModel, prompt: FinishPrompt) {
    val film = prompt.pending.film
    val first = remember { FocusRequester() }
    DialogFrame(film.poster) {
        Text(if (prompt.likelyFinished) "Welcome back" else "Back already?", style = Type.small.copy(fontSize = 16.sp))
        Gap(6.dp)
        Text(
            if (prompt.likelyFinished) "Did you finish ${film.title}?" else "Did you watch ${film.title}?",
            style = Type.h1,
        )
        Gap(28.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("Yes — rate it", { vm.answerFinish(finished = true) }, Modifier.focusRequester(first), primary = true)
            Action("Still watching", { vm.answerFinish(finished = false, keepAsking = true) })
            Action("Didn't watch it", { vm.answerFinish(finished = false) })
        }
    }
    LaunchedEffect(prompt) { runCatching { first.requestFocus() } }
}

@Composable
private fun DialogFrame(poster: String?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (poster != null) {
                AsyncImage(
                    model = poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
                )
                Box(Modifier.width(48.dp))
            }
            Column(Modifier.width(760.dp)) { content() }
        }
    }
}
