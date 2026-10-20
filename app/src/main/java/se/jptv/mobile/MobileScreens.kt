package se.jptv.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import se.jptv.AppViewModel
import se.jptv.BuildConfig
import se.jptv.Screen
import se.jptv.iptv.Channel
import se.jptv.plex.LibrarySort
import se.jptv.plex.PlexFilterOption
import se.jptv.plex.PlexItem
import se.jptv.plex.PlexSection
import se.jptv.ui.formatDuration
import se.jptv.ui.formatTime

// ======================================================================
// Gemensamt
// ======================================================================

/** Affisch (2:3) med hur långt man sett, och titel under. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterTile(
    vm: AppViewModel,
    item: PlexItem,
    /** null = hela bredden (rutnät). */
    width: Dp? = 110.dp,
    subtitle: String? = null,
    onClick: () -> Unit = { vm.select(item) },
) {
    Column((if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.small)
                .background(CardHigh),
        ) {
            AsyncImage(
                model = vm.plex.imageUrl(item.thumb, 240, 360),
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.progress > 0f && !item.watched) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(item.progress).height(4.dp).background(Accent))
                }
            }
            if (item.watched || (item.type == "show" && !item.hasUnwatched && (item.childCount ?: 0) > 0)) {
                Text(
                    "✓",
                    color = Bg,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Accent, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(item.displayTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        (subtitle ?: vm.posterSubtitle(item))?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Centered(text: String, button: String? = null, onButton: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Dim)
        if (button != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onButton) { Text(button) }
        }
    }
}

/** Bakgrundsbild överst (16:9) som tonas mot mörkt nertill. */
@Composable
fun HeaderImage(url: String?) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Card)) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Bg)))
    }
}

// ======================================================================
// Inloggning
// ======================================================================

@Composable
fun MobileLogin(vm: AppViewModel) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("JPTV", color = Accent, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(24.dp))
        Text("Logga in med Plex", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        val code = vm.loginCode
        if (code != null) {
            Text("Öppna plex.tv/link och skriv koden:", color = Dim)
            Spacer(Modifier.height(8.dp))
            Text(code, style = MaterialTheme.typography.displayMedium, color = Accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://plex.tv/link"))) }
            }) { Text("Öppna plex.tv/link") }
        } else if (vm.loginError == null) {
            CircularProgressIndicator()
        }
        vm.loginError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.beginLogin() }) { Text("Ny kod") }
        }
    }
}

// ======================================================================
// Hem
// ======================================================================

