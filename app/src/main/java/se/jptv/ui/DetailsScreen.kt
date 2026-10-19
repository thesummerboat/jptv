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

    LaunchedEffect(initial.ratingKey) {
        runCatching { vm.plex.metadata(initial.ratingKey) }.getOrNull()?.let {
            item = it
            watched = it.watched
        }
        // Avsnitt har ingen egen logotyp; använd seriens.
        logo = vm.showLogo(item)
        trailer = runCatching { vm.plex.trailers(item) }.getOrNull()?.firstOrNull()
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(vm.plex.imageUrl(item.art, 1920, 1080))
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 64.dp, end = 64.dp)
                .width(900.dp),
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
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                TitleLogo(
                    logoUrl = vm.plex.rawImageUrl(logo),
                    title = item.title,
                    maxHeight = 140.dp,
                    maxWidth = 560.dp,
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
            Text(facts.joinToString("  ·  "), color = JptvColors.TextDim)

            val subs = item.streams.filter { it.type == 3 }.mapNotNull { it.languageCode }.distinct()
            if (subs.isNotEmpty()) {
                Text("Undertexter: ${subs.joinToString(", ")}", color = JptvColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }

            item.summary?.let {
                Text(it, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
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
                    Brush.horizontalGradient(
                        listOf(JptvColors.Background, JptvColors.Background.copy(alpha = 0.85f), JptvColors.Background.copy(alpha = 0.35f)),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(JptvColors.Background.copy(alpha = 0f), JptvColors.Background)),
                ),
        )
    }
}

fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h h $m min" else "$m min"
}
