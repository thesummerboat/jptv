package se.jptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.iptv.Channel
import se.jptv.plex.PlexHomeRow
import se.jptv.plex.PlexItem

private const val CONTINUE_ROW = "Fortsätt titta"

@Composable
fun HomeScreen(vm: AppViewModel) {
    val topFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }
    var focusedItem by remember { mutableStateOf<PlexItem?>(null) }
    var menuFor by remember { mutableStateOf<Pair<PlexItem, Boolean>?>(null) }
    // Kom tillbaka till samma kort som sist, om det finns kvar.
    val rememberedKey = remember { vm.focusKeys["home"] }
    val liveNow = remember(vm.playlist, vm.favorites, vm.recents) { vm.liveNowChannels() }
    // Nyckeln bygger på radens namn och titelns id, så den håller även när raderna laddas om.
    val rememberedExists = remember(vm.homeRows, liveNow) {
        rememberedKey != null && (
            vm.homeRows.any { row -> row.items.any { item -> homeKey(row.title, item.ratingKey) == rememberedKey } } ||
                liveNow.any { ch -> homeKey(LIVE_ROW, ch.key) == rememberedKey }
            )
    }
    // Fokus sätts en gång när skärmen öppnas; senare omladdningar ska inte rycka fokus.
    var initialFocusDone by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Backdrop(vm.plex.imageUrl(focusedItem?.art, 1280, 720))
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 48.dp, top = 28.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("JPTV", style = MaterialTheme.typography.headlineMedium, color = JptvColors.Accent)
                Spacer(Modifier.width(16.dp))
                Button(onClick = { vm.open(Screen.LiveTv) }, modifier = Modifier.focusRequester(topFocus)) {
                    Text("Live-TV")
                }
                vm.sections.forEach { section ->
                    OutlinedButton(onClick = { vm.open(Screen.Section(section)) }) { Text(section.title) }
                }
                OutlinedButton(onClick = { vm.open(Screen.Search) }) { Text("Sök") }
                OutlinedButton(onClick = { vm.open(Screen.Settings) }) { Text("Inställningar") }
                Spacer(Modifier.weight(1f))
                Clock()
            }
            Spacer(Modifier.height(16.dp))

            vm.availableUpdate?.let { release ->
                UpdateBanner(
                    text = vm.updateMessage ?: "JPTV ${release.versionName} finns att installera.",
                    busy = vm.updateBusy,
                    onUpdate = { vm.installUpdate() },
                )
                Spacer(Modifier.height(16.dp))
            }

            FocusedInfo(vm, focusedItem)

            val error = vm.homeError
            when {
                error != null && liveNow.isEmpty() -> MessageBox(
                    message = error,
                    buttonText = "Försök igen",
                    onButton = { vm.connectAndLoad(rediscover = true) },
                )
                vm.homeLoading && vm.homeRows.isEmpty() && liveNow.isEmpty() -> MessageBox("Hämtar från ${vm.serverName ?: "Plex"}…")
                vm.homeRows.isEmpty() && liveNow.isEmpty() -> MessageBox(
                    message = "Inget att visa ännu från Plex.",
                    buttonText = "Uppdatera",
                    onButton = { vm.refreshHome() },
                )
                else -> HomeRows(
                    vm = vm,
                    rows = vm.homeRows,
                    liveNow = liveNow,
                    focusKey = if (rememberedExists) rememberedKey else null,
                    focusRequester = itemFocus,
                    onFocused = { key, item ->
                        focusedItem = item
                        vm.focusKeys["home"] = key
                    },
                    onLongPress = { item, inContinue -> menuFor = item to inContinue },
                )
            }
        }

        menuFor?.let { (item, inContinue) ->
            ItemMenu(
                vm = vm,
                item = item,
                inContinueWatching = inContinue,
                onClose = { menuFor = null },
            )
        }
    }
    LaunchedEffect(rememberedExists, vm.homeRows.isNotEmpty()) {
        if (initialFocusDone) return@LaunchedEffect
        // Vänta på raderna om vi har ett kort att komma tillbaka till men de inte hunnit laddas.
        if (!rememberedExists && rememberedKey != null && vm.homeRows.isEmpty() && vm.homeLoading) return@LaunchedEffect
        initialFocusDone = true
        if (rememberedExists) itemFocus.requestWhenReady() else topFocus.requestWhenReady()
    }
}

private const val LIVE_ROW = "live"

private fun homeKey(row: String, id: String) = "$row|$id"

