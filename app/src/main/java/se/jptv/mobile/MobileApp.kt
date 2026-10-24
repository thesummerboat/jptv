package se.jptv.mobile

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import se.jptv.AppViewModel
import se.jptv.PlayRequest
import se.jptv.Screen
import se.jptv.ui.formatTime

val Accent = Color(0xFFF2A541)
val Bg = Color(0xFF0E1116)
val Card = Color(0xFF181D25)
val CardHigh = Color(0xFF222935)
val Dim = Color(0xFF9AA4B2)

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF1A1206),
            secondary = Accent,
            background = Bg,
            onBackground = Color(0xFFECEFF4),
            surface = Bg,
            onSurface = Color(0xFFECEFF4),
            surfaceVariant = CardHigh,
            onSurfaceVariant = Dim,
            surfaceContainer = Card,
            surfaceContainerHigh = CardHigh,
            secondaryContainer = Color(0xFF3A2C14),
            onSecondaryContainer = Accent,
        ),
        content = content,
    )
}

/** Telefonens version av JPTV: flikar längst ner, castknapp överst och spelare i helskärm. */
@Composable
fun MobileApp(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CastManager.init(context) }
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    // Telefonen kan ha bytt nät (hemma → hotell): leta upp servern igen om den inte svarar.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        vm.reconnectIfNeeded()
        vm.refreshIfStale()
    }

    MobileTheme {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            val screen = vm.current
            key(vm.stack.size, screen) {
                when (screen) {
                    is Screen.Player -> PlayerOrCast(vm, screen)
                    else -> MobileShell(vm, screen)
                }
            }
            vm.dueReminder?.let { r ->
                AlertDialog(
                    onDismissRequest = { vm.dismissReminder() },
                    title = { Text("🔔 ${r.title}") },
                    text = { Text("Börjar ${se.jptv.ui.clock(r.start)} på ${r.channelName}") },
                    confirmButton = { TextButton(onClick = { vm.playReminder(r) }) { Text("Byt kanal") } },
                    dismissButton = { TextButton(onClick = { vm.dismissReminder() }) { Text("Stäng") } },
                )
            }
        }
    }
}

