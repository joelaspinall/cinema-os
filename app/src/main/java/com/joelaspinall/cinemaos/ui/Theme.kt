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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme

val Bg = Color(0xFF0E0E14)
val Panel = Color(0xFF1A1A24)
val PanelHi = Color(0xFF2A2A3A)
val Gold = Color(0xFFE8AA42)
val Fg = Color(0xFFF2EDE4)
val Dim = Color(0xFF9A958C)
val Green = Color(0xFF6CC28A)

object Type {
    val hero = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Fg)
    val h1 = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Fg)
    val h2 = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Fg)
    val body = TextStyle(fontSize = 15.sp, color = Fg, lineHeight = 22.sp)
    val small = TextStyle(fontSize = 12.sp, color = Dim)
    val label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun CinemaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold,
            onPrimary = Bg,
            background = Bg,
            onBackground = Fg,
            surface = Panel,
            onSurface = Fg,
        ),
        content = content,
    )
}

/** Anything you can select with the remote: lifts and gets a gold edge when focused. */
@Composable
fun Tile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    focusedScale: Float = 1.05f,
    container: Color = Panel,
    focusedContainer: Color = PanelHi,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = Fg,
            focusedContainerColor = focusedContainer,
            focusedContentColor = Fg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Gold), shape = shape),
        ),
        content = content,
    )
}
