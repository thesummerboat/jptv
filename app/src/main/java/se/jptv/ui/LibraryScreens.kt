@file:OptIn(ExperimentalTvMaterial3Api::class)

package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import se.jptv.plex.PlexFilterOption
import se.jptv.plex.PlexItem
import se.jptv.plex.PlexSection

/** Hela biblioteket som rutnät, med genre, samlingar, sortering och filter för osedda. */
@Composable
fun SectionScreen(vm: AppViewModel, section: PlexSection, initialGenre: PlexFilterOption? = null) {
    var sort by remember { mutableStateOf(vm.sectionSort[section.key] ?: LibrarySort.TITLE) }
    var unwatched by remember { mutableStateOf(vm.sectionUnwatched[section.key] ?: false) }
    var genre by remember { mutableStateOf(initialGenre ?: vm.sectionGenre[section.key]) }
    var showCollections by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    val genres by produceState<List<PlexFilterOption>>(emptyList(), section.key) {
        value = runCatching { vm.genres(section.key) }.getOrDefault(emptyList())
    }
    val collections by produceState<List<PlexItem>>(emptyList(), section.key) {
        value = runCatching { vm.plex.collections(section.key) }.getOrDefault(emptyList())
    }
    // Efter en skanning (libraryVersion ökar) ligger den gamla listan kvar tills den nya har kommit.
    var shownVersion by remember { mutableIntStateOf(vm.libraryVersion) }
    val version = vm.libraryVersion
    val result by produceState<Result<List<PlexItem>>?>(
        initialValue = null, section.key, sort, unwatched, genre, showCollections, version,
    ) {
        if (version == shownVersion) value = null
        value = runCatching {
            if (showCollections) {
                vm.plex.collections(section.key)
            } else {
                vm.sectionItems(section.key, sort, unwatched, genre?.id).distinctBy { it.ratingKey }
            }
        }
        shownVersion = version
    }
    val filterFocus = remember { FocusRequester() }
    val memoryKey = "section_${section.key}_${sort.name}_${unwatched}_${genre?.id}_$showCollections"

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 48.dp, top = 28.dp, end = 48.dp),
        ) {
            val items = result?.getOrNull()
            Row(verticalAlignment = Alignment.CenterVertically) {
                val what = when {
                    showCollections -> "samlingar"
                    else -> listOfNotNull(genre?.title, if (unwatched) "osedda" else null).joinToString(" · ")
                }
                ScreenTitle(
                    section.title,
                    listOfNotNull(
                        items?.let { "${it.size} ${if (showCollections) "samlingar" else "titlar"}" },
                        what.ifEmpty { null },
                    ).joinToString(" · ").ifEmpty { null },
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { vm.scanLibrary(section) }, enabled = !vm.scanning) {
                    Text(if (vm.scanning) "Skannar…" else "Skanna")
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { pickerOpen = true }, modifier = Modifier.focusRequester(filterFocus)) {
                    Text(
                        when {
                            showCollections -> "Samlingar ▾"
                            genre != null -> "${genre?.title} ▾"
                            else -> "Alla genrer ▾"
                        },
                    )
                }
                if (!showCollections) {
                    Spacer(Modifier.width(8.dp))
                    LibrarySort.entries.forEach { option ->
                        if (option == sort) {
                            Button(onClick = {}) { Text(option.label) }
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
                        OutlinedButton(onClick = toggle) { Text("Sedda och osedda") }
                    }
                }
            }
            vm.scanStatus?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = if (vm.scanning) JptvColors.Accent else JptvColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            when {
                result == null -> MessageBox("Hämtar ${section.title}…")
                items == null -> MessageBox("Kunde inte hämta: ${result?.exceptionOrNull()?.message}")
                items.isEmpty() -> MessageBox(
                    when {
                        showCollections -> "Inga samlingar i det här biblioteket."
                        unwatched -> "Inget osett här."
                        genre != null -> "Inget i ${genre?.title}."
                        else -> "Biblioteket är tomt."
                    },
                )
                else -> PosterGrid(vm, items, memoryKey)
            }
        }
        if (pickerOpen) {
            FilterPicker(
                genres = genres,
                collectionCount = collections.size,
                selectedGenre = genre,
                collectionsSelected = showCollections,
                onPick = { picked, collectionsPicked ->
                    genre = picked
                    showCollections = collectionsPicked
                    vm.sectionGenre[section.key] = picked
                    pickerOpen = false
                },
                onClose = { pickerOpen = false },
            )
        }
    }
    LaunchedEffect(pickerOpen) { if (!pickerOpen) filterFocus.requestWhenReady() }
}

