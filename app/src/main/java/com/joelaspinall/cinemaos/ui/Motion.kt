package com.joelaspinall.cinemaos.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.joelaspinall.cinemaos.data.Art
import com.joelaspinall.cinemaos.data.Film

/** Side margin that keeps everything inside the TV's safe area (16:9, 5% overscan). */
val SafeX = 64.dp

/** Apple-style ease: quick start, long gentle finish. */
val Ease = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

/** Where the focused item sits while scrolling: rows keep it at the left margin, columns near the top. */
const val PivotRow = 0.066f
const val PivotColumn = 0.22f

/**
 * Makes a row or column glide so the focused card stays in one place on screen,
 * as on Apple TV, instead of jumping only when focus reaches the edge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Glide(pivot: Float, content: @Composable () -> Unit) {
    val spec = remember(pivot) { object : BringIntoViewSpec {
        @Suppress("OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec = tween<Float>(durationMillis = 420, easing = Ease)
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            // Hold the focused item at the pivot line; the list itself stops at its ends.
            return offset - containerSize * pivot
        }
    } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) { content() }
}

/**
 * Full-screen backdrop behind Home, Plex and film pages. Crossfades between films and
 * drifts very slowly, like the Apple TV app, then fades into the black page.
 */
@Composable
fun Backdrop(film: Film?, dim: Float = 0f) {
    val drift = rememberInfiniteTransition(label = "drift")
    val scale by drift.animateFloat(
        initialValue = 1.0f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale",
    )
    Box(Modifier.fillMaxSize().background(Bg)) {
        AnimatedContent(
            targetState = Art.backdrop(film?.backdrop),
            transitionSpec = { fadeIn(tween(700, easing = Ease)) togetherWith fadeOut(tween(700, easing = Ease)) },
            label = "backdrop",
        ) { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale },
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        // Fade to black at the bottom (rows) and the left (title), so text is always readable.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x59000000), 0.35f to Color(0x1A000000), 0.62f to Bg.copy(alpha = 0.75f), 0.85f to Bg,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xD9000000), 0.45f to Color(0x40000000), 0.8f to Color.Transparent),
            ),
        )
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}