@Composable
fun MobileHome(vm: AppViewModel) {
    val liveNow = remember(vm.playlist, vm.favorites, vm.recents) { vm.liveNowChannels() }
    val rows = vm.homeRows
    when {
        vm.homeError != null && rows.isEmpty() && liveNow.isEmpty() ->
            Centered(vm.homeError ?: "", "Försök igen") { vm.connectAndLoad(rediscover = true) }
        rows.isEmpty() && liveNow.isEmpty() && vm.homeLoading -> Centered("Hämtar från ${vm.serverName ?: "Plex"}…")
        rows.isEmpty() && liveNow.isEmpty() -> Centered("Inget att visa ännu.", "Uppdatera") { vm.refreshHome() }
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            vm.availableUpdate?.let { release ->
                item(key = "update") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Card, MaterialTheme.shapes.medium).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(vm.updateMessage ?: "JPTV ${release.versionName} finns.", Modifier.weight(1f))
                        Button(onClick = { vm.installUpdate() }, enabled = !vm.updateBusy) { Text("Uppdatera") }
                    }
                }
            }
            if (liveNow.isNotEmpty()) {
                item(key = "live") {
                    Column {
                        RowTitle(if (vm.favorites.isEmpty()) "Senaste kanaler" else "Live nu")
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(liveNow, key = { _, ch -> ch.key }) { i, ch ->
                                LiveCard(vm, ch) { vm.playChannel(liveNow, i) }
                            }
                        }
                    }
                }
            }
            items(rows.size, key = { "row_$it" }) { r ->
                val row = rows[r]
                Column {
                    RowTitle(row.title)
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(row.items.distinctBy { it.ratingKey }, key = { it.ratingKey }) { item ->
                            PosterTile(vm, item, subtitle = row.labels[item.ratingKey])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun LiveCard(vm: AppViewModel, channel: Channel, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val current = vm.epg?.now(channel, now)
    Column(
        Modifier
            .width(180.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Card)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogoSmall(channel, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(6.dp))
        Text(current?.title ?: "Ingen guide", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        if (current != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { current.progress(now) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Accent)
        }
    }
}

@Composable
fun ChannelLogoSmall(channel: Channel, size: Dp) {
    Box(Modifier.size(size).clip(MaterialTheme.shapes.extraSmall).background(CardHigh), contentAlignment = Alignment.Center) {
        if (channel.logo != null) {
            AsyncImage(model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
        } else {
            Text(channel.name.take(2), style = MaterialTheme.typography.labelSmall, color = Dim)
        }
    }
}

// ======================================================================
// Bibliotek
// ======================================================================

@Composable
fun MobileLibrary(vm: AppViewModel, section: PlexSection, initialGenre: PlexFilterOption?) {
    var sort by remember { mutableStateOf(vm.sectionSort[section.key] ?: LibrarySort.TITLE) }
    var unwatched by remember { mutableStateOf(vm.sectionUnwatched[section.key] ?: false) }
    var genre by remember { mutableStateOf(initialGenre ?: vm.sectionGenre[section.key]) }
    var showCollections by remember { mutableStateOf(false) }
    val genres by produceState<List<PlexFilterOption>>(emptyList(), section.key) {
        value = runCatching { vm.genres(section.key) }.getOrDefault(emptyList())
    }
    val collections by produceState<List<PlexItem>>(emptyList(), section.key) {
        value = runCatching { vm.plex.collections(section.key) }.getOrDefault(emptyList())
    }
    val result by produceState<Result<List<PlexItem>>?>(null, section.key, sort, unwatched, genre, showCollections, vm.libraryVersion) {
        value = null
        value = runCatching {
            if (showCollections) vm.plex.collections(section.key)
            else vm.sectionItems(section.key, sort, unwatched, genre?.id).distinctBy { it.ratingKey }
        }
    }
    Column(Modifier.fillMaxSize()) {
        // Biblioteken (Filmer, TV-serier …)
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.visibleSections, key = { it.key }) { s ->
                FilterChip(
                    selected = s.key == section.key,
                    onClick = { if (s.key != section.key) vm.replaceTop(Screen.Section(s)) },
                    label = { Text(s.title) },
                )
            }
        }
        // Kategorier
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") {
                FilterChip(
                    selected = genre == null && !showCollections,
                    onClick = {
                        genre = null
                        showCollections = false
                        vm.sectionGenre[section.key] = null
                    },
                    label = { Text("Alla") },
                )
            }
            items(genres, key = { "g_${it.id}" }) { g ->
                FilterChip(
                    selected = !showCollections && genre?.id == g.id,
                    onClick = {
                        genre = g
                        showCollections = false
                        vm.sectionGenre[section.key] = g
                    },
                    label = { Text(g.title) },
                )
            }
            if (collections.isNotEmpty()) {
                item(key = "collections") {
                    FilterChip(selected = showCollections, onClick = { showCollections = true }, label = { Text("Samlingar") })
                }
            }
        }
        // Sortering och osedda
        if (!showCollections) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibrarySort.entries.toList(), key = { it.name }) { option ->
                    FilterChip(
                        selected = option == sort,
                        onClick = {
                            sort = option
                            vm.sectionSort[section.key] = option
                        },
                        label = { Text(option.label) },
                    )
                }
                item(key = "unwatched") {
                    FilterChip(
                        selected = unwatched,
                        onClick = {
                            unwatched = !unwatched
                            vm.sectionUnwatched[section.key] = unwatched
                        },
                        label = { Text("Bara osedda") },
                    )
                }
            }
        }
        val items = result?.getOrNull()
        when {
            result == null -> Centered("Hämtar ${section.title}…")
            items == null -> Centered("Kunde inte hämta: ${result?.exceptionOrNull()?.message}")
            items.isEmpty() -> Centered("Inget här.")
            else -> PosterGrid(vm, items)
        }
    }
}

@Composable
private fun PosterGrid(vm: AppViewModel, items: List<PlexItem>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items, key = { it.ratingKey }) { item ->
            PosterTile(
                vm,
                item,
                width = null,
                subtitle = if (item.isCollection) item.childCount?.let { "$it titlar" } else null,
            )
        }
    }
}

