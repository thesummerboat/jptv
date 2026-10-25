package se.jptv.mobile

import android.content.res.Configuration
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import se.jptv.AppViewModel
import se.jptv.PlayRequest
import se.jptv.Screen
import se.jptv.iptv.Channel
import se.jptv.player.VideoSurface
import se.jptv.player.buildExoPlayer
import se.jptv.player.liveSource
import se.jptv.plex.PlexItem
import se.jptv.ui.clock
import java.util.UUID

// ======================================================================
// Filmer och avsnitt
// ======================================================================

/**
 * Plex på telefonen, liggande i helskärm med Media3:s vanliga kontroller (spola, paus, ljud och
 * undertexter). Hemma spelas originalfilen; hemifrån kodar Plex om till vald kvalitet.
 */
@OptIn(UnstableApi::class)
@Composable
fun MobileVodPlayer(vm: AppViewModel, request: PlayRequest.Vod) {
    FullscreenPlayer(forceLandscape = true, hideBars = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf(request.item) }
    /** Pågående visning i Plex; stängs vid lång paus och när appen läggs i bakgrunden. */
    var sessionOpen by remember { mutableStateOf(false) }
    val exo = remember {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    true,
                )
                playWhenReady = true
            }
    }
    val session = remember { UUID.randomUUID().toString() }
    var transcoding by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var next by remember { mutableStateOf<PlexItem?>(null) }
    var controls by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }

    fun hls(url: String): MediaSource {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(vm.plex.streamHeaders())
        return HlsMediaSource.Factory(http).createMediaSource(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
    }

    fun direct(url: String): MediaSource {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(vm.plex.streamHeaders())
        return DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(url))
    }

    fun transcode(atMs: Long) {
        if (transcoding) return
        transcoding = true
        error = null
        scope.launch {
            try {
                val q = if (vm.plex.serverIsLocal()) null else vm.remoteStream()
                val url = vm.plex.prepareTranscode(item, session, q?.first ?: 20000, q?.second ?: "1920x1080")
                exo.setMediaSource(hls(url), atMs)
                exo.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Plex kunde inte koda om: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        val fresh = withTimeoutOrNull(5_000) {
            try {
                vm.plex.metadata(request.item.ratingKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: request.item
        item = fresh
        val start = if (request.fromStart || request.trailer) 0L else fresh.viewOffsetMs
        val url = vm.plex.directPlayUrl(fresh)
        // Hemma (eller valt "Original"): filen som den är. Hemifrån: omkodat till vald nivå.
        val useDirect = url != null && (request.trailer || vm.plex.serverIsLocal() || vm.remoteStream() == null)
        if (useDirect && url != null) {
            exo.setMediaSource(direct(url), start)
            exo.prepare()
        } else {
            transcode(start)
        }
        if (fresh.type == "episode" && !request.trailer) {
            next = try {
                vm.plex.nextEpisode(fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // Telefonen klarar inte filen: be Plex koda om. Andra felet: visa det.
                if (!transcoding) transcode(exo.currentPosition) else error = "Kan inte spela upp (${e.errorCodeName})"
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) sessionOpen = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) started = true
                if (playbackState == Player.STATE_ENDED) {
                    val n = next
                    if (n != null) vm.replaceTop(Screen.Player(PlayRequest.Vod(n))) else vm.back()
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            if (!request.trailer) {
                val pos = exo.currentPosition
                val dur = exo.duration.takeIf { it > 0 } ?: item.durationMs
                if (started && sessionOpen) {
                    vm.reportStopped(item, pos, dur, session.takeIf { transcoding }, ended = dur > 0 && pos > dur * 0.95)
                } else {
                    vm.abandonPlayback(session.takeIf { transcoding })
                }
            }
            exo.release()
        }
    }

    // Position för "Hoppa över intro" och "Nästa avsnitt", och "Fortsätt titta" i Plex var tionde sekund.
    // Lång paus: visningen avslutas i Plex, så att en annan enhet kan ta över utan att telefonen
    // fortsätter skicka sin gamla position.
    LaunchedEffect(Unit) {
        var tick = 0
        var pausedSince = 0L
        while (true) {
            delay(500)
            position = exo.currentPosition
            val now = System.currentTimeMillis()
            pausedSince = if (exo.playWhenReady) 0L else pausedSince.takeIf { it > 0L } ?: now
            if (++tick % 20 == 0 && started && sessionOpen && !request.trailer && exo.duration > 0) {
                val pausedLong = !exo.isPlaying && pausedSince > 0L && now - pausedSince > 2 * 60_000L
                val state = when {
                    exo.isPlaying -> "playing"
                    pausedLong -> "stopped"
                    else -> "paused"
                }
                vm.reportProgress(item, state, exo.currentPosition, exo.duration, session.takeIf { transcoding })
                if (pausedLong) sessionOpen = false
            }
        }
    }

    // Ansluter man till en TV medan filmen går: fortsätt på TV:n där telefonen var.
    LaunchedEffect(CastManager.connected) {
        if (CastManager.connected && !request.trailer) {
            CastManager.castPlex(vm, item, if (started) exo.currentPosition else null)
            vm.back()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        exo.pause()
        // I bakgrunden: avsluta visningen i Plex och sluta skicka positionen.
        if (started && sessionOpen && !request.trailer && exo.duration > 0) {
            vm.reportProgress(item, "stopped", exo.currentPosition, exo.duration, session.takeIf { transcoding })
        }
        sessionOpen = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    useController = true
                    controllerShowTimeoutMs = 3_500
                    keepScreenOn = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == View.VISIBLE })
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (controls) {
            PlayerTopBar(
                title = item.displayTitle,
                subtitle = item.subtitle?.takeIf { item.type == "episode" },
                onBack = { vm.back() },
            )
        }
        val intro = item.intro
        val credits = item.credits
        when {
            intro != null && position >= intro.startMs && position < intro.endMs - 1_000 -> FilledTonalButton(
                onClick = { exo.seekTo(intro.endMs) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 96.dp),
            ) { Text("Hoppa över intro") }
            next != null && credits != null && position >= credits.startMs -> Button(
                onClick = { next?.let { vm.replaceTop(Screen.Player(PlayRequest.Vod(it))) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 96.dp),
            ) { Text("Nästa avsnitt ▶") }
        }
        error?.let {
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f), MaterialTheme.shapes.medium).padding(20.dp)) {
                Text(it, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.back() }) { Text("Tillbaka") }
            }
        }
    }
}

/** Tillbaka, titel och castknapp ovanpå bilden. */
@Composable
private fun PlayerTopBar(title: String, subtitle: String?, onBack: () -> Unit) {
    var castDialog by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Tillbaka", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
        if (CastManager.available) {
            IconButton(onClick = { castDialog = true }) { Icon(CastIcon, contentDescription = "Casta", tint = Color.White) }
        }
    }
    if (castDialog) CastDialog(onClose = { castDialog = false })
}

