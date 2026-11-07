package com.joelaspinall.cinemaos.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.joelaspinall.cinemaos.data.Film
import com.joelaspinall.cinemaos.data.StoreData
import com.joelaspinall.cinemaos.data.filmKey

/** What Joel has seen and how he rated it, worked out once for every poster on screen. */
class Marks(data: StoreData) {
    private val ratingsById = HashMap<Int, Double?>()
    private val ratingsByKey = HashMap<String, Double?>()

    init {
        // Oldest sources first so newer ratings win.
        data.history.forEach { put(it.tmdbId ?: data.resolved[filmKey(it.title, it.year)], filmKey(it.title, it.year), it.rating) }
        data.rss.asReversed().forEach { put(it.tmdbId, filmKey(it.title, it.year), it.rating) }
        data.diary.asReversed().forEach { put(it.tmdbId, filmKey(it.title, it.year), it.rating) }
    }

    private fun put(id: Int?, key: String, rating: Double?) {
        if (id != null) ratingsById[id] = rating ?: ratingsById[id]
        ratingsByKey[key] = rating ?: ratingsByKey[key]
    }

    fun seen(f: Film) = (f.tmdbId != null && ratingsById.containsKey(f.tmdbId)) || ratingsByKey.containsKey(f.key)
    fun rating(f: Film): Double? = f.tmdbId?.let { ratingsById[it] } ?: ratingsByKey[f.key]
}

val LocalMarks = compositionLocalOf { Marks(StoreData()) }

fun stars(r: Double?): String =
    r?.let { "★".repeat(it.toInt()) + if (it % 1.0 != 0.0) "½" else "" } ?: ""

@Composable
fun PosterCard(film: Film, onClick: () -> Unit, width: Dp = 140.dp, badge: String? = null) {
    val marks = LocalMarks.current
    Column(Modifier.width(width)) {
        Tile(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f), focusedScale = 1.08f) {
            if (film.poster != null) {
                AsyncImage(
                    model = film.poster,
                    contentDescription = film.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    film.label, style = Type.label, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
            }
            val seen = marks.seen(film)
            if (seen || badge != null) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color(0xCC0E0E14))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    val r = marks.rating(film)
                    val text = badge ?: if (r != null) stars(r) else "✓"
                    Text(text, style = Type.small.copy(color = if (badge != null) Fg else Gold))
                }
            }
        }
        Text(
            film.title, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
    }
}

@Composable
fun FilmRow(title: String, films: List<Film>, onOpen: (Film) -> Unit, badges: (Film) -> String? = { null }) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(title, style = Type.h2, modifier = Modifier.padding(start = 48.dp, bottom = 10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(films, key = { (it.tmdbId?.toString() ?: "") + (it.plexKey ?: "") + it.key }) { f ->
                PosterCard(f, onClick = { onOpen(f) }, badge = badges(f))
            }
        }
    }
}

/** A text button for actions ("Watch on Netflix", "Save", …). */
@Composable
fun Action(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Tile(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        container = if (primary) Gold else Panel,
        focusedContainer = if (primary) Color(0xFFF4C26A) else PanelHi,
    ) {
        Text(
            text,
            style = Type.label.copy(color = if (primary) Bg else Fg),
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
        )
    }
}

@Composable
fun QrCode(text: String, size: Dp = 200.dp) {
    val bitmap = remember(text) {
        val px = 400
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565)
        for (x in 0 until px) for (y in 0 until px) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        bmp.asImageBitmap()
    }
    Image(bitmap, contentDescription = "QR code", modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)))
}

@Composable
fun Centered(text: String, sub: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = Type.h2)
            if (sub != null) Text(sub, style = Type.body.copy(color = Dim), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun Gap(h: Dp) = androidx.compose.foundation.layout.Spacer(Modifier.height(h))
