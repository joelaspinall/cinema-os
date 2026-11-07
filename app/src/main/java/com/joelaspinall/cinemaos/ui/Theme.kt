package com.joelaspinall.cinemaos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme

// Apple TV-style palette: near-black, soft greys, white for focus.
val Bg = Color(0xFF0A0A0C)
val Panel = Color(0xFF1C1C1F)
val PanelHi = Color(0xFF2C2C30)
val Glass = Color(0x99303036)          // translucent grey for buttons and the menu
val White = Color(0xFFF5F5F7)
val Fg = Color(0xFFF5F5F7)
val Dim = Color(0xFF8E8E93)
val Gold = Color(0xFFFFD60A)           // stars and ratings only
val Green = Color(0xFF30D158)

object Type {
    val hero = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Fg, letterSpacing = (-0.5).sp)
    val h1 = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Fg)
    val h2 = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Fg)
    val body = TextStyle(fontSize = 16.sp, color = Fg, lineHeight = 23.sp)
    val small = TextStyle(fontSize = 13.sp, color = Dim)
    /** No colour, so it follows the button's focused/unfocused text colour. */
    val label = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun CinemaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = White,
            onPrimary = Bg,
            background = Bg,
            onBackground = Fg,
            surface = Panel,
            onSurface = Fg,
        ),
        content = content,
    )
}

/**
 * Anything you can select with the remote. Like Apple TV: it grows and lifts
 * with a soft glow when focused, rather than getting an outline.
 */
@Composable
fun Tile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.08f,
    container: Color = Panel,
    focusedContainer: Color = PanelHi,
    content: Color = Fg,
    focusedContent: Color = Fg,
    outline: Boolean = false,
    body: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = focusedContainer,
            focusedContentColor = focusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = if (outline) ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, White.copy(alpha = 0.9f)), shape = shape),
        ) else ClickableSurfaceDefaults.border(),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.6f), elevation = 18.dp),
        ),
        content = body,
    )
}
