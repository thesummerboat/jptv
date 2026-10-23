package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.plex.PlexItem

/** Detaljsida för film eller avsnitt: Fortsätt / Börja om / markera som sett. */
@Composable
fun DetailsScreen(vm: AppViewModel, initial: PlexItem) {
    // Hämta färsk information (aktuell position, spår) men visa direkt det vi redan har.
    var item by remember { mutableStateOf(initial) }
    var watched by remember { mutableStateOf(initial.watched) }
    val playFocus = remember { FocusRequester() }

    var logo by remember { mutableStateOf(initial.logo) }
    var trailer by remember { mutableStateOf<PlexItem?>(null) }

    // Även när appen kommer tillbaka: positionen kan ha ändrats på en annan TV.
    LaunchedEffect(initial.ratingKey, vm.returnCount) {
        runCatching { vm.plex.metadata(initial.ratingKey) }.getOrNull()?.let {
            item = it
            watched = it.watched
        }
        // Avsnitt har ingen egen logotyp; använd seriens.
        logo = vm.showLogo(item)
        trailer = runCatching { vm.plex.trailers(item) }.getOrNull()?.firstOrNull()
    }

    // Som hemskärmen: fri bild till höger och trailern ljudlöst i bakgrunden efter några sekunder.
    val trailerState = remember { TrailerState() }
    val imageAlpha by animateFloatAsState(
        targetValue = if (trailerState.showing) 0f else 1f,
        animationSpec = tween(if (trailerState.showing) 900 else 300),
        label = "trailer",
    )

    Box(Modifier.fillMaxSize()) {
        Backdrop(
            url = vm.plex.imageUrl(item.art, 1920, 1080),
            imageAlpha = imageAlpha,
            video = { HeroTrailer(vm, item, enabled = true, state = trailerState) },
            fadeFrom = 0.5f,
            leftScrimOnly = true,
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 64.dp, end = 64.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.type == "episode") {
                TitleLogo(
                    logoUrl = vm.plex.rawImageUrl(logo),
                    title = item.showTitle ?: "",
                    maxHeight = 90.dp,
                    maxWidth = 420.dp,
                    textStyle = MaterialTheme.typography.titleLarge,
                )
                Text(
                    buildString {
                        if (item.season != null && item.episode != null) append("Säsong ${item.season} · Avsnitt ${item.episode}  ")
                        append(item.title)
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(shadow = TextShadow),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(620.dp),
                )
            } else {
                TitleLogo(
                    logoUrl = vm.plex.rawImageUrl(logo),
                    title = item.title,
                    maxHeight = 120.dp,
                    maxWidth = 480.dp,
                    textStyle = MaterialTheme.typography.displayMedium,
                )
            }

            Badges(vm.ratingBadges(item) + item.badges)

            val facts = buildList {
                item.year?.let { add(it.toString()) }
                if (item.durationMs > 0) add(formatDuration(item.durationMs))
                item.contentRating?.let { add(it) }
                if (item.genres.isNotEmpty()) add(item.genres.take(3).joinToString(", "))
            }
            Text(
                facts.joinToString("  ·  "),
                color = JptvColors.Text.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyLarge.copy(shadow = TextShadow),
            )

            val subs = item.streams.filter { it.type == 3 }.mapNotNull { it.languageCode }.distinct()
            if (subs.isNotEmpty()) {
                Text(
                    "Undertexter: ${subs.joinToString(", ")}",
                    color = JptvColors.Text.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall.copy(shadow = TextShadow),
                )
            }

            item.summary?.let {
                Text(
                    it,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge.copy(shadow = TextShadow),
                    modifier = Modifier.width(560.dp),
                )
            }

            if (item.progress > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressBar(item.progress, Modifier.width(300.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${formatTime(item.viewOffsetMs)} av ${formatTime(item.durationMs)}",
                        color = JptvColors.TextDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val resume = item.viewOffsetMs > 0
                Button(onClick = { vm.play(item) }, modifier = Modifier.focusRequester(playFocus)) {
                    Text(if (resume) "▶ Fortsätt från ${formatTime(item.viewOffsetMs)}" else "▶ Spela")
                }
                if (resume) {
                    OutlinedButton(onClick = { vm.play(item, fromStart = true) }) { Text("Börja om") }
                }
                trailer?.let { t -> OutlinedButton(onClick = { vm.playTrailer(t) }) { Text("▶ Trailer") } }
                OutlinedButton(onClick = {
                    val target = !watched
                    watched = target
                    vm.setWatched(item, target)
                }) {
                    Text(if (watched) "Markera som osedd" else "Markera som sedd")
                }
                item.showContainer()?.let { show ->
                    OutlinedButton(onClick = { vm.open(Screen.Episodes(show)) }) { Text("Alla avsnitt") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { playFocus.requestWhenReady() }
}

/** Stor bakgrundsbild som tonas mot mörkt, så att texten går att läsa. */
@Composable
fun Backdrop(
    url: String?,
    modifier: Modifier = Modifier,
    imageAlpha: Float = 1f,
    /** Video bakom bilden (trailer på hemskärmen); syns när bilden tonas bort. */
    video: (@Composable () -> Unit)? = null,
    /** Var nedtoningen mot botten börjar (0 = överst, 0,45 = strax under mitten). */
    fadeFrom: Float = 0f,
    /** Mörkt bara bakom texten till vänster; bilden syns fritt till höger (hemskärmen). */
    leftScrimOnly: Boolean = false,
) {
    Box(modifier.fillMaxSize()) {
        if (video != null) {
            video()
            // Täcker videoytan tills trailern faktiskt syns.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = imageAlpha }
                    .background(JptvColors.Background),
            )
        }
        if (url != null) {
            // Mjuk övertoning när bakgrunden byts (t.ex. när fokus flyttas på hemskärmen).
            val context = androidx.compose.ui.platform.LocalContext.current
            val request = remember(url) {
                coil.request.ImageRequest.Builder(context).data(url).crossfade(400).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = imageAlpha },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (leftScrimOnly) {
                        Brush.horizontalGradient(
                            0f to JptvColors.Background.copy(alpha = 0.95f),
                            0.3f to JptvColors.Background.copy(alpha = 0.8f),
                            0.5f to JptvColors.Background.copy(alpha = 0.45f),
                            0.7f to JptvColors.Background.copy(alpha = 0.1f),
                            1f to JptvColors.Background.copy(alpha = 0f),
                        )
                    } else {
                        Brush.horizontalGradient(
                            listOf(JptvColors.Background, JptvColors.Background.copy(alpha = 0.85f), JptvColors.Background.copy(alpha = 0.35f)),
                        )
                    },
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to JptvColors.Background.copy(alpha = 0f),
                        fadeFrom to JptvColors.Background.copy(alpha = 0f),
                        1f to JptvColors.Background,
                    ),
                ),
        )
    }
}

/** Mjuk skugga bakom text som ligger över bilder och trailers. */
val TextShadow = androidx.compose.ui.graphics.Shadow(
    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f),
    offset = androidx.compose.ui.geometry.Offset(0f, 2f),
    blurRadius = 8f,
)

fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h h $m min" else "$m min"
}