/** Titel och handling för kortet som har fokus, ovanför raderna. */
@Composable
private fun FocusedInfo(vm: AppViewModel, item: PlexItem?) {
    // Logotypen hämtas en kort stund efter att fokus stannat, så att snabb bläddring inte ger en massa anrop.
    var logo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item?.ratingKey) {
        logo = item?.logo
        if (item != null && logo == null && item.showKey != null) {
            kotlinx.coroutines.delay(300)
            logo = vm.showLogo(item)
        }
    }
    Column(
        Modifier
            .height(112.dp)
            .width(820.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (item != null) {
            TitleLogo(
                logoUrl = vm.plex.rawImageUrl(logo),
                title = item.displayTitle,
                maxHeight = 48.dp,
                maxWidth = 320.dp,
                textStyle = MaterialTheme.typography.headlineSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badges(listOfNotNull(vm.imdbText(item)) + item.badges)
                item.subtitle?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(it, color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item.summary?.let { Text(it, color = JptvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun UpdateBanner(text: String, busy: Boolean, onUpdate: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(end = 48.dp)
            .background(JptvColors.Surface, MaterialTheme.shapes.medium)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        Button(onClick = onUpdate, enabled = !busy) {
            Text(if (busy) "Uppdaterar…" else "Uppdatera nu")
        }
    }
}

@Composable
private fun HomeRows(
    vm: AppViewModel,
    rows: List<PlexHomeRow>,
    liveNow: List<Channel>,
    focusKey: String?,
    focusRequester: FocusRequester,
    onFocused: (String, PlexItem?) -> Unit,
    onLongPress: (PlexItem, Boolean) -> Unit,
) {
    LazyColumn(
        state = vm.rememberListState("home"),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        if (liveNow.isNotEmpty()) {
            item(key = "live_now") {
                Column {
                    Text(if (vm.favorites.isEmpty()) "Senaste kanaler" else "Live nu", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        state = vm.rememberListState("home_row_live"),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(end = 48.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        itemsIndexed(liveNow, key = { _, ch -> ch.key }) { i, ch ->
                            val key = homeKey(LIVE_ROW, ch.key)
                            LiveTile(
                                vm = vm,
                                channel = ch,
                                onClick = { vm.playChannel(liveNow, i) },
                                modifier = Modifier
                                    .onFocusChanged { if (it.hasFocus) onFocused(key, null) }
                                    .then(if (key == focusKey) Modifier.focusRequester(focusRequester) else Modifier),
                            )
                        }
                    }
                }
            }
        }
        itemsIndexed(rows, key = { r, row -> rowKey(rows, r) }) { r, row ->
            Column {
                Text(row.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                // Nycklar på id (inte plats), så att fokus följer med när ordningen ändras.
                val items = remember(row) { row.items.distinctBy { it.ratingKey } }
                LazyRow(
                    state = vm.rememberListState("home_row_${rowKey(rows, r)}"),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(end = 48.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.ratingKey }) { _, item ->
                        val key = homeKey(row.title, item.ratingKey)
                        PosterCard(
                            title = item.displayTitle,
                            subtitle = vm.posterSubtitle(item),
                            imageUrl = vm.plex.imageUrl(item.thumb),
                            progress = item.progress,
                            watched = item.watched,
                            onClick = { vm.select(item) },
                            onLongClick = { onLongPress(item, row.title == CONTINUE_ROW) },
                            modifier = Modifier
                                .onFocusChanged { if (it.hasFocus) onFocused(key, item) }
                                .then(if (key == focusKey) Modifier.focusRequester(focusRequester) else Modifier),
                        )
                    }
                }
            }
        }
    }
}

/** Radens nyckel: namnet, med löpnummer om två rader skulle heta likadant. */
private fun rowKey(rows: List<PlexHomeRow>, index: Int): String {
    val title = rows[index].title
    val before = (0 until index).count { rows[it].title == title }
    return if (before == 0) title else "$title#$before"
}

/** Kanalruta på hemskärmen: logga, namn och vad som går just nu. */
@Composable
private fun LiveTile(vm: AppViewModel, channel: Channel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val current = vm.epg?.now(channel, now)
    Card(onClick = onClick, modifier = modifier.width(300.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(JptvColors.Surface)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel, size = 40)
                Spacer(Modifier.width(10.dp))
                Text("${channel.number} ${channel.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                current?.title ?: "Ingen guide",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (current != null) JptvColors.Text else JptvColors.TextDim,
            )
            Spacer(Modifier.height(6.dp))
            if (current != null) {
                ProgressBar(current.progress(now), Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("${clock(current.start)}–${clock(current.stop)}", color = JptvColors.TextDim, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Meny när man håller in OK på ett kort. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ItemMenu(vm: AppViewModel, item: PlexItem, inContinueWatching: Boolean, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            // Menyn öppnas medan OK fortfarande hålls inne; släppet ska inte välja första knappen.
            .ignoreUnpairedOk(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                // Fokus stannar i menyn tills den stängs.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .width(520.dp)
                .background(JptvColors.Surface, MaterialTheme.shapes.large)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(item.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    onClose()
                    vm.select(item)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(first),
            ) {
                Text(if (item.isPlayable) "Visa detaljer" else "Öppna")
            }
            if (item.isPlayable) {
                OutlinedButton(onClick = {
                    onClose()
                    vm.play(item)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (item.viewOffsetMs > 0) "Fortsätt spela" else "Spela")
                }
                OutlinedButton(onClick = {
                    onClose()
                    vm.setWatched(item, !item.watched)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (item.watched) "Markera som osedd" else "Markera som sedd")
                }
            }
            if (inContinueWatching) {
                OutlinedButton(onClick = {
                    onClose()
                    vm.removeFromContinueWatching(item)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ta bort från Fortsätt titta")
                }
            }
            item.showContainer()?.let { show ->
                OutlinedButton(onClick = {
                    onClose()
                    vm.open(Screen.Episodes(show))
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Gå till serien")
                }
            }
        }
    }
    LaunchedEffect(Unit) { first.requestWhenReady() }
}
