package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.plex.PlexHomeRow

@Composable
fun HomeScreen(vm: AppViewModel) {
    val firstFocus = remember { FocusRequester() }
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
            Button(onClick = { vm.open(Screen.LiveTv) }, modifier = Modifier.focusRequester(firstFocus)) {
                Text("Live-TV")
            }
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
            else -> HomeRows(vm, vm.homeRows)
        }
    }
    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
        vm.checkForUpdate(manual = false)
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
private fun HomeRows(vm: AppViewModel, rows: List<PlexHomeRow>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(28.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        items(rows, key = { it.title }) { row ->
            Column {
                Text(row.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(end = 48.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    itemsIndexed(row.items, key = { i, item -> "${i}_${item.ratingKey}" }) { _, item ->
                        PosterCard(
                            title = item.displayTitle,
                            subtitle = item.subtitle,
                            imageUrl = vm.plex.imageUrl(item.thumb),
                            progress = item.progress,
                            watched = item.watched,
                            onClick = { vm.select(item) },
                        )
                    }
                }
            }
        }
    }
}
