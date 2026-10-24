package se.jptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.web.YtChannel
import se.jptv.web.YtHome
import se.jptv.web.YtJson
import se.jptv.web.YtSearch
import se.jptv.web.YtVideo

private val VideoCardWidth = 300.dp
private const val GRID_COLUMNS = 4

/** YouTube: Fortsätt titta, dina kanaler och nytt från dem. Allt hämtas via JPTV Web. */
@Composable
fun YouTubeScreen(vm: AppViewModel) {
    var home by remember { mutableStateOf<YtHome?>(vm.ytHome) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    val topFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(reload, vm.returnCount) {
        loading = true
        try {
            home = vm.loadYtHome()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "YouTube svarar inte"
        }
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("YouTube", style = MaterialTheme.typography.headlineMedium, color = JptvColors.Accent)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.open(Screen.YouTubeSearch) }, modifier = Modifier.focusRequester(topFocus)) { Text("Sök på YouTube") }
            if (loading && home != null) Text("Uppdaterar…", color = JptvColors.TextDim)
            Spacer(Modifier.weight(1f))
            Clock()
        }
        Spacer(Modifier.height(16.dp))
        val h = home
        when {
            h == null && loading -> MessageBox("Hämtar från YouTube…")
            h == null -> MessageBox(error ?: "YouTube svarar inte", buttonText = "Försök igen", onButton = { reload++ })
            else -> {
                val firstRow = when {
                    h.continueWatching.isNotEmpty() -> "continue"
                    h.subs.isNotEmpty() -> "subs"
                    else -> "feed"
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                    if (h.subs.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "Inga kanaler än. Sök efter en kanal och tryck Prenumerera, eller hämta dina prenumerationer " +
                                    "från FreeTube, NewPipe eller YouTube i JPTV Web (Inställningar → YouTube).",
                                color = JptvColors.TextDim,
                                modifier = Modifier.width(900.dp),
                            )
                        }
                    }
                    if (h.continueWatching.isNotEmpty()) {
                        item(key = "continue") {
                            VideoRow(vm, "Fortsätt titta", h.continueWatching, if (firstRow == "continue") firstFocus else null)
                        }
                    }
                    if (h.subs.isNotEmpty()) {
                        item(key = "subs") {
                            ChannelRow(vm, "Dina kanaler", h.subs, if (firstRow == "subs") firstFocus else null)
                        }
                    }
                    if (h.feed.isNotEmpty()) {
                        item(key = "feed_title") { Text("Nytt från dina kanaler", style = MaterialTheme.typography.titleLarge) }
                        val rows = h.feed.chunked(GRID_COLUMNS)
                        itemsIndexed(rows, key = { i, row -> "feed_${i}_${row.first().id}" }) { i, row ->
                            VideoGridRow(vm, row, if (i == 0 && firstRow == "feed") firstFocus else null)
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    if (h.subs.isEmpty() && h.continueWatching.isEmpty()) topFocus.requestWhenReady() else firstFocus.requestWhenReady()
                }
            }
        }
    }
}

