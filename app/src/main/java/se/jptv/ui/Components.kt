@file:OptIn(ExperimentalTvMaterial3Api::class)

package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage

val PosterWidth: Dp = 150.dp

/** Affischkort som skalar upp och får ram när det har fokus (inbyggt i Compose for TV). */
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    progress: Float,
    watched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp = PosterWidth,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            // Affischen med fokus större, de andra lite mindre, så att det syns tydligt var man är.
            scale = androidx.tv.material3.CardDefaults.scale(scale = 0.86f, focusedScale = 1.06f),
            modifier = Modifier
                .width(width)
                .aspectRatio(2f / 3f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(JptvColors.SurfaceHigh),
            ) {
                Text(
                    text = title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JptvColors.TextDim,
                )
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(JptvColors.Background.copy(alpha = 0.7f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(5.dp)
                                .background(JptvColors.Accent),
                        )
                    }
                }
                if (watched && progress == 0f) {
                    Text(
                        text = "✓",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(JptvColors.Accent, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        color = JptvColors.Background,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = JptvColors.TextDim,
            )
        }
    }
}

/** Centrerat meddelande med valfri knapp, för tomma lägen och fel. */
@Composable
fun MessageBox(
    message: String,
    modifier: Modifier = Modifier,
    buttonText: String? = null,
    onButton: (() -> Unit)? = null,
    buttonModifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(560.dp),
            )
            if (buttonText != null && onButton != null) {
                Button(onClick = onButton, modifier = buttonModifier) { Text(buttonText) }
            }
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Spacer(Modifier.width(16.dp))
            Text(subtitle, style = MaterialTheme.typography.titleMedium, color = JptvColors.TextDim)
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Sätter fokus när elementet faktiskt finns på skärmen. Listor bygger sina rader först vid
 * layout, så ett direkt anrop kan missa; vi försöker därför över några bildrutor.
 */
suspend fun androidx.compose.ui.focus.FocusRequester.requestWhenReady(attempts: Int = 6) {
    repeat(attempts) {
        androidx.compose.runtime.withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess) return
    }
}


/**
 * Släpper igenom OK-knappen först efter att den tryckts ner här. TV-komponenterna klickar när OK
 * släpps, utan att kolla var den trycktes ner – så ett långtryck som öppnar en ny skärm eller meny
 * skulle annars klicka på det som har fokus där.
 */
fun Modifier.ignoreUnpairedOk(): Modifier = composed {
    val armed = androidx.compose.runtime.remember { booleanArrayOf(false) }
    onPreviewKeyEvent { ev ->
        val native = ev.nativeKeyEvent
        val code = native.keyCode
        val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
            code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        when {
            !ok -> false
            native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 0 -> {
                armed[0] = true
                false
            }
            else -> !armed[0]
        }
    }
}

/** Listans scrollposition, sparad i ViewModel när skärmen stängs och återställd när den öppnas igen. */
@Composable
fun se.jptv.AppViewModel.rememberListState(memoryKey: String): androidx.compose.foundation.lazy.LazyListState {
    val state = androidx.compose.runtime.key(memoryKey) {
        val saved = scrollPositions[memoryKey]
        androidx.compose.foundation.lazy.rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    }
    androidx.compose.runtime.DisposableEffect(memoryKey, state) {
        onDispose { scrollPositions[memoryKey] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    }
    return state
}

/** Samma sak för rutnät. */
@Composable
fun se.jptv.AppViewModel.rememberGridState(memoryKey: String): androidx.compose.foundation.lazy.grid.LazyGridState {
    val state = androidx.compose.runtime.key(memoryKey) {
        val saved = scrollPositions[memoryKey]
        androidx.compose.foundation.lazy.grid.rememberLazyGridState(saved?.first ?: 0, saved?.second ?: 0)
    }
    androidx.compose.runtime.DisposableEffect(memoryKey, state) {
        onDispose { scrollPositions[memoryKey] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    }
    return state
}

/** Små märken på rad: "4K", "Dolby Vision", "IMDb 8,6" … */
@Composable
fun Badges(labels: List<String>, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEach { label ->
            val imdb = label.startsWith("IMDb")
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (imdb) androidx.compose.ui.graphics.Color.Black else JptvColors.Text,
                maxLines = 1,
                modifier = Modifier
                    .background(
                        if (imdb) androidx.compose.ui.graphics.Color(0xFFF5C518) else JptvColors.SurfaceHigh,
                        MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Titelns logotyp (Plex clearLogo) om den finns, annars titeln som text.
 * Bilden laddas utan omskalning så att den genomskinliga bakgrunden behålls.
 */
@Composable
fun TitleLogo(
    logoUrl: String?,
    title: String,
    maxHeight: Dp,
    maxWidth: Dp,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    var failed by androidx.compose.runtime.remember(logoUrl) { androidx.compose.runtime.mutableStateOf(false) }
    if (logoUrl == null || failed) {
        Text(title, style = textStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
    } else {
        AsyncImage(
            model = logoUrl,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = modifier
                .height(maxHeight)
                .width(maxWidth),
        )
    }
}

/** Klocka för hörnet, uppdateras var 15:e sekund. */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    Text(clock(now), style = MaterialTheme.typography.titleLarge, color = JptvColors.TextDim, modifier = modifier)
}