// ======================================================================
// Live-TV
// ======================================================================

/**
 * Live på telefonen. Stående: bilden överst, vad som går nu och kanallistan under. Liggande: helskärm.
 * En ström åt gången: kanalbyte ersätter strömmen i samma spelare.
 */
@OptIn(UnstableApi::class)
@Composable
fun MobileLivePlayer(vm: AppViewModel, request: PlayRequest.Live) {
    // Skärmen spelaren hör till: har appen börjat om på Hem ska strömmen inte öppnas igen.
    val ownScreen = remember { vm.current }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    FullscreenPlayer(forceLandscape = false, hideBars = landscape)
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val exo = remember { buildExoPlayer(context, tunneling = false) }
    var channels by remember { mutableStateOf(request.channels) }
    var index by remember { mutableIntStateOf(request.index.coerceIn(0, request.channels.lastIndex)) }
    val channel = channels[index]
    var error by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var playingKey by remember { mutableStateOf<String?>(null) }
    var recordedKey by remember { mutableStateOf<String?>(null) }
    var stoppedInBackground by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    return
                }
                if (retries < 2) {
                    retries++
                    exo.prepare()
                    return
                }
                error = "Kanalen spelar inte just nu (${e.errorCodeName})"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    error = null
                    retries = 0
                    val key = playingKey
                    if (key != null && key != recordedKey) {
                        recordedKey = key
                        channels.firstOrNull { it.key == key }?.let { vm.onChannelTuned(it) }
                    }
                }
                if (playbackState == Player.STATE_ENDED && retries < 3) {
                    retries++
                    exo.seekToDefaultPosition()
                    exo.prepare()
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    LaunchedEffect(channel.key) {
        error = null
        retries = 0
        playingKey = channel.key
        exo.setMediaSource(liveSource(channel))
        exo.prepare()
        exo.play()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    // Pekar man på bilden visas kanalnamnet en stund (liggande).
    LaunchedEffect(overlay) {
        if (overlay) {
            delay(4_000)
            overlay = false
        }
    }

    // Strömmen tar en anslutning hos leverantören: stäng den när appen läggs undan.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        stoppedInBackground = true
        exo.stop()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (vm.current !== ownScreen) return@LifecycleEventEffect
        if (stoppedInBackground) {
            stoppedInBackground = false
            retries = 0
            exo.prepare()
            exo.play()
        }
    }

    // Ansluter man till en TV: kanalen fortsätter där, telefonens ström stängs (en ström).
    LaunchedEffect(CastManager.connected) {
        if (CastManager.connected) {
            CastManager.castChannel(channel)
            vm.back()
        }
    }

    val video: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color.Black).clickable { overlay = !overlay }) {
            VideoSurface(exo, vm.videoScale, keepContent = true)
            error?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(12.dp),
                )
            }
            if (overlay || error != null) {
                PlayerTopBar(title = "${channel.number}  ${channel.name}", subtitle = vm.epg?.now(channel, now)?.title, onBack = { vm.back() })
            }
        }
    }

    if (landscape) {
        video(Modifier.fillMaxSize())
    } else {
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
            video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            NowPlaying(vm, channel, now)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(channels, key = { i, ch -> "${i}_${ch.key}" }) { i, ch ->
                    Box(if (i == index) Modifier.background(CardHigh) else Modifier) {
                        ChannelListRow(vm = vm, channel = ch, now = now, onClick = { if (i != index) index = i })
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(vm: AppViewModel, channel: Channel, now: Long) {
    val current = vm.epg?.now(channel, now)
    val next = vm.epg?.next(channel, now)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogoSmall(channel, 36.dp)
            Spacer(Modifier.width(10.dp))
            Text("${channel.number}  ${channel.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (current != null) {
            Spacer(Modifier.height(6.dp))
            Text("${clock(current.start)}–${clock(current.stop)}  ${current.title}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { current.progress(now) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Accent)
            current.desc?.let { Text(it, color = Dim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        next?.let { Text("Sedan ${clock(it.start)} ${it.title}", color = Dim, style = MaterialTheme.typography.labelSmall) }
    }
}
