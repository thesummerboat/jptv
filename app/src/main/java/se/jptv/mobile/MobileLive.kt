package se.jptv.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.iptv.Channel
import se.jptv.ui.clock

/** Live-TV på telefonen: grupper överst, kanaler med vad som går nu, och dagens program per kanal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileLive(vm: AppViewModel) {
    if (vm.playlist == null) {
        if (vm.iptvRestoring) {
            Centered("Laddar kanalerna…")
        } else {
            Centered("Ingen IPTV-spellista ännu. Lägg till den under Inställningar.", "Inställningar") { vm.open(Screen.Settings) }
        }
        return
    }
    val groups = vm.groupsForUi()
    var group by remember {
        mutableStateOf(
            vm.focusKeys["mobile_live_group"]?.takeIf { it in groups }
                ?: groups.firstOrNull { it != AppViewModel.GROUP_SEARCH }
                ?: AppViewModel.GROUP_SEARCH,
        )
    }
    var query by remember { mutableStateOf("") }
    val channels = remember(group, query, vm.playlist, vm.favorites, vm.recents) { vm.channelsForGroup(group, query) }
    var guideFor by remember { mutableStateOf<Channel?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it }) { g ->
                FilterChip(
                    selected = g == group,
                    onClick = {
                        group = g
                        vm.focusKeys["mobile_live_group"] = g
                    },
                    label = { Text(g, maxLines = 1) },
                )
            }
        }
        if (group == AppViewModel.GROUP_SEARCH) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Kanalnamn") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (channels.isEmpty()) {
            Centered(if (group == AppViewModel.GROUP_SEARCH) "Skriv en del av kanalnamnet." else "Inga kanaler här.")
        } else {
            Text(
                "Tryck = titta · håll = favorit · kalendern = dagens program",
                color = Dim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(channels, key = { i, ch -> "${i}_${ch.key}" }) { i, ch ->
                    ChannelListRow(
                        vm = vm,
                        channel = ch,
                        now = now,
                        onClick = { vm.playChannel(channels, i) },
                        onLongClick = { vm.toggleFavorite(ch) },
                        onGuide = { guideFor = ch },
                    )
                }
            }
        }
    }

    guideFor?.let { ch ->
        ModalBottomSheet(onDismissRequest = { guideFor = null }, containerColor = Card) {
            ChannelGuide(
                vm = vm,
                channel = ch,
                onPlay = {
                    guideFor = null
                    val i = channels.indexOfFirst { it.key == ch.key }
                    if (i >= 0) vm.playChannel(channels, i) else vm.playChannel(listOf(ch), 0)
                },
            )
        }
    }
}

/** En kanal i listan: logga, namn, vad som går nu med förlopp, och vad som kommer sedan. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelListRow(
    vm: AppViewModel,
    channel: Channel,
    now: Long = System.currentTimeMillis(),
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onGuide: (() -> Unit)? = null,
) {
    val current = vm.epg?.now(channel, now)
    val next = vm.epg?.next(channel, now)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogoSmall(channel, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${channel.number}  ${channel.name}" + if (vm.isFavorite(channel)) "  ★" else "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current != null) {
                Text(
                    "${clock(current.start)}–${clock(current.stop)}  ${current.title}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(progress = { current.progress(now) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Accent)
            }
            next?.let {
                Text("Sedan ${clock(it.start)} ${it.title}", color = Dim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onGuide != null) {
            IconButton(onClick = onGuide) { Icon(Icons.Filled.DateRange, contentDescription = "Dagens program") }
        }
    }
}

/** Kanalens program från nu och ett dygn framåt; 🔔 ger en påminnelse när programmet börjar. */
@Composable
private fun ChannelGuide(vm: AppViewModel, channel: Channel, onPlay: () -> Unit) {
    val now = System.currentTimeMillis()
    val programmes = remember(channel.key, vm.epg) {
        vm.epg?.programmesFor(channel)?.filter { it.stop > now && it.start < now + 24 * 60 * 60_000L } ?: emptyList()
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogoSmall(channel, 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(channel.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = onPlay) { Text("▶ Titta") }
        }
        Spacer(Modifier.height(8.dp))
        if (programmes.isEmpty()) {
            Text("Ingen guide för kanalen.", color = Dim, modifier = Modifier.padding(16.dp))
        }
        LazyColumn {
            itemsIndexed(programmes, key = { i, p -> "${i}_${p.start}" }) { _, p ->
                val live = now in p.start until p.stop
                val reminded = vm.hasReminder(channel, p.start)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (live) Modifier.background(CardHigh) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(clock(p.start), color = if (live) Accent else Dim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        p.desc?.let { Text(it, color = Dim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    if (p.start > now) {
                        TextButton(onClick = { vm.toggleReminder(channel, p) }) { Text(if (reminded) "🔔" else "🔕") }
                    }
                }
            }
        }
    }
}