@Composable
fun MobileCollection(vm: AppViewModel, collection: PlexItem) {
    val result by produceState<Result<List<PlexItem>>?>(null, collection.ratingKey) {
        value = runCatching { vm.plex.collectionItems(collection.ratingKey) }
    }
    val items = result?.getOrNull()
    when {
        result == null -> Centered("Hämtar…")
        items.isNullOrEmpty() -> Centered("Samlingen är tom.")
        else -> PosterGrid(vm, items)
    }
}

// ======================================================================
// Serie och detaljer
// ======================================================================

/** Serien: bild, knappar, säsonger och hela säsongens avsnitt med bild och handling. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MobileSeries(vm: AppViewModel, container: PlexItem) {
    val showKey = if (container.type == "show") container.ratingKey else container.showKey ?: container.ratingKey
    var reload by remember { mutableIntStateOf(0) }
    val seasons by produceState<List<PlexItem>?>(null, showKey, reload) {
        value = runCatching { vm.plex.seasons(showKey) }.getOrDefault(emptyList())
    }
    var selectedKey by remember {
        mutableStateOf(if (container.type == "season") container.ratingKey else vm.focusKeys["season_$showKey"])
    }
    val seasonList = seasons
    val season = seasonList?.firstOrNull { it.ratingKey == selectedKey }
        ?: seasonList?.firstOrNull { it.hasUnwatched && (it.season ?: 0) > 0 }
        ?: seasonList?.firstOrNull { (it.season ?: 0) > 0 }
        ?: seasonList?.firstOrNull()
    val episodesResult by produceState<Result<List<PlexItem>>?>(null, season?.ratingKey, seasonList, reload) {
        value = null
        value = when {
            seasonList == null -> null
            season != null -> runCatching { vm.plex.episodes(season) }
            else -> runCatching { vm.plex.episodes(container.copy(type = "show", ratingKey = showKey)) }
        }
    }
    val showItem = remember(showKey) { container.copy(type = "show", ratingKey = showKey) }
    val showMeta by produceState<PlexItem?>(null, showKey) { value = runCatching { vm.plex.metadata(showKey) }.getOrNull() }
    val trailer by produceState<PlexItem?>(null, showKey) { value = runCatching { vm.trailerFor(showItem) }.getOrNull() }
    val show = showMeta ?: showItem
    val episodes = episodesResult?.getOrNull()
    val nextUp = episodes?.let { eps ->
        eps.firstOrNull { it.viewOffsetMs > 0 && !it.watched } ?: eps.firstOrNull { !it.watched } ?: eps.firstOrNull()
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Column {
                HeaderImage(vm.plex.imageUrl(show.art ?: container.art, 1280, 720))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(show.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        vm.imdbText(show)?.let { Tag(it) }
                        val seasonCount = seasonList?.count { (it.season ?: 0) > 0 } ?: 0
                        Text(
                            listOfNotNull(
                                show.year?.toString(),
                                if (seasonCount > 0) (if (seasonCount == 1) "1 säsong" else "$seasonCount säsonger") else null,
                                show.genres.take(2).joinToString(", ").ifEmpty { null },
                            ).joinToString(" · "),
                            color = Dim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    show.summary?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        nextUp?.let { ep ->
                            val code = listOfNotNull(ep.season?.let { "S$it" }, ep.episode?.let { "E$it" }).joinToString(" ")
                            Button(onClick = { vm.play(ep) }) {
                                Text((if (ep.viewOffsetMs > 0 && !ep.watched) "▶ Fortsätt " else "▶ Spela ") + code)
                            }
                        }
                        OutlinedButton(onClick = { vm.playShuffle(showItem) }) { Text("Slumpvis") }
                        trailer?.let { t -> OutlinedButton(onClick = { vm.playTrailer(t) }) { Text("Trailer") } }
                    }
                }
            }
        }
        if (seasonList != null && seasonList.size > 1) {
            item(key = "seasons") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(seasonList, key = { it.ratingKey }) { s ->
                        val done = !s.hasUnwatched && (s.childCount ?: 0) > 0
                        FilterChip(
                            selected = s.ratingKey == season?.ratingKey,
                            onClick = {
                                selectedKey = s.ratingKey
                                vm.focusKeys["season_$showKey"] = s.ratingKey
                            },
                            label = { Text(s.title + if (done) " ✓" else "") },
                        )
                    }
                }
            }
        }
        if (season != null && !episodes.isNullOrEmpty()) {
            item(key = "season_watched") {
                val allWatched = episodes.all { it.watched }
                TextButton(
                    onClick = { vm.setWatched(season, !allWatched) { reload++ } },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(if (allWatched) "Markera säsongen som osedd" else "Markera säsongen som sedd") }
            }
        }
        when {
            episodesResult == null -> item(key = "loading") { Centered("Hämtar avsnitt…") }
            episodes == null -> item(key = "error") { Centered("Kunde inte hämta avsnitten.") }
            else -> items(episodes, key = { it.ratingKey }) { ep ->
                EpisodeItem(vm, ep, onClick = { vm.play(ep) }, onLongClick = { vm.open(Screen.Details(ep)) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeItem(vm: AppViewModel, ep: PlexItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .width(136.dp)
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.small)
                .background(CardHigh),
        ) {
            AsyncImage(
                model = vm.plex.imageUrl(ep.still ?: ep.thumb, 320, 180),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (ep.progress > 0f && !ep.watched) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(ep.progress).height(3.dp).background(Accent))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (ep.episode != null) "${ep.episode}. " else "") + ep.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    vm.imdbText(ep),
                    if (ep.durationMs > 0) formatDuration(ep.durationMs) else null,
                    when {
                        ep.watched -> "Sett ✓"
                        ep.viewOffsetMs > 0 -> "${formatDuration((ep.durationMs - ep.viewOffsetMs).coerceAtLeast(60_000))} kvar"
                        else -> null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (ep.viewOffsetMs > 0 && !ep.watched) Accent else Dim,
            )
            ep.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Film eller avsnitt: bild, betyg, handling och knappar. */