/** Ansluten till en TV när man trycker Spela: skicka dit i stället för att spela på telefonen. */
@Composable
private fun PlayerOrCast(vm: AppViewModel, screen: Screen.Player) {
    val castNow = remember(screen) { CastManager.connected }
    if (castNow) {
        LaunchedEffect(screen) {
            when (val r = screen.request) {
                is PlayRequest.Vod -> CastManager.castPlex(vm, r.item, if (r.fromStart) 0L else null)
                is PlayRequest.Live -> r.channels.getOrNull(r.index)?.let { CastManager.castChannel(it) }
                is PlayRequest.YouTube -> Unit
            }
            vm.back()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        when (val r = screen.request) {
            is PlayRequest.Vod -> MobileVodPlayer(vm, r)
            is PlayRequest.Live -> MobileLivePlayer(vm, r)
            // YouTube finns än så länge på TV:n och i webben.
            is PlayRequest.YouTube -> LaunchedEffect(r) { vm.back() }
        }
    }
}

private enum class Tab(val label: String) { HOME("Hem"), LIVE("Live-TV"), LIBRARY("Bibliotek"), SEARCH("Sök") }

private fun tabOf(screen: Screen): Tab? = when (screen) {
    Screen.Home -> Tab.HOME
    Screen.LiveTv, is Screen.Guide -> Tab.LIVE
    is Screen.Section, is Screen.Collection -> Tab.LIBRARY
    Screen.Search -> Tab.SEARCH
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileShell(vm: AppViewModel, screen: Screen) {
    var castDialog by remember { mutableStateOf(false) }
    val tab = tabOf(vm.stack.firstOrNull() ?: Screen.Home)
    val topLevel = vm.stack.size <= 1
    Scaffold(
        containerColor = Bg,
        topBar = {
            if (screen != Screen.Login) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                    navigationIcon = {
                        if (!topLevel) {
                            IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Tillbaka") }
                        }
                    },
                    title = {
                        if (topLevel && screen == Screen.Home) {
                            Text("JPTV", color = Accent, fontWeight = FontWeight.Black)
                        } else {
                            Text(titleOf(screen), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        if (CastManager.available) {
                            IconButton(onClick = { castDialog = true }) {
                                Icon(
                                    CastIcon,
                                    contentDescription = "Casta",
                                    tint = if (CastManager.connected) Accent else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        if (screen == Screen.Home) {
                            IconButton(onClick = { vm.open(Screen.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Inställningar") }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (screen != Screen.Login) {
                Column {
                    CastMiniBar()
                    NavigationBar(containerColor = Card) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = {
                                    when (t) {
                                        Tab.HOME -> vm.resetTo(Screen.Home)
                                        Tab.LIVE -> vm.resetTo(Screen.LiveTv)
                                        Tab.LIBRARY -> vm.visibleSections.firstOrNull()?.let { vm.resetTo(Screen.Section(it)) }
                                        Tab.SEARCH -> vm.resetTo(Screen.Search)
                                    }
                                },
                                icon = {
                                    Icon(
                                        when (t) {
                                            Tab.HOME -> Icons.Filled.Home
                                            Tab.LIVE -> TvIcon
                                            Tab.LIBRARY -> Icons.Filled.List
                                            Tab.SEARCH -> Icons.Filled.Search
                                        },
                                        contentDescription = t.label,
                                    )
                                },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                Screen.Login -> MobileLogin(vm)
                Screen.Home -> MobileHome(vm)
                is Screen.Section -> MobileLibrary(vm, screen.section, screen.genre)
                is Screen.Collection -> MobileCollection(vm, screen.collection)
                is Screen.Episodes -> MobileSeries(vm, screen.container)
                is Screen.Details -> MobileDetails(vm, screen.item)
                Screen.LiveTv, is Screen.Guide -> MobileLive(vm)
                Screen.Search -> MobileSearch(vm)
                Screen.Settings -> MobileSettings(vm)
                is Screen.Player -> Unit
                Screen.YouTube, is Screen.YouTubeChannel, Screen.YouTubeSearch -> Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("YouTube finns än så länge på TV:n och i webben (JPTV Web).")
                }
            }
        }
    }
    if (castDialog) CastDialog(onClose = { castDialog = false })
}

private fun titleOf(screen: Screen): String = when (screen) {
    Screen.Home -> "JPTV"
    is Screen.Section -> screen.section.title
    is Screen.Collection -> screen.collection.title
    is Screen.Episodes -> screen.container.showTitle?.takeIf { screen.container.type == "season" } ?: screen.container.title
    is Screen.Details -> screen.item.displayTitle
    Screen.LiveTv, is Screen.Guide -> "Live-TV"
    Screen.Search -> "Sök"
    Screen.Settings -> "Inställningar"
    else -> "JPTV"
}

// ---------- Casting ----------

/** Välj TV att casta till (hittas på samma wifi, även hotellets). */
@Composable
internal fun CastDialog(onClose: () -> Unit) {
    DisposableEffect(Unit) {
        CastManager.startDiscovery()
        onDispose { CastManager.stopDiscovery() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(CastManager.deviceName?.let { "Ansluten till $it" } ?: "Casta till TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (CastManager.connected) {
                    Text("Allt du spelar visas nu på TV:n. Telefonen blir fjärrkontroll.", color = Dim)
                } else {
                    if (CastManager.devices.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Letar efter TV-apparater på det här nätet…", color = Dim)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "På hotell: anslut telefonen till hotellets wifi och följ TV:ns instruktioner för att koppla ihop (ofta en QR-kod på TV:n).",
                            color = Dim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    CastManager.devices.forEach { route ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    CastManager.connect(route)
                                    onClose()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(TvIcon, contentDescription = null, tint = Accent)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(route.name)
                                route.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = Dim, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (CastManager.connected) {
                TextButton(onClick = {
                    CastManager.disconnect()
                    onClose()
                }) { Text("Koppla från") }
            } else {
                TextButton(onClick = onClose) { Text("Stäng") }
            }
        },
    )
}

/** Det som spelas på TV:n, ovanför flikarna: titel, paus, spola och stopp. */
@Composable
private fun CastMiniBar() {
    val title = CastManager.title
    val message = CastManager.message
    if (!CastManager.connected || (title == null && message == null)) return
    Surface(color = CardHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CastIcon, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title ?: "På ${CastManager.deviceName}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        message ?: listOfNotNull(CastManager.subtitle, "på ${CastManager.deviceName}").joinToString(" · "),
                        color = if (message != null) MaterialTheme.colorScheme.error else Dim,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (CastManager.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else if (title != null) {
                    if (!CastManager.isLive) {
                        TextButton(onClick = { CastManager.seekBy(-10_000) }) { Text("−10") }
                    }
                    IconButton(onClick = { CastManager.togglePause() }) {
                        Icon(if (CastManager.isPlaying) PauseIcon else Icons.Filled.PlayArrow, contentDescription = "Paus")
                    }
                    if (!CastManager.isLive) {
                        TextButton(onClick = { CastManager.seekBy(30_000) }) { Text("+30") }
                    }
                }
                IconButton(onClick = {
                    CastManager.message = null
                    CastManager.stopCasting()
                }) { Icon(Icons.Filled.Close, contentDescription = "Sluta casta") }
            }
            val duration = CastManager.durationMs
            if (title != null && !CastManager.isLive && duration > 0) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val fraction = dragging ?: (CastManager.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                Slider(
                    value = fraction,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { CastManager.seekTo((it * duration).toLong()) }
                        dragging = null
                    },
                    modifier = Modifier.height(24.dp),
                )
                Row {
                    Text(formatTime((fraction * duration).toLong()), color = Dim, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(duration), color = Dim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---------- Helskärm och orientering för spelarna ----------

/** Döljer status- och navigeringsfältet (svep för att visa), och låser till liggande om landscape. */
@Composable
fun FullscreenPlayer(forceLandscape: Boolean, hideBars: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(forceLandscape) {
        val before = activity.requestedOrientation
        if (forceLandscape) activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = before.takeIf { !forceLandscape } ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    DisposableEffect(hideBars) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (hideBars) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

// ---------- Ikoner som inte finns i grundpaketet ----------

val CastIcon: ImageVector by lazy {
    ImageVector.Builder("cast", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            moveTo(21f, 3f); horizontalLineTo(3f)
            curveTo(1.9f, 3f, 1f, 3.9f, 1f, 5f); verticalLineToRelative(3f); horizontalLineToRelative(2f)
            verticalLineTo(5f); horizontalLineToRelative(18f); verticalLineToRelative(14f); horizontalLineToRelative(-7f)
            verticalLineToRelative(2f); horizontalLineToRelative(7f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(5f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
            moveTo(1f, 18f); verticalLineToRelative(3f); horizontalLineToRelative(3f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f); close()
            moveTo(1f, 14f); verticalLineToRelative(2f)
            curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f); horizontalLineToRelative(2f)
            curveToRelative(0f, -3.87f, -3.13f, -7f, -7f, -7f); close()
            moveTo(1f, 10f); verticalLineToRelative(2f)
            curveToRelative(4.97f, 0f, 9f, 4.03f, 9f, 9f); horizontalLineToRelative(2f)
            curveToRelative(0f, -6.08f, -4.93f, -11f, -11f, -11f); close()
        }
    }.build()
}

val PauseIcon: ImageVector by lazy {
    ImageVector.Builder("pause", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineTo(6f); close()
            moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f); verticalLineTo(5f); close()
        }
    }.build()
}

val TvIcon: ImageVector by lazy {
    ImageVector.Builder("tv", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.White)) {
            moveTo(21f, 3f); horizontalLineTo(3f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(12f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(5f); verticalLineToRelative(2f)
            horizontalLineToRelative(8f); verticalLineToRelative(-2f); horizontalLineToRelative(5f)
            curveToRelative(1.1f, 0f, 1.99f, -0.9f, 1.99f, -2f); lineTo(23f, 5f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
            moveTo(21f, 17f); horizontalLineTo(3f); verticalLineTo(5f); horizontalLineToRelative(18f); verticalLineToRelative(12f); close()
        }
    }.build()
}

/** Stjärna för favoriter (finns i grundpaketet, men samlad här för telefonen). */
val StarIcon: ImageVector get() = Icons.Filled.Star

/** Liten etikett, t.ex. "IMDb 7,9" (gul) eller "4K". */
@Composable
fun Tag(text: String) {
    val imdb = text.startsWith("IMDb")
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (imdb) Color.Black else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(if (imdb) Color(0xFFF5C518) else CardHigh, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
