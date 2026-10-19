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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.plex.LibrarySort
import se.jptv.plex.PlexItem
import se.jptv.plex.PlexSection

/** Hela biblioteket som rutnät, med sortering och filter för osedda. */
@Composable
fun SectionScreen(vm: AppViewModel, section: PlexSection) {
    var sort by remember { mutableStateOf(vm.sectionSort[section.key] ?: LibrarySort.TITLE) }
    var unwatched by remember { mutableStateOf(vm.sectionUnwatched[section.key] ?: false) }
    val result by produceState<Result<List<PlexItem>>?>(initialValue = null, section.key, sort, unwatched) {
        value = null
        value = runCatching { vm.sectionItems(section.key, sort, unwatched) }
    }
    val firstFocus = remember { FocusRequester() }
    val sortFocus = remember { FocusRequester() }
    val memoryKey = "section_${section.key}_${sort.name}_$unwatched"

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        val items = result?.getOrNull()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScreenTitle(section.title, items?.let { "${it.size} titlar" })
            Spacer(Modifier.weight(1f))
            LibrarySort.entries.forEach { option ->
                if (option == sort) {
                    Button(onClick = {}, modifier = Modifier.focusRequester(sortFocus)) { Text(option.label) }
                } else {
                    OutlinedButton(onClick = {
                        sort = option
                        vm.sectionSort[section.key] = option
                    }) { Text(option.label) }
                }
            }
            Spacer(Modifier.width(8.dp))
            val toggle = {
                unwatched = !unwatched
                vm.sectionUnwatched[section.key] = unwatched
            }
            if (unwatched) {
                Button(onClick = toggle) { Text("Bara osedda") }
            } else {
                OutlinedButton(onClick = toggle) { Text("Alla") }
            }
        }
        Spacer(Modifier.height(20.dp))
        when {
            result == null -> MessageBox("Hämtar ${section.title}…")
            items == null -> MessageBox("Kunde inte hämta: ${result?.exceptionOrNull()?.message}")
            items.isEmpty() -> MessageBox(if (unwatched) "Inget osett här." else "Biblioteket är tomt.")
            else -> {
                val remembered = remember(memoryKey) { vm.focusKeys[memoryKey] }
                val focusIndex = remember(items) {
                    items.indexOfFirst { it.ratingKey == remembered }.coerceAtLeast(0)
                }
                val grid = vm.rememberGridState(memoryKey)
                LazyVerticalGrid(
                    state = grid,
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
                            watched = item.watched || (item.type == "show" && !item.hasUnwatched && (item.childCount ?: 0) > 0),
                            onClick = { vm.select(item) },
                            modifier = Modifier
                                .onFocusChanged { if (it.hasFocus) vm.focusKeys[memoryKey] = item.ratingKey }
                                .then(if (i == focusIndex) Modifier.focusRequester(firstFocus) else Modifier),
                        )
                    }
                }
                LaunchedEffect(items) {
                    if (grid.layoutInfo.visibleItemsInfo.none { it.index == focusIndex }) grid.scrollToItem(focusIndex)
                    firstFocus.requestWhenReady()
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (result == null) sortFocus.requestWhenReady() }
}

