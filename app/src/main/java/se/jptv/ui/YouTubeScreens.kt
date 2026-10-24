package se.jptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import se.jptv.web.YtRecs
import se.jptv.web.YtSearch
import se.jptv.web.YtVideo

private val VideoCardWidth = 300.dp
private const val GRID_COLUMNS = 4

// ---------- kort-meny och fokus ----------

/** Kortet man höll in OK på. suggested: ett förslag (kan döljas). */
private class YtMenuTarget(val video: YtVideo, val suggested: Boolean, val showChannel: Boolean, val focus: FocusRequester)

/** Delas av korten på en skärm: menyn som är öppen och förslag som har dolts här. */
private class YtCardActions {
    var menu by mutableStateOf<YtMenuTarget?>(null)
    var hiddenVideos by mutableStateOf(setOf<String>())
    var hiddenChannels by mutableStateOf(setOf<String>())

    fun hidden(v: YtVideo, suggested: Boolean) = suggested && (v.id in hiddenVideos || v.channelId in hiddenChannels)
}

/**
 * Vilket kort som hade fokus, så att man kommer tillbaka till samma kort efter en video, och så
 * att fokus hamnar rätt när raderna uppdateras (ett kort som försvinner tar annars fokus med sig).
 */
private class YtFocus(private val vm: AppViewModel, private val slot: String) {
    val cards = HashMap<String, FocusRequester>()
    var current: String? = vm.focusKeys[slot]
    var started = false
    var lost = false

    fun focused(key: String) {
        current = key
        vm.focusKeys[slot] = key
    }

    /** Något annat än ett videokort har fokus (sök, kanaler): fokus ska inte flyttas tillbaka. */
    fun elsewhere() {
        current = null
    }

    fun disposed(key: String) {
        if (key == current) lost = true
    }

    suspend fun restore(): Boolean {
        // Raderna (LazyRow) bygger sina kort vid layout: vänta in dem.
        repeat(2) { withFrameNanos { } }
        val r = current?.let { cards[it] } ?: return false
        r.requestWhenReady()
        started = true
        lost = false
        return true
    }
}

