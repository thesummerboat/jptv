@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
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
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    /** Radens etikett för kortet med fokus (t.ex. "3 nya avsnitt"); affischerna har ingen text. */
    var focusedLabel by remember { mutableStateOf<String?>(null) }
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

    // Trailer i bakgrunden: bilden tonas bort när trailern har börjat visas.
    val trailer = remember { TrailerState() }
    val imageAlpha by animateFloatAsState(
        targetValue = if (trailer.showing) 0f else 1f,
        animationSpec = tween(if (trailer.showing) 900 else 300),
        label = "trailer",
    )

    Box(Modifier.fillMaxSize()) {
        Backdrop(
            url = vm.plex.imageUrl(focusedItem?.art, 1280, 720),
            imageAlpha = imageAlpha,
            // Bakgrunden syns ända ner till raderna och fritt till höger; mörkt bara bakom texten.
            fadeFrom = 0.55f,
            leftScrimOnly = true,
            video = { HeroTrailer(vm, focusedItem, enabled = menuFor == null, state = trailer) },
        )
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
                vm.visibleSections.forEach { section ->
                    OutlinedButton(onClick = { vm.open(Screen.Section(section)) }) {
                        Text(if (section.key in vm.favoriteSections) "★ ${section.title}" else section.title)
                    }
                }
                OutlinedButton(onClick = { vm.open(Screen.Search) }) { Text("Sök") }
                OutlinedButton(onClick = { vm.open(Screen.Settings) }) { Text("Inställningar") }
                Spacer(Modifier.weight(1f))
                Clock()
            }
            Spacer(Modifier.height(12.dp))

            vm.availableUpdate?.let { release ->
                UpdateBanner(
                    text = vm.updateMessage ?: "JPTV ${release.versionName} finns att installera.",
                    busy = vm.updateBusy,
                    onUpdate = { vm.installUpdate() },
                )
                Spacer(Modifier.height(16.dp))
            }

            Row {
                Box(Modifier.weight(1f)) { FocusedInfo(vm, focusedItem, focusedChannel, focusedLabel) }
                // Bild-i-bild: kanalen i den stora rutans högra hörn.
                if (vm.pip != null) {
                    Spacer(Modifier.width(16.dp))
                    se.jptv.player.PipSlot(vm, Modifier.padding(top = 8.dp, end = 48.dp).size(256.dp, 144.dp))
                }
            }

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
                    onFocused = { key, item, label ->
                        focusedItem = item
                        focusedLabel = label
                        if (item != null) focusedChannel = null
                        vm.focusKeys["home"] = key
                    },
                    onChannelFocused = { focusedChannel = it },
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