/** Affischer i rutnät; minns fokus och scroll per lista. */
@Composable
private fun PosterGrid(vm: AppViewModel, items: List<PlexItem>, memoryKey: String, focusFirst: Boolean = false) {
    val firstFocus = remember { FocusRequester() }
    val remembered = remember(memoryKey) { vm.focusKeys[memoryKey] }
    val focusIndex = remember(items) {
        items.indexOfFirst { it.ratingKey == remembered }.let { if (it < 0 && focusFirst) 0 else it }
    }
    val grid = vm.rememberGridState(memoryKey)
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(PosterWidth + 18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 48.dp),
    ) {
        // Nyckel på id, så att fokus stannar på samma titel när listan laddas om efter en skanning.
        itemsIndexed(items, key = { _, item -> item.ratingKey }) { i, item ->
            PosterCard(
                title = item.displayTitle,
                subtitle = if (item.isCollection) item.childCount?.let { "$it titlar" } else vm.posterSubtitle(item),
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
    // Tillbaka till samma affisch som sist; första gången stannar fokus på knapparna ovanför.
    LaunchedEffect(items) {
        if (focusIndex < 0) return@LaunchedEffect
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == focusIndex }) grid.scrollToItem(focusIndex)
        firstFocus.requestWhenReady()
    }
}

/** Panel för att välja genre eller samlingar. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FilterPicker(
    genres: List<PlexFilterOption>,
    collectionCount: Int,
    selectedGenre: PlexFilterOption?,
    collectionsSelected: Boolean,
    onPick: (PlexFilterOption?, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val selectedFocus = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .fillMaxHeight()
                .width(420.dp)
                .background(JptvColors.Surface)
                .padding(start = 24.dp, top = 28.dp, end = 24.dp),
        ) {
            Text("Visa", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            val noneSelected = selectedGenre == null && !collectionsSelected
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "all") {
                    ListItem(
                        selected = noneSelected,
                        onClick = { onPick(null, false) },
                        headlineContent = { Text("Alla genrer") },
                        modifier = if (noneSelected) Modifier.focusRequester(selectedFocus) else Modifier,
                    )
                }
                if (collectionCount > 0) {
                    item(key = "collections") {
                        ListItem(
                            selected = collectionsSelected,
                            onClick = { onPick(null, true) },
                            headlineContent = { Text("Samlingar") },
                            supportingContent = { Text("$collectionCount st") },
                            modifier = if (collectionsSelected) Modifier.focusRequester(selectedFocus) else Modifier,
                        )
                    }
                }
                itemsIndexed(genres, key = { i, g -> "g_${i}_${g.id}" }) { _, g ->
                    val selected = !collectionsSelected && g.id == selectedGenre?.id
                    ListItem(
                        selected = selected,
                        onClick = { onPick(g, false) },
                        headlineContent = { Text(g.title) },
                        modifier = if (selected) Modifier.focusRequester(selectedFocus) else Modifier,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { selectedFocus.requestWhenReady() }
}

/** En samling i Plex (t.ex. "Marvel" eller "Bond") som rutnät. */
@Composable
fun CollectionScreen(vm: AppViewModel, collection: PlexItem) {
    val result by produceState<Result<List<PlexItem>>?>(null, collection.ratingKey) {
        value = runCatching { vm.plex.collectionItems(collection.ratingKey) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        val items = result?.getOrNull()
        ScreenTitle(collection.title, items?.let { "${it.size} titlar" })
        Spacer(Modifier.height(16.dp))
        when {
            result == null -> MessageBox("Hämtar samlingen…")
            items == null -> MessageBox("Kunde inte hämta: ${result?.exceptionOrNull()?.message}")
            items.isEmpty() -> MessageBox("Samlingen är tom.")
            else -> PosterGrid(vm, items, "collection_${collection.ratingKey}", focusFirst = true)
        }
    }
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
        // Seriens trailer och IMDb-betyg i rubriken.
        val showItem = remember(showKey) { container.copy(type = "show", ratingKey = showKey) }
        val showMeta by produceState<PlexItem?>(null, showKey) { value = runCatching { vm.plex.metadata(showKey) }.getOrNull() }
        val trailers by produceState<List<PlexItem>>(emptyList(), showKey) {
            value = runCatching { vm.plex.trailers(showMeta ?: showItem) }.getOrDefault(emptyList())
        }
        // Seriens ledmotiv, lågt, när sidan öppnas.
        ThemeMusic(vm, showMeta?.theme?.let { vm.plex.rawImageUrl(it) })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenTitle(container.showTitle?.takeIf { container.type == "season" } ?: container.title)
            showMeta?.let { meta -> Badges(listOfNotNull(vm.imdbText(meta)) + meta.ratings.filter { it.source != "IMDb" }.map { it.text }) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { vm.playShuffle(showItem) }) { Text("Spela slumpvis") }
            trailers.firstOrNull()?.let { t -> OutlinedButton(onClick = { vm.playTrailer(t) }) { Text("▶ Trailer") } }
        }
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
                            imdb = vm.imdbText(ep),
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
private fun EpisodeRow(
    episode: PlexItem,
    imdb: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                imdb?.let { add(it) }
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