/** Serie: säsonger som flikar och avsnitten i vald säsong. En säsong öppnas med den förvald. */
@Composable
fun EpisodesScreen(vm: AppViewModel, container: PlexItem) {
    val showKey = if (container.type == "show") container.ratingKey else container.showKey ?: container.ratingKey
    var reload by remember { mutableIntStateOf(0) }
    val seasons by produceState<List<PlexItem>?>(initialValue = null, showKey, reload) {
        value = try {
            vm.plex.seasons(showKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    var selectedKey by remember {
        mutableStateOf(if (container.type == "season") container.ratingKey else vm.focusKeys["season_$showKey"])
    }
    val seasonList = seasons
    val season = seasonList?.firstOrNull { it.ratingKey == selectedKey }
        ?: seasonList?.firstOrNull { it.hasUnwatched && (it.season ?: 0) > 0 }
        ?: seasonList?.firstOrNull { (it.season ?: 0) > 0 }
        ?: seasonList?.firstOrNull()
    val episodesResult by produceState<Result<List<PlexItem>>?>(initialValue = null, season?.ratingKey, seasonList, reload) {
        value = null
        value = when {
            seasonList == null -> null
            season != null -> runCatching { vm.plex.episodes(season) }
            // Serier utan säsonger (eller om säsongerna inte gick att hämta): alla avsnitt.
            else -> runCatching { vm.plex.episodes(container.copy(type = "show", ratingKey = showKey)) }
        }
    }
    val firstFocus = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        ScreenTitle(container.showTitle?.takeIf { container.type == "season" } ?: container.title)
        container.summary?.takeIf { container.type == "show" }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = JptvColors.TextDim, modifier = Modifier.width(1000.dp))
        }
        Spacer(Modifier.height(14.dp))

        if (seasonList != null && seasonList.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(seasonList, key = { i, s -> "${i}_${s.ratingKey}" }) { _, s ->
                    val label = s.title + if (!s.hasUnwatched && (s.childCount ?: 0) > 0) " ✓" else ""
                    if (s.ratingKey == season?.ratingKey) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        OutlinedButton(onClick = {
                            selectedKey = s.ratingKey
                            vm.focusKeys["season_$showKey"] = s.ratingKey
                        }) { Text(label) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        val episodes = episodesResult?.getOrNull()
        if (season != null && !episodes.isNullOrEmpty()) {
            val allWatched = episodes.all { it.watched }
            OutlinedButton(onClick = {
                vm.setWatched(season, !allWatched) { reload++ }
            }) { Text(if (allWatched) "Markera säsongen som osedd" else "Markera säsongen som sedd") }
            Spacer(Modifier.height(10.dp))
        }

        when {
            episodesResult == null -> MessageBox("Hämtar avsnitt…")
            episodes == null -> MessageBox("Kunde inte hämta: ${episodesResult?.exceptionOrNull()?.message}")
            episodes.isEmpty() -> MessageBox("Inga avsnitt.")
            else -> {
                val memoryKey = "episodes_${season?.ratingKey ?: showKey}"
                val remembered = remember(memoryKey) { vm.focusKeys[memoryKey] }
                val startIndex = remember(episodes) {
                    episodes.indexOfFirst { it.ratingKey == remembered }.takeIf { it >= 0 }
                        ?: episodes.indexOfFirst { !it.watched }.coerceAtLeast(0)
                }
                val list = vm.rememberListState(memoryKey)
                LazyColumn(
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    itemsIndexed(episodes, key = { i, ep -> "${i}_${ep.ratingKey}" }) { i, ep ->
                        EpisodeRow(
                            episode = ep,
                            onClick = { vm.play(ep) },
                            onLongClick = { vm.open(Screen.Details(ep)) },
                            modifier = Modifier
                                .onFocusChanged { if (it.hasFocus) vm.focusKeys[memoryKey] = ep.ratingKey }
                                .then(if (i == startIndex) Modifier.focusRequester(firstFocus) else Modifier),
                        )
                    }
                }
                LaunchedEffect(episodes) {
                    if (list.layoutInfo.visibleItemsInfo.none { it.index == startIndex }) {
                        list.scrollToItem((startIndex - 2).coerceAtLeast(0))
                    }
                    firstFocus.requestWhenReady()
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: PlexItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val code = if (episode.episode != null) "${episode.episode}." else ""
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        headlineContent = {
            Text("$code  ${episode.title}".trim(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val parts = buildList {
                if (episode.durationMs > 0) add(formatDuration(episode.durationMs))
                if (episode.viewOffsetMs > 0) add("${formatTime(episode.viewOffsetMs)} sett")
                else if (episode.watched) add("Sett ✓")
                episode.summary?.let { add(it) }
            }
            Text(parts.joinToString(" · "), color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            if (episode.progress > 0f) {
                Text("Fortsätt", color = JptvColors.Accent, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}