/** Stor ruta överst: logotyp, märken och handling för kortet som har fokus (eller kanalen). */
@Composable
private fun FocusedInfo(vm: AppViewModel, item: PlexItem?, channel: Channel?, label: String?) {
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
            .height(HERO_HEIGHT)
            .width(680.dp)
            .clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        when {
            item != null -> {
                TitleLogo(
                    logoUrl = vm.plex.rawImageUrl(logo),
                    title = item.displayTitle,
                    maxHeight = 80.dp,
                    maxWidth = 380.dp,
                    textStyle = MaterialTheme.typography.headlineMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    label?.let {
                        Text(it, color = JptvColors.Accent, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Spacer(Modifier.width(12.dp))
                    }
                    Badges(listOfNotNull(vm.imdbText(item)) + item.badges)
                    val meta = listOfNotNull(
                        item.subtitle,
                        item.genres.take(2).joinToString(", ").ifEmpty { null },
                        if (item.durationMs > 0 && item.type != "show") formatDuration(item.durationMs) else null,
                    ).joinToString("  ·  ")
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            meta,
                            color = JptvColors.Text.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge.copy(shadow = HeroShadow),
                        )
                    }
                }
                item.summary?.let {
                    // Smal spalt med skugga, så att den går att läsa även över ljusa bilder.
                    Text(
                        it,
                        color = JptvColors.Text.copy(alpha = 0.9f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge.copy(shadow = HeroShadow),
                        modifier = Modifier.width(440.dp),
                    )
                }
            }
            channel != null -> {
                val now = System.currentTimeMillis()
                val current = vm.epg?.now(channel, now)
                val next = vm.epg?.next(channel, now)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(channel, size = 56)
                    Spacer(Modifier.width(14.dp))
                    Text("${channel.number}  ${channel.name}", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (current != null) {
                    Text(
                        "${clock(current.start)}–${clock(current.stop)}  ${current.title}",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ProgressBar(current.progress(now), Modifier.width(360.dp))
                    current.desc?.let { Text(it, color = JptvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                next?.let { Text("Sedan ${clock(it.start)}  ${it.title}", color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

private val HERO_HEIGHT = 250.dp

/** Skuggan bakom texten i den stora rutan (samma som på detaljsidan). */
private val HeroShadow = TextShadow
private val HOME_POSTER = 64.dp

/** Utrymme vid radens kant för det förstorade kortet med fokus. */
private val EdgeRoom = 12.dp

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
    onFocused: (String, PlexItem?, String?) -> Unit,
    onChannelFocused: (Channel) -> Unit,
    onLongPress: (PlexItem, Boolean) -> Unit,
) {
    val listState = vm.rememberListState("home")
    val scope = rememberCoroutineScope()
    // Raderna (vågrätt) scrollar som vanligt; listan (lodrätt) styrs bara av pin() nedan.
    val rowSpec = LocalBringIntoViewSpec.current
    // Raden med fokus läggs alltid överst, så att raden ovanför inte syns halvt bakom texten.
    fun pin(index: Int): Modifier = Modifier.onFocusChanged {
        if (it.hasFocus) scope.launch { listState.animateScrollToItem(index) }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .padding(top = 8.dp)
            // Håller man inne ▲/▼ hinner fokus annars hoppa medan raden glider på plats, och det fastnar
            // mellan två rader. Nästa rad tas först när förflyttningen är klar (ungefär en rad per tryck-repetition).
            .onPreviewKeyEvent { ev ->
                val code = ev.nativeKeyEvent.keyCode
                (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN || code == android.view.KeyEvent.KEYCODE_DPAD_UP) &&
                    ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                    listState.isScrollInProgress
            },
        // Varje rad är lika hög som listan: bara raden med fokus syns, ingen titt på raden under.
        verticalArrangement = Arrangement.spacedBy(0.dp),
        // Raderna fyller höjden, så även den sista kan läggas överst utan extra utrymme.
        contentPadding = PaddingValues(bottom = 0.dp),
    ) {
        // Radernas plats i listan räknas om varje gång listan byggs.
        var index = 0
        if (liveNow.isNotEmpty()) {
            val pinAt = index++
            item(key = "live_now") {
                Column(pin(pinAt).fillParentMaxHeight()) {
                    Text(if (vm.favorites.isEmpty()) "Senaste kanaler" else "Live nu", style = MaterialTheme.typography.titleMedium.copy(shadow = HeroShadow))
                    Spacer(Modifier.height(8.dp))
                    CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                    LazyRow(
                        state = vm.rememberListState("home_row_live"),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        // Luft runt raden så att kortet med fokus (förstorat, med ram) inte klipps i kanten;
                        // raden flyttas lika mycket åt vänster så att den står kvar i linje med texten.
                        contentPadding = PaddingValues(start = EdgeRoom, end = 48.dp, top = 10.dp, bottom = 10.dp),
                        modifier = Modifier.offset(x = -EdgeRoom),
                    ) {
                        itemsIndexed(liveNow, key = { _, ch -> ch.key }) { i, ch ->
                            val key = homeKey(LIVE_ROW, ch.key)
                            LiveTile(
                                vm = vm,
                                channel = ch,
                                onClick = { vm.playChannel(liveNow, i) },
                                modifier = Modifier
                                    .onFocusChanged {
                                        if (it.hasFocus) {
                                            onFocused(key, null, null)
                                            onChannelFocused(ch)
                                        }
                                    }
                                    .then(if (key == focusKey) Modifier.focusRequester(focusRequester) else Modifier),
                            )
                        }
                    }
                    }
                }
            }
        }
        val lead = vm.homeLeadRows.coerceAtMost(rows.size)
        val leadStart = index
        index += lead
        itemsIndexed(rows.take(lead), key = { r, _ -> rowKey(rows, r) }) { r, row ->
            HomeRow(vm, rows, r, row, focusKey, focusRequester, onFocused, onLongPress, rowSpec, pin(leadStart + r).fillParentMaxHeight())
        }
        val restStart = index
        itemsIndexed(rows.drop(lead), key = { r, _ -> rowKey(rows, r + lead) }) { r, row ->
            HomeRow(vm, rows, r + lead, row, focusKey, focusRequester, onFocused, onLongPress, rowSpec, pin(restStart + r).fillParentMaxHeight())
        }
    }
    }
}

/** Listan scrollar inte av sig själv när fokus flyttas; pin() lägger raden med fokus överst i stället. */
private object NoAutoScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** En rad med affischer på hemskärmen. */
@Composable
private fun HomeRow(
    vm: AppViewModel,
    rows: List<PlexHomeRow>,
    r: Int,
    row: PlexHomeRow,
    focusKey: String?,
    focusRequester: FocusRequester,
    onFocused: (String, PlexItem?, String?) -> Unit,
    onLongPress: (PlexItem, Boolean) -> Unit,
    rowSpec: BringIntoViewSpec,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
            Column(modifier) {
                Text(row.title, style = MaterialTheme.typography.titleMedium.copy(shadow = HeroShadow))
                Spacer(Modifier.height(8.dp))
                // Nycklar på id (inte plats), så att fokus följer med när ordningen ändras.
                val items = remember(row) { row.items.distinctBy { it.ratingKey } }
                LazyRow(
                    state = vm.rememberListState("home_row_${rowKey(rows, r)}"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    // Luft så att affischen med fokus inte klipps i vänsterkanten (se raden ovan).
                    contentPadding = PaddingValues(start = EdgeRoom, end = 48.dp, top = 10.dp, bottom = 10.dp),
                    modifier = Modifier.offset(x = -EdgeRoom),
                ) {
                    itemsIndexed(items, key = { _, item -> item.ratingKey }) { _, item ->
                        val key = homeKey(row.title, item.ratingKey)
                        val label = row.labels[item.ratingKey]
                        PosterCard(
                            title = item.displayTitle,
                            subtitle = null,
                            imageUrl = vm.plex.imageUrl(item.thumb, 160, 240),
                            progress = item.progress,
                            watched = item.watched,
                            onClick = { vm.select(item) },
                            onLongClick = { onLongPress(item, row.title == CONTINUE_ROW) },
                            width = HOME_POSTER,
                            // Små affischer utan text: titeln och allt annat står i den stora rutan.
                            showText = false,
                            modifier = Modifier
                                .onFocusChanged { if (it.hasFocus) onFocused(key, item, label) }
                                .then(if (key == focusKey) Modifier.focusRequester(focusRequester) else Modifier),
                        )
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
    Card(onClick = onClick, modifier = modifier.width(240.dp)) {
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
            val shuffleShow = if (item.type == "show") item else item.showContainer()
            if (shuffleShow != null) {
                OutlinedButton(onClick = {
                    onClose()
                    vm.playShuffle(shuffleShow)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Spela slumpvis")
                }
            }
            OutlinedButton(onClick = {
                onClose()
                vm.markNotInterested(item)
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (item.type == "episode") "Inte intresserad av serien" else "Inte intresserad")
            }
        }
    }
    LaunchedEffect(Unit) { first.requestWhenReady() }
}