/** YouTube: Fortsätt titta, förslag, nytt från kanalerna, "eftersom du tittade på" och kanaler. Allt via JPTV Web. */
@Composable
fun YouTubeScreen(vm: AppViewModel) {
    var home by remember { mutableStateOf<YtHome?>(vm.ytHome) }
    var recs by remember { mutableStateOf<YtRecs?>(vm.ytRecs) }
    var error by remember { mutableStateOf<String?>(null) }
    var recsError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var recsLoading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    val actions = remember { YtCardActions() }
    val focus = remember { YtFocus(vm, "yt_home") }
    val scope = rememberCoroutineScope()
    val topFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(reload, vm.returnCount) {
        launch {
            recsLoading = true
            try {
                val fresh = vm.loadYtRecs()
                // Förslagen byts inte medan man tittar på dem (kortet man står på kunde försvinna);
                // de nya syns nästa gång man kommer hit.
                if (recs == null) recs = fresh
                recsError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recsError = e.message ?: "Förslagen gick inte att hämta"
            }
            recsLoading = false
        }
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

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 48.dp, top = 28.dp, end = 48.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("YouTube", style = MaterialTheme.typography.headlineMedium, color = JptvColors.Accent)
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.open(Screen.YouTubeSearch) },
                    modifier = Modifier
                        .focusRequester(topFocus)
                        .onFocusChanged { if (it.isFocused) focus.elsewhere() },
                ) { Text("Sök på YouTube") }
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
                    val r = recs
                    val fresh = h.feed.filter { !it.watched }.take(40)
                    val rowOrder = buildList {
                        if (h.continueWatching.isNotEmpty()) add("continue")
                        if (r != null && r.recommended.isNotEmpty()) add("recs")
                        if (fresh.isNotEmpty()) add("feed")
                        r?.because?.indices?.forEach { add("because$it") }
                    }
                    val firstRow = rowOrder.firstOrNull()
                    fun first(key: String) = if (key == firstRow) firstFocus else null

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        contentPadding = PaddingValues(bottom = 48.dp),
                    ) {
                        if (h.subs.isEmpty() && h.continueWatching.isEmpty() && r?.recommended.isNullOrEmpty() && !recsLoading) {
                            item(key = "welcome") {
                                Text(
                                    "Här kommer förslag när du har tittat på några videor eller prenumererar på kanaler. " +
                                        "Förslagen räknas fram hemma hos dig, utan Google-konto. Sök efter en kanal och tryck " +
                                        "Prenumerera, eller hämta dina prenumerationer från FreeTube, NewPipe eller YouTube i " +
                                        "JPTV Web (Inställningar → YouTube).",
                                    color = JptvColors.TextDim,
                                    modifier = Modifier.width(900.dp),
                                )
                            }
                        }
                        if (h.continueWatching.isNotEmpty()) {
                            item(key = "continue") {
                                VideoRow(vm, "Fortsätt titta", h.continueWatching, "continue", first("continue"), actions, focus)
                            }
                        }
                        when {
                            r != null && r.recommended.isNotEmpty() -> item(key = "recs") {
                                VideoRow(vm, "Rekommenderat för dig", r.recommended, "recs", first("recs"), actions, focus, suggested = true)
                            }
                            r == null && recsLoading -> item(key = "recs_wait") {
                                Text("Letar fram förslag…", color = JptvColors.TextDim)
                            }
                            r == null && recsError != null -> item(key = "recs_error") {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Förslagen gick inte att hämta: $recsError", color = JptvColors.TextDim, modifier = Modifier.width(760.dp))
                                    OutlinedButton(onClick = { reload++ }) { Text("Försök igen") }
                                }
                            }
                        }
                        if (fresh.isNotEmpty()) {
                            item(key = "feed") {
                                VideoRow(vm, "Nytt från dina kanaler", fresh, "feed", first("feed"), actions, focus)
                            }
                        }
                        r?.because?.forEachIndexed { i, b ->
                            item(key = "because_${b.seedId}") {
                                VideoRow(vm, "Eftersom du tittade på ”${b.seedTitle}”", b.videos, "because$i", first("because$i"), actions, focus, suggested = true)
                            }
                        }
                        if (r != null && r.channels.isNotEmpty()) {
                            item(key = "suggest") {
                                ChannelRow(vm, "Kanaler du kanske gillar", r.channels, null, onFocus = { focus.elsewhere() })
                            }
                        }
                        if (h.subs.isNotEmpty()) {
                            item(key = "subs") {
                                ChannelRow(vm, "Dina kanaler", h.subs, null, onFocus = { focus.elsewhere() })
                            }
                        }
                    }
                    // Fokus: samma kort som förut (efter en video), annars första raden.
                    LaunchedEffect(h, r) {
                        if (actions.menu != null) return@LaunchedEffect
                        if (focus.restore()) return@LaunchedEffect
                        if (!focus.started || focus.lost) {
                            focus.started = true
                            focus.lost = false
                            if (firstRow != null) firstFocus.requestWhenReady() else topFocus.requestWhenReady()
                        }
                    }
                }
            }
        }
        actions.menu?.let { target ->
            YtVideoMenu(vm, target, actions, onClose = {
                actions.menu = null
                scope.launch { target.focus.requestWhenReady() }
            })
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
    val actions = remember { YtCardActions() }
    val scope = rememberCoroutineScope()
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

    Box(Modifier.fillMaxSize()) {
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
                            VideoGridRow(vm, row, null, actions = actions)
                        }
                    }
                }
            }
        }
        actions.menu?.let { target ->
            YtVideoMenu(vm, target, actions, onClose = {
                actions.menu = null
                scope.launch { target.focus.requestWhenReady() }
            })
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
    val actions = remember { YtCardActions() }

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

    Box(Modifier.fillMaxSize()) {
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
                    VideoGridRow(vm, row, null, showChannel = false, actions = actions)
                }
            }
            LaunchedEffect(Unit) { buttonFocus.requestWhenReady() }
        }
        actions.menu?.let { target ->
            YtVideoMenu(vm, target, actions, onClose = {
                actions.menu = null
                scope.launch { target.focus.requestWhenReady() }
            })
        }
    }
}

// ---------- byggstenar ----------

@Composable
private fun VideoRow(
    vm: AppViewModel,
    title: String,
    videos: List<YtVideo>,
    rowKey: String,
    firstFocus: FocusRequester?,
    actions: YtCardActions,
    focus: YtFocus?,
    suggested: Boolean = false,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            // Samma video två gånger i en rad skulle krascha listan (nyckeln är videons id).
            itemsIndexed(videos.distinctBy { it.id }, key = { _, v -> v.id }) { i, v ->
                VideoCard(
                    vm, v,
                    if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                    actions = actions, suggested = suggested, focus = focus, focusKey = "$rowKey/${v.id}",
                )
            }
        }
    }
}

@Composable
private fun VideoGridRow(
    vm: AppViewModel,
    videos: List<YtVideo>,
    firstFocus: FocusRequester?,
    showChannel: Boolean = true,
    actions: YtCardActions? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        videos.forEachIndexed { i, v ->
            VideoCard(vm, v, if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier, showChannel, actions)
        }
    }
}

