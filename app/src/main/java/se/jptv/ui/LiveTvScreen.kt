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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.iptv.Channel
import se.jptv.iptv.Epg
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clockFormat = SimpleDateFormat("HH:mm", Locale("sv", "SE"))

fun clock(ms: Long): String = clockFormat.format(Date(ms))

@Composable
fun LiveTvScreen(vm: AppViewModel) {
    val playlist = vm.playlist
    if (playlist == null || playlist.channels.isEmpty()) {
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
    val remembered = vm.focusKeys["live_group"]
    val startGroup = remember {
        remembered?.takeIf { it in groups }
            ?: vm.lastChannel?.group?.takeIf { it in groups }
            ?: groups.firstOrNull { it != AppViewModel.GROUP_SEARCH }
            ?: AppViewModel.GROUP_SEARCH
    }
    var group by remember { mutableStateOf(startGroup) }
    var query by remember { mutableStateOf("") }
    val channels = remember(group, query, vm.playlist, vm.favorites, vm.recents) { vm.channelsForGroup(group, query) }
    val groupFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    // Tillbaka från spelaren: fokus på kanalen du tittade på, i samma grupp.
    val rememberedChannel = remember { vm.focusKeys["live_channel"] }
    val startChannelIndex = remember { channels.indexOfFirst { it.key == rememberedChannel } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Live-TV", "${vm.playlist?.channels?.size ?: 0} kanaler")
            Spacer(Modifier.weight(1f))
            Text(
                vm.epgStatus?.takeIf { vm.epgLoading } ?: "Håll inne OK på en kanal för att spara som favorit",
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(16.dp))
            Button(onClick = { vm.open(Screen.Guide(group)) }) { Text("Guide") }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                state = vm.listState("live_groups"),
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
                        modifier = Modifier
                            .onFocusChanged {
                                if (it.isFocused) {
                                    group = g
                                    vm.focusKeys["live_group"] = g
                                }
                            }
                            .then(if (g == startGroup) Modifier.focusRequester(groupFocus) else Modifier),
                        headlineContent = { Text(g, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                if (group == AppViewModel.GROUP_SEARCH) {
                    SearchField(query = query, onQuery = { query = it })
                    Spacer(Modifier.height(12.dp))
                }
                if (channels.isEmpty()) {
                    Text(
                        if (group == AppViewModel.GROUP_SEARCH) "Skriv en del av kanalnamnet." else "Inga kanaler här.",
                        color = JptvColors.TextDim,
                    )
                }
                LazyColumn(
                    state = vm.listState("live_ch_$group"),
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    itemsIndexed(channels, key = { i, ch -> "${i}_${ch.key}" }) { i, ch ->
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
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (startChannelIndex >= 0) {
            val list = vm.listState("live_ch_$startGroup")
            if (list.layoutInfo.visibleItemsInfo.none { it.index == startChannelIndex }) {
                list.scrollToItem((startChannelIndex - 3).coerceAtLeast(0))
            }
            channelFocus.requestWhenReady()
        } else {
            val groupsList = vm.listState("live_groups")
            val gi = groups.indexOf(startGroup)
            if (gi >= 0 && groupsList.layoutInfo.visibleItemsInfo.none { it.index == gi }) {
                groupsList.scrollToItem((gi - 3).coerceAtLeast(0))
            }
            groupFocus.requestWhenReady()
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
                if (query.isEmpty()) Text("Sök kanal…", color = JptvColors.TextDim, fontSize = 20.sp)
                inner()
            }
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
) {
    val current = epg?.now(channel, now)
    val next = epg?.next(channel, now)
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        leadingContent = { ChannelLogo(channel) },
        headlineContent = {
            Text(
                "${channel.number}  ${channel.name}${if (favorite) "  ★" else ""}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
