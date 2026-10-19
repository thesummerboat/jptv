package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.plex.PlexHomeRow
import se.jptv.plex.PlexItem

@Composable
fun HomeScreen(vm: AppViewModel) {
    val topFocus = remember { FocusRequester() }
    val itemFocus = remember { FocusRequester() }
    var focusedItem by remember { mutableStateOf<PlexItem?>(null) }
    // Kom tillbaka till samma kort som sist, om det finns kvar.
    val rememberedKey = remember { vm.focusKeys["home"] }
    val rememberedExists = remember(vm.homeRows) {
        rememberedKey != null && vm.homeRows.withIndex().any { (r, row) ->
            row.items.withIndex().any { (i, item) -> homeKey(r, i, item) == rememberedKey }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(vm.plex.imageUrl(focusedItem?.art, 1280, 720))
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 48.dp, top = 28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("JPTV", style = MaterialTheme.typography.headlineMedium, color = JptvColors.Accent)
                Spacer(Modifier.width(16.dp))
                Button(onClick = { vm.open(Screen.LiveTv) }, modifier = Modifier.focusRequester(topFocus)) {
                    Text("Live-TV")
                }
                LastChannelButton(vm)
                vm.sections.forEach { section ->
                    OutlinedButton(onClick = { vm.open(Screen.Section(section)) }) { Text(section.title) }
                }
                OutlinedButton(onClick = { vm.open(Screen.Settings) }) { Text("Inställningar") }
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

            FocusedInfo(focusedItem)

            val error = vm.homeError
            when {
                error != null -> MessageBox(
                    message = error,
                    buttonText = "Försök igen",
                    onButton = { vm.connectAndLoad(rediscover = true) },
                )
                vm.homeLoading && vm.homeRows.isEmpty() -> MessageBox("Hämtar från ${vm.serverName ?: "Plex"}…")
                vm.homeRows.isEmpty() -> MessageBox(
                    message = "Inget att visa ännu från Plex.",
                    buttonText = "Uppdatera",
                    onButton = { vm.refreshHome() },
                )
                else -> HomeRows(
                    vm = vm,
                    rows = vm.homeRows,
                    focusKey = if (rememberedExists) rememberedKey else null,
                    focusRequester = itemFocus,
                    onFocused = { key, item ->
                        focusedItem = item
                        vm.focusKeys["home"] = key
                    },
                )
            }
        }
    }
    LaunchedEffect(rememberedExists) {
        if (rememberedExists) itemFocus.requestWhenReady() else topFocus.requestWhenReady()
        vm.checkForUpdate(manual = false)
    }
}

private fun homeKey(row: Int, index: Int, item: PlexItem) = "${row}_${index}_${item.ratingKey}"

/** Titel och handling för kortet som har fokus, ovanför raderna. */
@Composable
private fun FocusedInfo(item: PlexItem?) {
    Column(
        Modifier
            .height(96.dp)
            .width(820.dp),
    ) {
        if (item != null) {
            Text(item.displayTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(item.subtitle, item.summary).joinToString("  ·  ")
            Text(line, color = JptvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    focusKey: String?,
    focusRequester: FocusRequester,
    onFocused: (String, PlexItem) -> Unit,
) {
    LazyColumn(
        state = vm.listState("home"),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        itemsIndexed(rows, key = { r, row -> "${r}_${row.title}" }) { r, row ->
            Column {
                Text(row.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    state = vm.listState("home_row_${row.title}"),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(end = 48.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    itemsIndexed(row.items, key = { i, item -> "${i}_${item.ratingKey}" }) { i, item ->
                        val key = homeKey(r, i, item)
                        PosterCard(
                            title = item.displayTitle,
                            subtitle = item.subtitle,
                            imageUrl = vm.plex.imageUrl(item.thumb),
                            progress = item.progress,
                            watched = item.watched,
                            onClick = { vm.select(item) },
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
