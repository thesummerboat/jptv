@file:OptIn(ExperimentalTvMaterial3Api::class)

package se.jptv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import se.jptv.AppViewModel

/** Sök i Plex, bland kanalerna och i TV-guiden samtidigt. */
@Composable
fun SearchScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf(vm.focusKeys["search_query"] ?: "") }
    var results by remember { mutableStateOf(AppViewModel.SearchResults()) }
    var searching by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }

    // Vänta en halv sekund efter sista tecknet innan vi söker.
    LaunchedEffect(query) {
        vm.focusKeys["search_query"] = query
        if (query.trim().length < 2) {
            results = AppViewModel.SearchResults()
            return@LaunchedEffect
        }
        delay(500)
        searching = true
        results = vm.searchEverything(query)
        searching = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        ScreenTitle("Sök", "filmer, serier, kanaler och TV-guiden")
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = JptvColors.Text, fontSize = 24.sp),
            cursorBrush = SolidColor(JptvColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { }),
            modifier = Modifier
                .width(800.dp)
                .focusRequester(fieldFocus)
                .onFocusChanged { fieldFocused = it.isFocused }
                .border(2.dp, if (fieldFocused) JptvColors.Accent else JptvColors.SurfaceHigh, MaterialTheme.shapes.small)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Skriv minst två bokstäver…", color = JptvColors.TextDim, fontSize = 24.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                searching -> "Söker…"
                results.error != null -> "Plex-sökningen misslyckades: ${results.error}"
                query.trim().length >= 2 && results.plex.isEmpty() && results.channels.isEmpty() && results.programmes.isEmpty() -> "Inga träffar."
                else -> ""
            },
            color = JptvColors.TextDim,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            itemsIndexed(results.plex, key = { i, hub -> "hub_${i}_${hub.title}" }) { _, hub ->
                Column {
                    Text(hub.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        itemsIndexed(hub.items, key = { i, item -> "${i}_${item.ratingKey}" }) { _, item ->
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
            if (results.channels.isNotEmpty()) {
                item(key = "channels") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Kanaler", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        val now = System.currentTimeMillis()
                        results.channels.forEachIndexed { i, ch ->
                            ChannelRow(
                                channel = ch,
                                epg = vm.epg,
                                now = now,
                                favorite = vm.isFavorite(ch),
                                onClick = { vm.playChannel(results.channels, i) },
                                onLongClick = { vm.toggleFavorite(ch) },
                            )
                        }
                    }
                }
            }
            if (results.programmes.isNotEmpty()) {
                item(key = "programmes") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("I TV-guiden", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        val now = System.currentTimeMillis()
                        results.programmes.forEach { (ch, p) ->
                            val live = p.start <= now && p.stop > now
                            ListItem(
                                selected = false,
                                onClick = { vm.playChannel(listOf(ch), 0) },
                                leadingContent = { ChannelLogo(ch) },
                                headlineContent = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(
                                        (if (live) "Nu · " else "") + "${dayLabel(p.start)} ${clock(p.start)}–${clock(p.stop)} · ${ch.name}",
                                        color = if (live) JptvColors.Accent else JptvColors.TextDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { fieldFocus.requestWhenReady() }
}

/** "idag", "imorgon" eller veckodag. */
fun dayLabel(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
    val year = cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = ms
    val sameYear = cal.get(java.util.Calendar.YEAR) == year
    return when {
        sameYear && cal.get(java.util.Calendar.DAY_OF_YEAR) == today -> "idag"
        sameYear && cal.get(java.util.Calendar.DAY_OF_YEAR) == today + 1 -> "imorgon"
        else -> java.text.SimpleDateFormat("EEE d/M", java.util.Locale("sv", "SE")).format(java.util.Date(ms))
    }
}