/** Sök på YouTube: kanaler (att prenumerera på) och videor. */
@Composable
fun YouTubeSearchScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf(vm.focusKeys["yt_query"] ?: "") }
    var results by remember { mutableStateOf<YtSearch?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    // Ändrade prenumerationer syns direkt i listan.
    var subscribed by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    // YouTube-sök tar några sekunder: vänta tills man har slutat skriva.
    LaunchedEffect(query) {
        vm.focusKeys["yt_query"] = query
        if (query.trim().length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(900)
        searching = true
        try {
            results = vm.yt().ytSearch(query.trim())
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Sökningen misslyckades"
        }
        searching = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        ScreenTitle("Sök på YouTube", "kanaler och videor")
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
        val r = results
        Text(
            when {
                searching -> "Söker på YouTube…"
                error != null -> error!!
                r != null && r.videos.isEmpty() && r.channels.isEmpty() -> "Inga träffar."
                else -> ""
            },
            color = JptvColors.TextDim,
        )
        Spacer(Modifier.height(8.dp))
        if (r != null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                if (r.channels.isNotEmpty()) {
                    item(key = "channels") {
                        ChannelRow(
                            vm, "Kanaler",
                            r.channels.map { c -> subscribed[c.id]?.let { c.copy(subscribed = it) } ?: c },
                            null,
                        )
                    }
                }
                if (r.videos.isNotEmpty()) {
                    item(key = "videos_title") { Text("Videor", style = MaterialTheme.typography.titleLarge) }
                    itemsIndexed(r.videos.chunked(GRID_COLUMNS), key = { i, row -> "v_${i}_${row.first().id}" }) { _, row ->
                        VideoGridRow(vm, row, null)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { fieldFocus.requestWhenReady() }
}

/** En kanal: profilbild, prenumerera och de senaste videorna. */
@Composable
fun YouTubeChannelScreen(vm: AppViewModel, channelId: String, name: String) {
    var page by remember { mutableStateOf<se.jptv.web.YtChannelPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var subscribed by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val buttonFocus = remember { FocusRequester() }

    LaunchedEffect(channelId, reload) {
        try {
            page = vm.yt().ytChannel(channelId)
            subscribed = page?.channel?.subscribed
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Kanalen gick inte att hämta"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        val p = page
        if (p == null) {
            ScreenTitle(name.ifBlank { "Kanal" })
            if (error != null) {
                MessageBox(error!!, buttonText = "Försök igen", onButton = { reload++ })
            } else {
                MessageBox("Hämtar kanalen…")
            }
            return@Column
        }
        val ch = p.channel
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ChannelAvatar(vm, ch, 96.dp)
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ch.subscribers?.let {
                    Text("${String.format(java.util.Locale("sv", "SE"), "%,d", it)} prenumeranter", color = JptvColors.TextDim)
                }
                if (p.description.isNotBlank()) {
                    Text(p.description, color = JptvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
            val on = subscribed == true
            val toggle: () -> Unit = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            vm.yt().ytSubscribe(ch, !on)
                            subscribed = !on
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message
                        }
                        busy = false
                    }
                }
            }
            if (on) {
                OutlinedButton(onClick = toggle, modifier = Modifier.focusRequester(buttonFocus)) { Text("✓ Prenumererar") }
            } else {
                Button(onClick = toggle, modifier = Modifier.focusRequester(buttonFocus)) { Text("Prenumerera") }
            }
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "title") { Text("Senaste videorna", style = MaterialTheme.typography.titleLarge) }
            itemsIndexed(p.videos.chunked(GRID_COLUMNS), key = { i, row -> "c_${i}_${row.first().id}" }) { _, row ->
                VideoGridRow(vm, row, null, showChannel = false)
            }
        }
        LaunchedEffect(Unit) { buttonFocus.requestWhenReady() }
    }
}

// ---------- byggstenar ----------

@Composable
private fun VideoRow(vm: AppViewModel, title: String, videos: List<YtVideo>, firstFocus: FocusRequester?) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(videos, key = { i, v -> "${i}_${v.id}" }) { i, v ->
                VideoCard(vm, v, if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            }
        }
    }
}

@Composable
private fun VideoGridRow(vm: AppViewModel, videos: List<YtVideo>, firstFocus: FocusRequester?, showChannel: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        videos.forEachIndexed { i, v ->
            VideoCard(vm, v, if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier, showChannel)
        }
    }
}

@Composable
private fun ChannelRow(vm: AppViewModel, title: String, channels: List<YtChannel>, firstFocus: FocusRequester?) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(channels, key = { i, c -> "${i}_${c.id}" }) { i, c ->
                Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Card(
                        onClick = { vm.open(Screen.YouTubeChannel(c.id, c.name)) },
                        shape = CardDefaults.shape(shape = CircleShape),
                        scale = CardDefaults.scale(focusedScale = 1.1f),
                        modifier = Modifier
                            .size(88.dp)
                            .then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
                    ) {
                        ChannelAvatarContent(vm, c)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        (if (c.subscribed && title == "Kanaler") "✓ " else "") + c.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelAvatar(vm: AppViewModel, c: YtChannel, size: Dp) {
    Box(
        Modifier
            .size(size)
            .background(JptvColors.SurfaceHigh, CircleShape),
    ) { ChannelAvatarContent(vm, c) }
}

@Composable
private fun ChannelAvatarContent(vm: AppViewModel, c: YtChannel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(JptvColors.SurfaceHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(c.name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = JptvColors.TextDim)
        vm.web.ytImage(c.avatar)?.let { url ->
            AsyncImage(model = url, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Videokort (16:9): bild, längd, sedd/påbörjad, titel och kanal. */
@Composable
private fun VideoCard(vm: AppViewModel, v: YtVideo, modifier: Modifier = Modifier, showChannel: Boolean = true) {
    Column(modifier.width(VideoCardWidth)) {
        Card(
            onClick = { vm.playYouTube(v.id) },
            onLongClick = { if (v.channelId.isNotBlank()) vm.open(Screen.YouTubeChannel(v.channelId, v.channel)) },
            scale = CardDefaults.scale(scale = 0.94f, focusedScale = 1.05f),
            modifier = Modifier
                .width(VideoCardWidth)
                .aspectRatio(16f / 9f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(JptvColors.SurfaceHigh),
            ) {
                Text(
                    v.title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = JptvColors.TextDim,
                    maxLines = 3,
                )
                vm.web.ytImage(v.thumb)?.let { url ->
                    AsyncImage(model = url, contentDescription = v.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                v.durationMs?.let { d ->
                    Text(
                        formatTime(d),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.82f), MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (v.watched) {
                    Text(
                        "✓",
                        color = JptvColors.Accent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.75f), CircleShape)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
                if (v.progress > 0f && !v.watched) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(JptvColors.Background.copy(alpha = 0.7f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(v.progress)
                                .fillMaxHeight()
                                .background(JptvColors.Accent),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(v.channel.takeIf { showChannel && it.isNotBlank() }, YtJson.views(v.views), YtJson.ago(v.published))
            .joinToString(" · ")
        if (sub.isNotBlank()) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
