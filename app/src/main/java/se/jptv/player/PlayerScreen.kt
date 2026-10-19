package se.jptv.player

import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.jptv.AppViewModel
import se.jptv.PlayRequest
import se.jptv.iptv.Channel
import se.jptv.iptv.IptvRepository
import se.jptv.ui.ChannelLogo
import se.jptv.ui.JptvColors
import se.jptv.ui.MessageBox
import se.jptv.ui.formatTime
import java.util.UUID

@Composable
fun PlayerScreen(vm: AppViewModel, request: PlayRequest) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    when (request) {
        is PlayRequest.Vod -> VodPlayer(vm, request)
        is PlayRequest.Live -> LivePlayer(vm, request)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun rememberExoPlayer(): ExoPlayer {
    val context = LocalContext.current
    return remember {
        // Decoder fallback: om hårdvaruavkodaren vägrar provas nästa, vilket hjälper på svagare TV-apparater.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            playWhenReady = true
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(exo: ExoPlayer) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                player = exo
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(UnstableApi::class)
private fun plexSource(url: String, headers: Map<String, String>, hls: Boolean): MediaSource {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headers)
    val item = MediaItem.Builder().setUri(url).apply {
        if (hls) setMimeType(MimeTypes.APPLICATION_M3U8)
    }.build()
    return if (hls) {
        HlsMediaSource.Factory(http).createMediaSource(item)
    } else {
        DefaultMediaSourceFactory(http).createMediaSource(item)
    }
}

@OptIn(UnstableApi::class)
private fun liveSource(channel: Channel): MediaSource {
    val headers = buildMap<String, String> {
        channel.referrer?.let { put("Referer", it) }
    }
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(channel.userAgent ?: IptvRepository.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)
        .setDefaultRequestProperties(headers)
    val item = MediaItem.Builder().setUri(channel.url).apply {
        if (channel.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
    }.build()
    return DefaultMediaSourceFactory(http).createMediaSource(item)
}

// ======================================================================
// Film och avsnitt från Plex
// ======================================================================

@OptIn(UnstableApi::class)
@Composable
private fun VodPlayer(vm: AppViewModel, request: PlayRequest.Vod) {
    val item = request.item
    val exo = rememberExoPlayer()
    val session = remember { UUID.randomUUID().toString() }
    val startAt = if (request.fromStart) 0L else item.viewOffsetMs
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var transcoding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(startAt) }
    var duration by remember { mutableLongStateOf(item.durationMs) }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }

    // Reserv: låt Plex koda om till H.264 + AAC när TV:n inte klarar originalfilen.
    fun switchToTranscode(atMs: Long) {
        if (transcoding) return
        transcoding = true
        error = null
        scope.launch {
            try {
                val url = vm.plex.prepareTranscode(item, session)
                exo.setMediaSource(plexSource(url, vm.plex.streamHeaders(), hls = true), atMs)
                exo.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Plex kunde inte koda om filen: ${e.message}"
            }
        }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (!transcoding) {
                    switchToTranscode(maxOf(exo.currentPosition, startAt))
                } else {
                    error = "Kan inte spela upp (${e.errorCodeName})"
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (transcoding || tracks.groups.isEmpty()) return
                // TV:n hittade spåret men kan inte avkoda det (t.ex. DTS på TCL) -> spelas utan ljud.
                val audioUnsupported = tracks.containsType(C.TRACK_TYPE_AUDIO) &&
                    !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                val videoUnsupported = tracks.containsType(C.TRACK_TYPE_VIDEO) &&
                    !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
                if (audioUnsupported || videoUnsupported) switchToTranscode(exo.currentPosition)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    ended = true
                    vm.back()
                }
            }
        }
        exo.addListener(listener)
        val direct = vm.plex.directPlayUrl(item)
        if (direct != null) {
            exo.setMediaSource(plexSource(direct, vm.plex.streamHeaders(), hls = false), startAt)
            exo.prepare()
        } else {
            switchToTranscode(startAt)
        }
        onDispose {
            exo.removeListener(listener)
            val pos = exo.currentPosition
            val dur = if (exo.duration > 0) exo.duration else item.durationMs
            vm.reportStopped(item, pos, dur, if (transcoding) session else null, ended)
            exo.release()
        }
    }

    // Uppdatera tiden på skärmen, göm infon efter en stund och rapportera till Plex var tionde sekund.
    LaunchedEffect(exo) {
        var ticks = 0
        while (true) {
            position = exo.currentPosition
            if (exo.duration > 0) duration = exo.duration
            if (overlayVisible && exo.isPlaying && System.currentTimeMillis() - lastKeyAt > 5_000) {
                overlayVisible = false
            }
            if (ticks % 20 == 0 && exo.playbackState == Player.STATE_READY) {
                vm.reportProgress(item, if (exo.isPlaying) "playing" else "paused", position, duration)
            }
            ticks++
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                val native = ev.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val handled = when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        if (exo.isPlaying) exo.pause() else exo.play()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        exo.play()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        exo.pause()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0))
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val target = exo.currentPosition + 30_000
                        val max = if (exo.duration > 0) exo.duration - 2_000 else target
                        exo.seekTo(minOf(target, max))
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> true
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        vm.back()
                        true
                    }
                    else -> false
                }
                if (handled) {
                    lastKeyAt = System.currentTimeMillis()
                    overlayVisible = true
                }
                handled
            },
    ) {
        VideoSurface(exo)
        if (overlayVisible || !playing) {
            VodOverlay(
                title = item.displayTitle,
                subtitle = item.subtitle,
                position = position,
                duration = duration,
                playing = playing,
                transcoding = transcoding,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        error?.let { MessageBox(it) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun VodOverlay(
    title: String,
    subtitle: String?,
    position: Long,
    duration: Long,
    playing: Boolean,
    transcoding: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(JptvColors.Accent),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${formatTime(position)} / ${formatTime(duration)}")
            Spacer(Modifier.width(24.dp))
            if (!playing) Text("Pausad", color = JptvColors.Accent)
            Spacer(Modifier.weight(1f))
            Text(
                if (transcoding) "Omkodas av Plex" else "Direct Play",
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "OK = paus · ◀ −10 s · ▶ +30 s · Tillbaka = avsluta",
            color = JptvColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ======================================================================
// Live-TV från IPTV-listan
// ======================================================================

@OptIn(UnstableApi::class)
@Composable
private fun LivePlayer(vm: AppViewModel, request: PlayRequest.Live) {
    val channels = request.channels
    val exo = rememberExoPlayer()
    val focus = remember { FocusRequester() }

    var index by remember { mutableIntStateOf(request.index.coerceIn(0, channels.lastIndex)) }
    var error by remember { mutableStateOf<String?>(null) }
    var bannerUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var retries by remember { mutableIntStateOf(0) }

    fun tune(i: Int) {
        val ch = channels[i]
        error = null
        retries = 0
        bannerUntil = System.currentTimeMillis() + 4_000
        vm.prefs.lastChannelUrl = ch.url
        exo.setMediaSource(liveSource(ch))
        exo.prepare()
        exo.play()
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    return
                }
                // Tillfälliga nätfel: försök igen ett par gånger innan vi visar fel.
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
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    LaunchedEffect(index) { tune(index) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val channel = channels[index]
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                val native = ev.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val count = channels.size
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        index = (index + 1) % count
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        index = (index - 1 + count) % count
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (error != null) tune(index) else bannerUntil = System.currentTimeMillis() + 4_000
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                        bannerUntil = System.currentTimeMillis() + 4_000
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        vm.back()
                        true
                    }
                    else -> false
                }
            },
    ) {
        VideoSurface(exo)
        if (now < bannerUntil || error != null) {
            ChannelBanner(
                number = index + 1,
                channel = channel,
                error = error,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun ChannelBanner(number: Int, channel: Channel, error: String?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(channel, size = 72)
        Spacer(Modifier.width(24.dp))
        Column {
            Text("$number  ${channel.name}", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(channel.group, color = JptvColors.TextDim)
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text("$error · Tryck OK för att försöka igen", color = JptvColors.Error)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "▲▼ byt kanal · OK visa info · Tillbaka till kanallistan",
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