@Composable
fun MobileDetails(vm: AppViewModel, initial: PlexItem) {
    var item by remember { mutableStateOf(initial) }
    var watched by remember { mutableStateOf(initial.watched) }
    var trailer by remember { mutableStateOf<PlexItem?>(null) }
    LaunchedEffect(initial.ratingKey) {
        runCatching { vm.plex.metadata(initial.ratingKey) }.getOrNull()?.let {
            item = it
            watched = it.watched
        }
        trailer = runCatching { vm.trailerFor(item) }.getOrNull()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        HeaderImage(vm.plex.imageUrl(item.art, 1280, 720))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.type == "episode") {
                Text(item.showTitle ?: "", color = Dim, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        if (item.season != null && item.episode != null) "S${item.season} E${item.episode}" else null,
                        item.title,
                    ).joinToString("  "),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (vm.ratingBadges(item) + item.badges).take(5).forEach { Tag(it) }
            }
            Text(
                listOfNotNull(
                    item.year?.toString(),
                    if (item.durationMs > 0) formatDuration(item.durationMs) else null,
                    item.contentRating,
                    item.genres.take(3).joinToString(", ").ifEmpty { null },
                ).joinToString(" · "),
                color = Dim,
                style = MaterialTheme.typography.bodySmall,
            )
            item.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (item.progress > 0f) {
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth(), color = Accent)
                Text("${formatTime(item.viewOffsetMs)} av ${formatTime(item.durationMs)}", color = Dim, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            val resume = item.viewOffsetMs > 0
            Button(onClick = { vm.play(item) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (resume) "▶ Fortsätt från ${formatTime(item.viewOffsetMs)}" else "▶ Spela")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (resume) OutlinedButton(onClick = { vm.play(item, fromStart = true) }) { Text("Börja om") }
                trailer?.let { t -> OutlinedButton(onClick = { vm.playTrailer(t) }) { Text("Trailer") } }
                OutlinedButton(onClick = {
                    val target = !watched
                    watched = target
                    vm.setWatched(item, target)
                }) { Text(if (watched) "Osedd" else "Sedd ✓") }
            }
            item.showContainer()?.let { show ->
                TextButton(onClick = { vm.open(Screen.Episodes(show)) }) { Text("Alla avsnitt") }
            }
        }
    }
}

// ======================================================================
// Sök
// ======================================================================

