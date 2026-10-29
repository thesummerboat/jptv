@file:OptIn(ExperimentalTvMaterial3Api::class)

package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.iptv.Channel
import se.jptv.iptv.Epg
import se.jptv.iptv.ProgrammeHit
import se.jptv.iptv.ProgrammeSearch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clockFormat = SimpleDateFormat("HH:mm", Locale("sv", "SE"))

fun clock(ms: Long): String = clockFormat.format(Date(ms))

@Composable
fun LiveTvScreen(vm: AppViewModel) {
    val playlist = vm.playlist
    if (playlist == null && vm.iptvRestoring) {
        MessageBox("Laddar kanalerna…")
    } else if (playlist == null || playlist.channels.isEmpty()) {
        MessageBox(
            message = "Ingen IPTV-spellista ännu. Lägg till adressen till din M3U-lista i Inställningar.",
            buttonText = "Öppna Inställningar",
            onButton = { vm.open(Screen.Settings) },
        )
    } else {
        ChannelBrowser(vm)
    }
}

@Composable
private fun ChannelBrowser(vm: AppViewModel) {
    val groups = vm.groupsForUi()
    // Tillbaka från spelaren: kanalen du tittade på sist (även om du zappat till en annan grupp).
    val rememberedChannel = remember { vm.focusKeys["live_channel"] }
    val startGroup = remember {
        val rememberedGroup = vm.focusKeys["live_group"]?.takeIf { it in groups }
        val channelGroup = rememberedChannel?.let { vm.playlist?.byKey?.get(it)?.group }?.takeIf { it in groups }
        when {
            rememberedGroup != null && rememberedChannel != null &&
                vm.channelsForGroup(rememberedGroup).any { it.key == rememberedChannel } -> rememberedGroup
            channelGroup != null -> channelGroup
            rememberedGroup != null -> rememberedGroup
            else -> vm.lastChannel?.group?.takeIf { it in groups }
                ?: groups.firstOrNull { it != AppViewModel.GROUP_SEARCH }
                ?: AppViewModel.GROUP_SEARCH
        }
    }
    var group by remember { mutableStateOf(startGroup) }
    var query by remember { mutableStateOf("") }
    val channels = remember(group, query, vm.playlist, vm.favorites, vm.recents) { vm.channelsForGroup(group, query) }
    val groupFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val startChannelIndex = remember { channels.indexOfFirst { it.key == rememberedChannel } }
    val groupsList = vm.rememberListState("live_groups")
    val channelList = vm.rememberListState("live_ch_$group")
    val scope = rememberCoroutineScope()

    /** Fokus till den valda gruppen, även om den är utanför skärmen. */
    fun focusSelectedGroup() {
        scope.launch {
            val gi = groups.indexOf(group)
            if (gi >= 0 && groupsList.layoutInfo.visibleItemsInfo.none { it.index == gi }) {
                groupsList.scrollToItem((gi - 3).coerceAtLeast(0))
            }
            groupFocus.requestWhenReady()
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // Sökläget: kanaler med namnet och program i guiden med titeln ("hockey", "Rapport").
    val searching = group == AppViewModel.GROUP_SEARCH
    var programmeHits by remember { mutableStateOf<List<ProgrammeHit>>(emptyList()) }
    var searchPending by remember { mutableStateOf(false) }
    LaunchedEffect(searching, query, vm.epg, vm.hiddenGroups) {
        if (!searching || query.trim().length < ProgrammeSearch.MIN_LENGTH) {
            programmeHits = emptyList()
            searchPending = false
            return@LaunchedEffect
        }
        searchPending = true
        delay(250)
        programmeHits = vm.searchProgrammes(query)
        searchPending = false
    }
    // Många kanalträffar ("sv") ska inte trycka ner programmen för långt.
    val shownChannels = if (searching) channels.take(SEARCH_CHANNELS) else channels

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Live-TV", "${vm.playlist?.channels?.size ?: 0} kanaler")
            Spacer(Modifier.weight(1f))
            Text(
                vm.epgStatus?.takeIf { vm.epgLoading }
                    ?: "Håll OK på en kanal = favorit · på en grupp = dölj",
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(16.dp))
            Button(onClick = { vm.open(Screen.Guide(group)) }) { Text("Guide") }
            Spacer(Modifier.width(16.dp))
            Clock()
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                state = groupsList,
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                items(groups, key = { it }) { g ->
                    ListItem(
                        selected = g == group,
                        onClick = { group = g },
                        onLongClick = {
                            val index = groups.indexOf(g)
                            vm.hideGroup(g)
                            if (g == group) {
                                // Gruppen försvann: välj grannen och behåll fokus i listan.
                                val left = vm.groupsForUi()
                                group = left.getOrNull(index) ?: left.lastOrNull() ?: AppViewModel.GROUP_SEARCH
                                vm.focusKeys["live_group"] = group
                                focusSelectedGroup()
                            }
                        },
                        modifier = Modifier
                            .onFocusChanged {
                                if (it.isFocused) {
                                    group = g
                                    vm.focusKeys["live_group"] = g
                                }
                            }
                            .then(if (g == group) Modifier.focusRequester(groupFocus) else Modifier),
                        headlineContent = { Text(g, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                if (searching) {
                    SearchField(query = query, onQuery = { query = it })
                    Spacer(Modifier.height(12.dp))
                }
                val empty = when {
                    searching && query.isBlank() -> "Skriv en del av ett kanalnamn eller ett program, t.ex. \"hockey\" eller \"Rapport\"."
                    searching && channels.isEmpty() && programmeHits.isEmpty() && !searchPending -> "Inget hittades för \"${query.trim()}\"."
                    !searching && channels.isEmpty() -> "Inga kanaler här."
                    else -> null
                }
                if (empty != null) Text(empty, color = JptvColors.TextDim)
                LazyColumn(
                    state = channelList,
                    modifier = Modifier
                        .fillMaxHeight()
                        // ◀ från en kanal: tillbaka till den valda gruppen, inte den som råkar ligga bredvid.
                        .onPreviewKeyEvent { ev ->
                            if (ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                if (ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) focusSelectedGroup()
                                true
                            } else {
                                false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    if (searching && shownChannels.isNotEmpty() && programmeHits.isNotEmpty()) {
                        item(key = "h_channels") {
                            SectionLabel(
                                "Kanaler",
                                if (channels.size > shownChannels.size) "${channels.size} träffar – skriv mer för att se färre" else null,
                            )
                        }
                    }
                    itemsIndexed(shownChannels, key = { i, ch -> "${i}_${ch.key}" }) { i, ch ->
                        ChannelRow(
                            modifier = Modifier
                                .onFocusChanged { if (it.hasFocus) vm.focusKeys["live_channel"] = ch.key }
                                .then(
                                    if (group == startGroup && i == startChannelIndex) Modifier.focusRequester(channelFocus) else Modifier,
                                ),
                            channel = ch,
                            epg = vm.epg,
                            now = now,
                            favorite = vm.isFavorite(ch),
                            onClick = { vm.playChannel(channels, i) },
                            onLongClick = { vm.toggleFavorite(ch) },
                            playing = vm.pip?.channel?.key == ch.key,
                            compact = vm.pip != null,
                        )
                    }
                    if (programmeHits.isNotEmpty()) {
                        item(key = "h_programmes") { SectionLabel("Program", "OK = titta om det går nu, annars påminnelse") }
                        itemsIndexed(programmeHits, key = { i, hit -> "p_${i}_${hit.channel.key}_${hit.programme.start}" }) { _, hit ->
                            ProgrammeRow(
                                hit = hit,
                                now = now,
                                reminded = vm.hasReminder(hit.channel, hit.programme.start),
                                onClick = {
                                    if (hit.isWatchable(System.currentTimeMillis())) {
                                        vm.playChannelInGroup(hit.channel)
                                    } else {
                                        vm.toggleReminder(hit.channel, hit.programme)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            // Bild-i-bild: kanalen som spelar, med vad som går just nu.
            vm.pip?.let { session ->
                Spacer(Modifier.width(24.dp))
                Column(Modifier.width(256.dp)) {
                    se.jptv.player.PipSlot(vm, Modifier.size(256.dp, 144.dp))
                    Spacer(Modifier.height(10.dp))
                    val ch = session.channel
                    Text("${ch.number}  ${ch.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    vm.epg?.now(ch, now)?.let { p ->
                        Text(
                            "${clock(p.start)}–${clock(p.stop)}  ${p.title}",
                            color = JptvColors.TextDim,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "OK på en annan kanal byter här. OK igen (eller ▶‖) = helskärm. Håll inne Tillbaka = stäng rutan.",
                        color = JptvColors.TextDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (startChannelIndex >= 0) {
            if (channelList.layoutInfo.visibleItemsInfo.none { it.index == startChannelIndex }) {
                channelList.scrollToItem((startChannelIndex - 3).coerceAtLeast(0))
            }
            channelFocus.requestWhenReady()
        } else {
            focusSelectedGroup()
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = TextStyle(color = JptvColors.Text, fontSize = 20.sp),
        cursorBrush = SolidColor(JptvColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) JptvColors.Accent else JptvColors.SurfaceHigh, MaterialTheme.shapes.small)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) Text("Sök kanal eller program…", color = JptvColors.TextDim, fontSize = 20.sp)
                inner()
            }
        },
    )
}

/** Högst så många kanaler i sökläget; programmen kommer under. */
private const val SEARCH_CHANNELS = 30

@Composable
private fun SectionLabel(title: String, hint: String? = null) {
    Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint != null) {
            Spacer(Modifier.width(12.dp))
            Text(hint, color = JptvColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Ett program i sökträffarna: titel, när och var. Går det nu: förlopp och ▶ Titta; annars 🔔. */
@Composable
private fun ProgrammeRow(hit: ProgrammeHit, now: Long, reminded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = hit.programme
    val live = hit.isLive(now)
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        leadingContent = { ChannelLogo(hit.channel) },
        headlineContent = {
            Text(p.title + if (reminded) "  🔔" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    "${ProgrammeSearch.whenText(p, now)} · ${hit.channel.name}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (live) JptvColors.Accent else JptvColors.TextDim,
                )
                if (live) {
                    Spacer(Modifier.height(4.dp))
                    ProgressBar(p.progress(now), Modifier.width(220.dp))
                }
            }
        },
        trailingContent = {
            Text(
                when {
                    hit.isWatchable(now) -> "▶ Titta"
                    reminded -> "🔔 Påminner"
                    else -> "OK = påminn"
                },
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}

@Composable
fun ChannelRow(
    channel: Channel,
    epg: Epg?,
    now: Long,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Kanalen spelar i bild-i-bild-rutan. */
    playing: Boolean = false,
    /** Smalare rad (bild-i-bild tar plats till höger): utan "sedan". */
    compact: Boolean = false,
) {
    val current = epg?.now(channel, now)
    val next = epg?.next(channel, now)?.takeIf { !compact }
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        leadingContent = { ChannelLogo(channel) },
        headlineContent = {
            Text(
                (if (playing) "▶ " else "") + "${channel.number}  ${channel.name}${if (favorite) "  ★" else ""}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) JptvColors.Accent else androidx.compose.ui.graphics.Color.Unspecified,
            )
        },
        supportingContent = if (current != null) {
            {
                Column {
                    Text(
                        "${clock(current.start)}–${clock(current.stop)}  ${current.title}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = JptvColors.TextDim,
                    )
                    Spacer(Modifier.height(4.dp))
                    ProgressBar(current.progress(now), Modifier.width(220.dp))
                }
            }
        } else null,
        trailingContent = if (next != null) {
            {
                Text(
                    "${clock(next.start)} ${next.title}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = JptvColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(260.dp),
                )
            }
        } else null,
    )
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .background(JptvColors.SurfaceHigh, MaterialTheme.shapes.extraSmall),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(JptvColors.Accent, MaterialTheme.shapes.extraSmall),
        )
    }
}

@Composable
fun ChannelLogo(channel: Channel, size: Int = 44) {
    Box(
        Modifier
            .size(size.dp)
            .background(JptvColors.SurfaceHigh, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = channel.name.take(2).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = JptvColors.TextDim,
        )
        if (channel.logo != null) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(size.dp)
                    .padding(4.dp),
            )
        }
    }
}

/** Knapp på hemskärmen: hoppa rakt in i senaste kanalen. */
@Composable
fun LastChannelButton(vm: AppViewModel) {
    val ch = vm.lastChannel ?: return
    OutlinedButton(onClick = { vm.playLastChannel() }) {
        Text("▶ ${ch.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
