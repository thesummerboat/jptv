@file:OptIn(ExperimentalTvMaterial3Api::class)

package se.jptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import se.jptv.AppViewModel
import se.jptv.PlayRequest
import se.jptv.Screen
import se.jptv.plex.PlexItem
import se.jptv.plex.PlexSection

/** Hela biblioteket (alla filmer eller alla serier) som ett rutnät. */
@Composable
fun SectionScreen(vm: AppViewModel, section: PlexSection) {
    val result by produceState<Result<List<PlexItem>>?>(initialValue = null, section.key) {
        value = runCatching { vm.plex.all(section.key) }
    }
    val firstFocus = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        val items = result?.getOrNull()
        ScreenTitle(section.title, items?.let { "${it.size} titlar" })
        Spacer(Modifier.height(20.dp))
        when {
            result == null -> MessageBox("Hämtar ${section.title}…")
            items == null -> MessageBox("Kunde inte hämta: ${result?.exceptionOrNull()?.message}")
            items.isEmpty() -> MessageBox("Biblioteket är tomt.")
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(PosterWidth + 18.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 48.dp),
                ) {
                    itemsIndexed(items, key = { i, item -> "${i}_${item.ratingKey}" }) { i, item ->
                        PosterCard(
                            title = item.displayTitle,
                            subtitle = item.subtitle,
                            imageUrl = vm.plex.imageUrl(item.thumb),
                            progress = item.progress,
                            watched = item.watched,
                            onClick = { vm.select(item) },
                            modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
                LaunchedEffect(items) { runCatching { firstFocus.requestFocus() } }
            }
        }
    }
}

/** Avsnittslista för en serie eller en säsong. */
@Composable
fun EpisodesScreen(vm: AppViewModel, container: PlexItem) {
    val result by produceState<Result<List<PlexItem>>?>(initialValue = null, container.ratingKey) {
        value = runCatching { vm.plex.episodes(container) }
    }
    val firstFocus = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        val title = if (container.type == "season") "${container.showTitle ?: ""} · ${container.title}" else container.title
        ScreenTitle(title)
        container.summary?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, color = JptvColors.TextDim, modifier = Modifier.width(900.dp))
        }
        Spacer(Modifier.height(20.dp))
        val episodes = result?.getOrNull()
        when {
            result == null -> MessageBox("Hämtar avsnitt…")
            episodes == null -> MessageBox("Kunde inte hämta: ${result?.exceptionOrNull()?.message}")
            episodes.isEmpty() -> MessageBox("Inga avsnitt.")
            else -> {
                // Börja på första avsnittet som inte är sett.
                val startIndex = episodes.indexOfFirst { !it.watched }.coerceAtLeast(0)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    itemsIndexed(episodes, key = { i, ep -> "${i}_${ep.ratingKey}" }) { i, ep ->
                        EpisodeRow(
                            episode = ep,
                            onClick = { vm.open(Screen.Player(PlayRequest.Vod(ep))) },
                            modifier = if (i == startIndex) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
                LaunchedEffect(episodes) { runCatching { firstFocus.requestFocus() } }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: PlexItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val code = if (episode.season != null && episode.episode != null) "S${episode.season}E${episode.episode}" else ""
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        headlineContent = {
            Text("$code  ${episode.title}".trim(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val parts = buildList {
                if (episode.durationMs > 0) add(formatTime(episode.durationMs))
                if (episode.viewOffsetMs > 0) add("${formatTime(episode.viewOffsetMs)} sett")
                else if (episode.watched) add("Sett")
            }
            Text(parts.joinToString(" · "), color = JptvColors.TextDim)
        },
        trailingContent = {
            if (episode.progress > 0f) {
                Row {
                    Text("Fortsätt", color = JptvColors.Accent, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    )
}
