package se.jptv.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import se.jptv.plex.PlexChapter
import se.jptv.plex.PlexMarker
import se.jptv.ui.JptvColors
import se.jptv.ui.clock
import se.jptv.ui.formatTime
import kotlin.math.abs

/** Knapparna i spelarens kontrollrad. */
internal enum class VodButton { PLAY_PAUSE, BACK, FORWARD, SKIP_INTRO, TRACKS, NEXT, RESTART, MORE }

/** Knapparna i live-TV:ns kontrollrad. */
internal enum class LiveButton { CHANNELS, GUIDE, PREVIOUS, PLAY_PAUSE, BACK, FORWARD, GO_LIVE, TRACKS, FAVORITE, MORE }

/** En knapp: ikon eller kort text (t.ex. "−10"), och namnet som visas under när den är vald. */
internal class ControlButton(val id: Enum<*>, val label: String, val icon: ImageVector? = null, val text: String? = null)

/** Markeringar på tidslinjen. */
internal class TimelineMarks(val intro: PlexMarker?, val credits: PlexMarker?, val chapters: List<PlexChapter>) {
    /** Namnet på delen av filmen vid [ms]: intro, eftertexter eller kapitlets namn. */
    fun labelAt(ms: Long): String? = when {
        intro != null && ms >= intro.startMs && ms < intro.endMs -> "Intro"
        credits != null && ms >= credits.startMs && ms < credits.endMs -> "Eftertexter"
        else -> chapterAt(ms)
    }

    fun chapterAt(ms: Long): String? = chapters.lastOrNull { it.startMs <= ms }?.title
}

