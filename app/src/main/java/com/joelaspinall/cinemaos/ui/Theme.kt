package com.joelaspinall.cinemaos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.ProvideTextStyle
import com.joelaspinall.cinemaos.R
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

/** Inter: a clean, neutral typeface in the spirit of Apple's San Francisco. Bundled, so it works offline. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

/** One type scale for the whole app, sized for reading from the sofa. */
object Type {
    /** Big titles over backdrops. */
    val display = TextStyle(fontFamily = Inter, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, color = Fg, letterSpacing = (-1.2).sp, lineHeight = 56.sp)
    val hero = TextStyle(fontFamily = Inter, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Fg, letterSpacing = (-0.8).sp, lineHeight = 46.sp)
    val h1 = TextStyle(fontFamily = Inter, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Fg, letterSpacing = (-0.4).sp)
    /** Row titles. */
    val h2 = TextStyle(fontFamily = Inter, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Fg, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = Fg, lineHeight = 24.sp)
    val small = TextStyle(fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Dim)
    /** Small capitals above a title, e.g. "RECOMMENDED FOR YOU". */
    val kicker = TextStyle(fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Fg.copy(alpha = 0.7f), letterSpacing = 1.6.sp)
    /** No colour, so it follows the button's focused/unfocused text colour. */
    val label = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
    ) {
        ProvideTextStyle(TextStyle(fontFamily = Inter, color = Fg)) { content() }
    }
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