@Composable
private fun ChannelRow(vm: AppViewModel, title: String, channels: List<YtChannel>, firstFocus: FocusRequester?, onFocus: (() -> Unit)? = null) {
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
                            .then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                            .then(if (onFocus != null) Modifier.onFocusChanged { if (it.isFocused) onFocus() } else Modifier),
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

/**
 * Videokort (16:9): bild, längd, sedd/påbörjad, titel, kanal, visningar och datum.
 * Håll in OK: meny (spela från början, kanalen, och för förslag: inte intresserad / föreslå inte kanalen).
 */
@Composable
private fun VideoCard(
    vm: AppViewModel,
    v: YtVideo,
    modifier: Modifier = Modifier,
    showChannel: Boolean = true,
    actions: YtCardActions? = null,
    suggested: Boolean = false,
    focus: YtFocus? = null,
    focusKey: String? = null,
) {
    val self = remember { FocusRequester() }
    if (focus != null && focusKey != null) {
        DisposableEffect(focusKey) {
            focus.cards[focusKey] = self
            onDispose {
                if (focus.cards[focusKey] === self) focus.cards.remove(focusKey)
                focus.disposed(focusKey)
            }
        }
    }
    val hidden = actions?.hidden(v, suggested) == true
    Column(modifier.width(VideoCardWidth)) {
        Card(
            onClick = { vm.playYouTube(v.id) },
            onLongClick = {
                if (actions != null) {
                    actions.menu = YtMenuTarget(v, suggested, showChannel, self)
                } else if (v.channelId.isNotBlank()) {
                    vm.open(Screen.YouTubeChannel(v.channelId, v.channel))
                }
            },
            scale = CardDefaults.scale(scale = 0.94f, focusedScale = 1.05f),
            modifier = Modifier
                .width(VideoCardWidth)
                .aspectRatio(16f / 9f)
                .focusRequester(self)
                .then(
                    if (focus != null && focusKey != null) Modifier.onFocusChanged { if (it.isFocused) focus.focused(focusKey) } else Modifier,
                ),
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
                if (hidden) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.78f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Föreslås inte igen", style = MaterialTheme.typography.titleSmall, color = JptvColors.TextDim)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(if (hidden) Modifier.alpha(0.4f) else Modifier) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (showChannel && v.channel.isNotBlank()) {
                Text(v.channel, style = MaterialTheme.typography.bodySmall, color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val meta = listOfNotNull(YtJson.views(v.views), YtJson.ago(v.published)).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Meny när man håller in OK på ett videokort (som på Plex-hemskärmen). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun YtVideoMenu(vm: AppViewModel, target: YtMenuTarget, actions: YtCardActions, onClose: () -> Unit) {
    val v = target.video
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val started = v.positionMs > 0 && !v.watched

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                onClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Det gick inte"
            }
            busy = false
        }
    }

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
                .width(560.dp)
                .background(JptvColors.Surface, MaterialTheme.shapes.large)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(v.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(v.channel.takeIf { it.isNotBlank() }, YtJson.views(v.views), YtJson.ago(v.published)).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    onClose()
                    vm.playYouTube(v.id)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(first),
            ) { Text(if (started) "Fortsätt spela" else "Spela") }
            if (started) {
                OutlinedButton(onClick = {
                    onClose()
                    vm.playYouTube(v.id, fromStart = true)
                }, modifier = Modifier.fillMaxWidth()) { Text("Spela från början") }
            }
            if (target.showChannel && v.channelId.isNotBlank()) {
                OutlinedButton(onClick = {
                    onClose()
                    vm.open(Screen.YouTubeChannel(v.channelId, v.channel))
                }, modifier = Modifier.fillMaxWidth()) { Text("Gå till ${v.channel.ifBlank { "kanalen" }}") }
            }
            if (target.suggested) {
                OutlinedButton(onClick = {
                    act {
                        vm.yt().ytDismiss(v.id)
                        actions.hiddenVideos = actions.hiddenVideos + v.id
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Inte intresserad") }
                if (v.channelId.isNotBlank()) {
                    OutlinedButton(onClick = {
                        act {
                            vm.yt().ytBlock(v.channelId, v.channel)
                            actions.hiddenChannels = actions.hiddenChannels + v.channelId
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Föreslå inte ${v.channel.ifBlank { "kanalen" }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            error?.let { Text(it, color = JptvColors.Error) }
        }
    }
    LaunchedEffect(Unit) { first.requestWhenReady() }
}