internal object PlayerIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.White))
            .build()

    val Play by lazy { icon("play", "M8 5v14l11-7z") }
    val Pause by lazy { icon("pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z") }
    val SkipNext by lazy { icon("next", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z") }
    val FastForward by lazy { icon("ff", "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z") }
    val Subtitles by lazy {
        icon("subs", "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM4 12h4v2H4v-2zm10 6H4v-2h10v2zm6 0h-4v-2h4v2zm0-4H10v-2h10v2z")
    }
    val More by lazy {
        icon("more", "M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z")
    }
    val ChannelList by lazy {
        icon("list", "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z")
    }
    val Guide by lazy {
        icon("guide", "M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11zM7 10h5v5H7z")
    }
    val History by lazy {
        icon(
            "history",
            "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
        )
    }
    val Star by lazy { icon("star", "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z") }
    val StarOutline by lazy {
        icon(
            "star_outline",
            "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
        )
    }
    val Restart by lazy {
        icon("restart", "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z")
    }
}

/** Klockan och sluttiden uppe till höger, över en svag skugga. */
@Composable
internal fun PlayerTopBar(now: Long, endsAt: Long?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
    ) {
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            Text(clock(now), style = MaterialTheme.typography.headlineSmall)
            if (endsAt != null) {
                Text("Slutar ${clock(endsAt)}", color = JptvColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Kontrollerna längst ner: titel, tidslinje med kapitel och intro, tider och knapprad.
 * [focused] = vald knapp, null = tidslinjen (◀/▶ spolar).
 */
@Composable
internal fun VodControls(
    title: String,
    subtitle: String?,
    scrubTarget: Long?,
    preview: ImageBitmap?,
    position: Long,
    duration: Long,
    buffered: Long,
    marks: TimelineMarks,
    buttons: List<ControlButton>,
    focused: Enum<*>?,
    status: List<Pair<String, Boolean>>,
    modifier: Modifier = Modifier,
) {
    val shown = scrubTarget ?: position
    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.9f))))
            .padding(start = 48.dp, end = 48.dp, top = 72.dp, bottom = 22.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        if (scrubTarget != null) {
            ScrubBubble(scrubTarget, position, fraction, preview, marks.labelAt(scrubTarget))
            Spacer(Modifier.height(8.dp))
        }
        Timeline(
            position = position,
            scrubTarget = scrubTarget,
            duration = duration,
            buffered = buffered,
            marks = marks,
            focused = focused == null,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(shown), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(20.dp))
            val part = marks.labelAt(shown)
            Text(
                part ?: "",
                color = JptvColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (duration > 0) {
                Text("−${formatTime((duration - shown).coerceAtLeast(0))}", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Top) {
            ControlRow(buttons, focused, Modifier.alpha(if (focused == null) 0.8f else 1f))
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                status.forEach { (text, highlight) ->
                    Text(
                        text,
                        color = if (highlight) JptvColors.Accent else JptvColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** En rad med runda knappar; den valda är vit och har sitt namn under sig. */
@Composable
internal fun ControlRow(buttons: List<ControlButton>, focused: Enum<*>?, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = modifier) {
        buttons.forEach { b -> ControlButtonView(b, b.id == focused) }
    }
}

@Composable
private fun ControlButtonView(button: ControlButton, focused: Boolean) {
    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .graphicsLayer {
                    val s = if (focused) 1.1f else 1f
                    scaleX = s
                    scaleY = s
                }
                .size(52.dp)
                .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (focused) Color.Black else Color.White
            val icon = button.icon
            if (icon != null) {
                Image(icon, contentDescription = button.label, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(28.dp))
            } else {
                Text(button.text ?: "", color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        // Namnet under den valda knappen; får bli bredare än knappen utan att flytta de andra.
        Text(
            if (focused) button.label else "",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
        )
    }
}

@Composable
private fun ScrubBubble(target: Long, position: Long, fraction: Float, preview: ImageBitmap?, label: String?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubble = if (preview != null) 256.dp else 200.dp
        val raw = maxWidth * fraction - bubble / 2
        val x = when {
            raw < 0.dp -> 0.dp
            raw > maxWidth - bubble -> maxWidth - bubble
            else -> raw
        }
        Column(
            Modifier
                .offset(x = x)
                .width(bubble),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(bubble)
                        .height(144.dp)
                        .background(Color.Black, MaterialTheme.shapes.small),
                )
                Spacer(Modifier.height(6.dp))
            }
            if (label != null) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            val delta = target - position
            Text(
                "${formatTime(target)}  (${if (delta >= 0) "+" else "−"}${formatTime(abs(delta))})",
                style = MaterialTheme.typography.titleMedium,
                color = JptvColors.Accent,
            )
        }
    }
}

/**
 * Tidslinjen: buffrat (ljust), sett (accentfärg), kapitelgränser (små glapp), intro och
 * eftertexter (streck under linjen) och markören när tidslinjen är vald.
 */
@Composable
private fun Timeline(
    position: Long,
    scrubTarget: Long?,
    duration: Long,
    buffered: Long,
    marks: TimelineMarks,
    focused: Boolean,
) {
    val accent = JptvColors.Accent
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp),
    ) {
        val w = size.width
        val cy = size.height / 2f
        val h = (if (focused) 8.dp else 5.dp).toPx()
        val top = cy - h / 2f
        val radius = CornerRadius(h / 2f)
        fun x(ms: Long): Float = if (duration > 0) (ms.toFloat() / duration).coerceIn(0f, 1f) * w else 0f

        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, top), Size(w, h), radius)
        if (duration <= 0) return@Canvas
        val bufferedX = x(maxOf(buffered, position))
        if (bufferedX > 0f) drawRoundRect(Color.White.copy(alpha = 0.32f), Offset(0f, top), Size(bufferedX, h), radius)
        val playedX = x(position)
        if (playedX > 0f) drawRoundRect(accent, Offset(0f, top), Size(playedX, h), radius)
        if (scrubTarget != null) {
            val tx = x(scrubTarget)
            val from = minOf(playedX, tx)
            drawRect(accent.copy(alpha = 0.45f), Offset(from, top), Size(abs(tx - playedX), h))
        }
        // Kapitelgränser som små glapp i linjen.
        val gap = 3.dp.toPx()
        marks.chapters.drop(1).forEach { c ->
            val cx = x(c.startMs)
            drawRect(Color.Black, Offset(cx - gap / 2f, top - 1f), Size(gap, h + 2f))
        }
        // Intro och eftertexter som streck under linjen.
        val markTop = top + h + 4.dp.toPx()
        val markH = 3.dp.toPx()
        listOfNotNull(marks.intro, marks.credits).forEach { m ->
            val sx = x(m.startMs)
            val ex = maxOf(x(m.endMs), sx + 3.dp.toPx())
            drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(sx, markTop), Size(ex - sx, markH), CornerRadius(markH / 2f))
        }
        if (focused) {
            val tx = x(scrubTarget ?: position)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(tx, cy))
        }
    }
}
