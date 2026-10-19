package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import se.jptv.AppViewModel
import se.jptv.iptv.Channel
import se.jptv.iptv.Programme

private const val MINUTE = 60_000L
private const val WINDOW_MINUTES = 180
private const val HALF_HOUR = 30 * MINUTE

/** TV-guide som rutnät: kanaler nedåt, tid åt höger. OK på ett program startar kanalen. */
@Composable
fun GuideScreen(vm: AppViewModel, group: String) {
    val channels = remember(group, vm.playlist, vm.hiddenGroups) { vm.channelsForGuide(group) }
    val epg = vm.epg
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var windowStart by remember { mutableLongStateOf(now - now % HALF_HOUR) }
    var focusedText by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    // Tillbaka från spelaren: samma kanal som sist, inte översta raden.
    val memoryKey = "guide_$group"
    val focusIndex = remember(channels) {
        channels.indexOfFirst { it.key == vm.focusKeys[memoryKey] }.coerceAtLeast(0)
    }
    val listState = vm.rememberListState(memoryKey)

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
        Row {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScreenTitle("Guide", group)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { windowStart -= 2 * 60 * MINUTE }) { Text("◀ Tidigare") }
                    OutlinedButton(onClick = { windowStart = now - now % HALF_HOUR }) { Text("Nu") }
                    OutlinedButton(onClick = { windowStart += 2 * 60 * MINUTE }) { Text("Senare ▶") }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    focusedText ?: if (epg == null) {
                        "Ingen guide laddad. Lägg till guide-adressen (XMLTV) i Inställningar."
                    } else if (vm.pip != null) {
                        "OK byter kanal i rutan. OK igen på samma kanal (eller ▶‖) = helskärm."
                    } else {
                        "Välj ett program och tryck OK för att titta."
                    },
                    color = JptvColors.TextDim,
                    maxLines = if (vm.pip != null) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(if (vm.pip != null) 64.dp else 44.dp),
                )
            }
            // Bild-i-bild: kanalen som spelar, uppe till höger som i TiviMate.
            if (vm.pip != null) {
                Spacer(Modifier.width(16.dp))
                se.jptv.player.PipSlot(vm, Modifier.size(208.dp, 117.dp))
            }
        }
        Spacer(Modifier.height(6.dp))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val nameWidth = 240.dp
            val gridWidth = maxWidth - nameWidth
            val dpPerMinute = gridWidth / WINDOW_MINUTES.toFloat()
            val windowEnd = windowStart + WINDOW_MINUTES * MINUTE

            Column {
                TimeHeader(windowStart, nameWidth, dpPerMinute)
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    itemsIndexed(channels, key = { i, ch -> "${i}_${ch.key}" }) { i, ch ->
                        GuideRow(
                            channel = ch,
                            programmes = epg?.window(ch, windowStart, windowEnd) ?: emptyList(),
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            nameWidth = nameWidth,
                            dpPerMinute = dpPerMinute,
                            now = now,
                            firstFocus = if (i == focusIndex) firstFocus else null,
                            onFocusProgramme = { p ->
                                vm.focusKeys[memoryKey] = ch.key
                                focusedText = if (p == null) {
                                    "${ch.number}  ${ch.name}: ingen guide"
                                } else {
                                    "${clock(p.start)}–${clock(p.stop)}  ${p.title}" + (p.desc?.let { " — $it" } ?: "")
                                }
                            },
                            onPlay = { vm.playChannel(channels, i) },
                            playing = vm.pip?.channel?.key == ch.key,
                        )
                    }
                }
            }
            // Röd linje för "nu".
            if (now in windowStart until windowEnd) {
                val x = nameWidth + dpPerMinute * ((now - windowStart) / MINUTE).toFloat()
                Box(
                    Modifier
                        .offset(x = x)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(JptvColors.Error.copy(alpha = 0.8f)),
                )
            }
        }
    }
    LaunchedEffect(channels) {
        if (channels.isEmpty()) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == focusIndex }) {
            listState.scrollToItem((focusIndex - 3).coerceAtLeast(0))
        }
        firstFocus.requestWhenReady()
    }
}

@Composable
private fun TimeHeader(windowStart: Long, nameWidth: Dp, dpPerMinute: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        for (slot in 0 until WINDOW_MINUTES / 30) {
            val t = windowStart + slot * HALF_HOUR
            Text(
                clock(t),
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.offset(x = nameWidth + dpPerMinute * (slot * 30).toFloat() + 6.dp),
            )
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programmes: List<Programme>,
    windowStart: Long,
    windowEnd: Long,
    nameWidth: Dp,
    dpPerMinute: Dp,
    now: Long,
    firstFocus: FocusRequester?,
    onFocusProgramme: (Programme?) -> Unit,
    onPlay: () -> Unit,
    /** Kanalen spelar i bild-i-bild-rutan. */
    playing: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        Row(
            Modifier
                .width(nameWidth)
                .fillMaxHeight()
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(channel, size = 40)
            Spacer(Modifier.width(10.dp))
            Text(
                (if (playing) "▶ " else "") + "${channel.number} ${channel.name}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (playing) JptvColors.Accent else androidx.compose.ui.graphics.Color.Unspecified,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            if (programmes.isEmpty()) {
                GuideCell(
                    title = "Ingen guide",
                    time = null,
                    live = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
                    onFocus = { onFocusProgramme(null) },
                    onClick = onPlay,
                )
            } else {
                // Första fokus i raden: programmet som går nu, annars det första.
                val focusIndex = programmes.indexOfFirst { now in it.start until it.stop }.coerceAtLeast(0)
                programmes.forEachIndexed { index, p ->
                    val start = maxOf(p.start, windowStart)
                    val end = minOf(p.stop, windowEnd)
                    val x = dpPerMinute * ((start - windowStart) / MINUTE).toFloat()
                    val w = dpPerMinute * ((end - start) / MINUTE).toFloat()
                    GuideCell(
                        title = p.title,
                        time = clock(p.start),
                        live = now in p.start until p.stop,
                        modifier = Modifier
                            .offset(x = x)
                            .width(w)
                            .then(
                                if (firstFocus != null && index == focusIndex) Modifier.focusRequester(firstFocus) else Modifier,
                            ),
                        onFocus = { onFocusProgramme(p) },
                        onClick = onPlay,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideCell(
    title: String,
    time: String?,
    live: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxHeight()
            .padding(2.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .background(
                when {
                    focused -> JptvColors.Accent
                    live -> JptvColors.SurfaceHigh
                    else -> JptvColors.Surface
                },
                MaterialTheme.shapes.small,
            )
            .border(1.dp, JptvColors.Background, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (focused) JptvColors.Background else JptvColors.Text,
                style = MaterialTheme.typography.bodySmall,
            )
            if (time != null) {
                Text(
                    time,
                    maxLines = 1,
                    color = if (focused) JptvColors.Background else JptvColors.TextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