@Composable
fun MobileSearch(vm: AppViewModel) {
    var query by remember { mutableStateOf(vm.focusKeys["mobile_search"] ?: "") }
    val results by produceState(AppViewModel.SearchResults(), query) {
        vm.focusKeys["mobile_search"] = query
        if (query.trim().length < 2) {
            value = AppViewModel.SearchResults()
            return@produceState
        }
        delay(350)
        value = try {
            vm.searchEverything(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppViewModel.SearchResults(error = e.message)
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Filmer, serier, kanaler, program") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            results.plex.forEach { hub ->
                item(key = "hub_${hub.title}") {
                    Column {
                        RowTitle(hub.title)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(hub.items, key = { it.ratingKey }) { item -> PosterTile(vm, item) }
                        }
                    }
                }
            }
            if (results.channels.isNotEmpty()) {
                item(key = "channels_title") { RowTitle("Kanaler") }
                items(results.channels, key = { "ch_${it.key}" }) { ch ->
                    ChannelListRow(vm, ch, onClick = { vm.playChannel(results.channels, results.channels.indexOf(ch)) })
                }
            }
            if (results.programmes.isNotEmpty()) {
                item(key = "prog_title") { RowTitle("I TV-guiden") }
                items(results.programmes.size, key = { "p_$it" }) { i ->
                    val (ch, p) = results.programmes[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.playChannel(listOf(ch), 0) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelLogoSmall(ch, 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${se.jptv.ui.clock(p.start)} · ${ch.name}", color = Dim, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            results.error?.let { item(key = "err") { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        }
    }
}

// ======================================================================
// Inställningar
// ======================================================================

@Composable
fun MobileSettings(vm: AppViewModel) {
    var url by remember { mutableStateOf(vm.prefs.m3uUrl ?: "") }
    var epgUrl by remember { mutableStateOf(vm.prefs.epgUrl ?: "") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Plex", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            (vm.serverName?.let { "Ansluten till $it" } ?: "Inte ansluten") +
                if (vm.plex.server != null) (if (vm.plex.serverIsLocal()) " (hemma)" else " (hemifrån)") else "",
            color = Dim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.connectAndLoad(rediscover = true) }) { Text("Anslut igen") }
            OutlinedButton(onClick = { vm.logout() }) { Text("Logga ut") }
        }

        HorizontalDivider()
        Text("Kvalitet hemifrån", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "När du inte är hemma (eller castar till en TV på hotellet) kodar Plex om till den här nivån. Hemma spelas originalet.",
            color = Dim,
            style = MaterialTheme.typography.bodySmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(AppViewModel.REMOTE_QUALITIES) { i, (label, _) ->
                FilterChip(selected = vm.remoteQuality == i, onClick = { vm.changeRemoteQuality(i) }, label = { Text(label) })
            }
        }

        HorizontalDivider()
        Text("IPTV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("M3U-adress") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.loadPlaylist(url) }, enabled = !vm.iptvLoading) { Text(if (vm.iptvLoading) "Laddar…" else "Ladda spellista") }
            if (vm.playlist != null) OutlinedButton(onClick = { vm.removePlaylist(); url = "" }) { Text("Ta bort") }
        }
        Text(vm.iptvStatus ?: vm.playlist?.let { "${it.channels.size} kanaler" } ?: "Ingen spellista.", color = Dim, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = epgUrl,
            onValueChange = { epgUrl = it },
            label = { Text("TV-guide (XMLTV), tomt = gissa") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.setEpgUrl(epgUrl) }, enabled = !vm.epgLoading && vm.playlist != null) {
            Text(if (vm.epgLoading) "Hämtar…" else "Spara och hämta guide")
        }
        Text(vm.epgStatus ?: vm.epg?.let { "Guide för ${it.channelCount} kanaler." } ?: "Ingen guide.", color = Dim, style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Casting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (CastManager.available) {
                "Tryck på castikonen uppe till höger och välj TV. Filmer och serier från Plex fungerar på alla Chromecast och Google TV. " +
                    "Live-TV går bara om leverantören har strömmar som TV:n kan spela (https och HLS)."
            } else {
                "Casting kräver Google Play-tjänster på telefonen."
            },
            color = Dim,
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("Uppdateringar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(vm.updateMessage ?: "Version ${BuildConfig.VERSION_NAME}", color = Dim, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }) { Text("Sök efter uppdatering") }
            vm.availableUpdate?.let { Button(onClick = { vm.installUpdate() }, enabled = !vm.updateBusy) { Text("Installera ${it.versionName}") } }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}
