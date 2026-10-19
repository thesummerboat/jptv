@file:OptIn(ExperimentalTvMaterial3Api::class)

package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.iptv.Channel
import se.jptv.iptv.Playlist

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
        ChannelBrowser(vm, playlist)
    }
}

@Composable
private fun ChannelBrowser(vm: AppViewModel, playlist: Playlist) {
    val lastUrl = remember { vm.prefs.lastChannelUrl }
    val startGroup = remember(playlist) {
        playlist.channels.firstOrNull { it.url == lastUrl }?.group ?: playlist.groups.first()
    }
    var group by remember(playlist) { mutableStateOf(startGroup) }
    val channels = remember(playlist, group) { playlist.channels.filter { it.group == group } }
    val groupFocus = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        ScreenTitle("Live-TV", "${playlist.channels.size} kanaler")
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxSize()) {
            // Vänster: grupper. Fokus på en grupp visar dess kanaler direkt.
            LazyColumn(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                items(playlist.groups, key = { it }) { g ->
                    ListItem(
                        selected = g == group,
                        onClick = { group = g },
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) group = g }
                            .then(if (g == startGroup) Modifier.focusRequester(groupFocus) else Modifier),
                        headlineContent = { Text(g, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            // Höger: kanaler i vald grupp.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                itemsIndexed(channels, key = { i, ch -> "${i}_${ch.url}" }) { i, ch ->
                    ChannelRow(
                        number = i + 1,
                        channel = ch,
                        isLast = ch.url == lastUrl,
                        onClick = { vm.playChannel(channels, i) },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { groupFocus.requestFocus() } }
}

@Composable
private fun ChannelRow(number: Int, channel: Channel, isLast: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        leadingContent = { ChannelLogo(channel) },
        headlineContent = {
            Text("$number  ${channel.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            if (isLast) Text("Senast", color = JptvColors.Accent, style = MaterialTheme.typography.labelLarge)
        },
    )
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
