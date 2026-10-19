package se.jptv.player

import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import se.jptv.AppViewModel
import se.jptv.PlayRequest
import se.jptv.Screen
import se.jptv.iptv.Channel
import se.jptv.iptv.IptvRepository
import se.jptv.plex.PlexItem
import se.jptv.plex.PlexStream
import se.jptv.ui.ChannelLogo
import se.jptv.ui.ChannelRow
import se.jptv.ui.JptvColors
import se.jptv.ui.MessageBox
import se.jptv.ui.ProgressBar
import se.jptv.ui.clock
import se.jptv.ui.formatTime
import se.jptv.ui.requestWhenReady
import java.util.Locale
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

private fun subtitleMime(codec: String?): String? = when (codec?.lowercase()) {
    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    "ttml" -> MimeTypes.APPLICATION_TTML
    else -> null
}

@OptIn(UnstableApi::class)
private fun plexSource(
    url: String,
    headers: Map<String, String>,
    hls: Boolean,
    subtitles: List<MediaItem.SubtitleConfiguration> = emptyList(),
): MediaSource {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headers)
    val item = MediaItem.Builder().setUri(url).apply {
        if (hls) setMimeType(MimeTypes.APPLICATION_M3U8)
        if (subtitles.isNotEmpty()) setSubtitleConfigurations(subtitles)
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

private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
    code == KeyEvent.KEYCODE_NUMPAD_ENTER

// ======================================================================
// Film och avsnitt från Plex
// ======================================================================

@OptIn(UnstableApi::class)
@Composable
private fun VodPlayer(vm: AppViewModel, request: PlayRequest.Vod) {
    val exo = rememberExoPlayer()
    val session = remember { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var item by remember { mutableStateOf(request.item) }
    var startAt by remember { mutableLongStateOf(if (request.fromStart) 0L else request.item.viewOffsetMs) }
    var started by remember { mutableStateOf(false) }
    var transcoding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(startAt) }
    var duration by remember { mutableLongStateOf(request.item.durationMs) }
    var playing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var nextEpisode by remember { mutableStateOf<PlexItem?>(null) }
    var countdown by remember { mutableIntStateOf(-1) }
    var countdownDismissed by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var tracksVersion by remember { mutableIntStateOf(0) }
    var everReady by remember { mutableStateOf(false) }
    var transcodeAttempts by remember { mutableIntStateOf(0) }

    val intro = item.intro
    val introActive = intro != null && position >= intro.startMs && position < intro.endMs - 1_000
    val countdownVisible = countdown >= 0

    fun switchToTranscode(atMs: Long) {
        if (transcodeAttempts >= 2) return
        transcodeAttempts++
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

    fun playNext() {
        val next = nextEpisode ?: return
        finished = true
        vm.replaceTop(Screen.Player(PlayRequest.Vod(next)))
    }

    fun dismissCountdown() {
        countdown = -1
        countdownDismissed = true
        if (exo.playbackState == Player.STATE_ENDED) vm.back()
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (transcodeAttempts < 2) {
                    // Första felet: byt till Plex-omkodning. Andra: starta en ny omkodning (t.ex. efter lång paus).
                    switchToTranscode(maxOf(exo.currentPosition, startAt))
                } else {
                    error = "Kan inte spela upp (${e.errorCodeName})"
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
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
                if (playbackState == Player.STATE_READY) everReady = true
                if (playbackState == Player.STATE_ENDED) {
                    finished = true
                    if (nextEpisode != null && !countdownDismissed) {
                        if (countdown < 0) countdown = 10
                    } else {
                        vm.back()
                    }
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            val pos = exo.currentPosition
            val dur = if (exo.duration > 0) exo.duration else item.durationMs
            val transcodeSession = if (transcoding) session else null
            if (everReady) {
                vm.reportStopped(item, pos, dur, transcodeSession, finished)
            } else {
                // Inget hann spelas: skriv inte över positionen i Plex med 0.
                vm.abandonPlayback(transcodeSession)
            }
            exo.release()
        }
    }

    // Hämta färsk information (spår, markörer, position) och starta uppspelningen.
    LaunchedEffect(Unit) {
        val fresh = withTimeoutOrNull(5_000) {
            try {
                vm.plex.metadata(request.item.ratingKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val meta = fresh ?: request.item
        item = meta
        startAt = if (request.fromStart) 0L else meta.viewOffsetMs
        applyPlexTrackPreferences(exo, meta)
        val direct = vm.plex.directPlayUrl(meta)
        if (direct != null) {
            val subs = meta.streams.filter { it.isExternalSubtitle }.mapNotNull { externalSubtitle(vm, it) }
            exo.setMediaSource(plexSource(direct, vm.plex.streamHeaders(), hls = false, subtitles = subs), startAt)
            exo.prepare()
        } else {
            switchToTranscode(startAt)
        }
        started = true
        if (meta.type == "episode") {
            nextEpisode = try {
                vm.plex.nextEpisode(meta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    // Tid på skärmen, göm infon efter en stund, eftertexter -> nästa avsnitt, rapport till Plex.
    LaunchedEffect(exo) {
        var ticks = 0
        while (true) {
            position = exo.currentPosition
            if (exo.duration > 0) duration = exo.duration
            if (overlayVisible && exo.isPlaying && System.currentTimeMillis() - lastKeyAt > 5_000) {
                overlayVisible = false
            }
            val credits = item.credits
            if (nextEpisode != null && !countdownDismissed && countdown < 0 && credits != null &&
                position >= credits.startMs && exo.isPlaying
            ) {
                countdown = 15
            }
            if (everReady && ticks % 20 == 0 && exo.playbackState == Player.STATE_READY) {
                vm.reportProgress(
                    item, if (exo.isPlaying) "playing" else "paused", position, duration,
                    if (transcoding) session else null,
                )
            }
            ticks++
            delay(500)
        }
    }

    // Nedräkning till nästa avsnitt.
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            countdown -= 1
        } else if (countdown == 0) {
            playNext()
        }
    }

    // Hem-knappen eller skärmsläckare: pausa, så att ljudet inte fortsätter i bakgrunden.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        exo.pause()
        if (everReady) {
            vm.reportProgress(item, "paused", exo.currentPosition, duration, if (transcoding) session else null)
        }
    }

    BackHandler(enabled = panelOpen || countdownVisible) {
        if (panelOpen) panelOpen = false else dismissCountdown()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                // Öppen panel eller nedräkning: låt knapparna styra fokus där istället.
                if (panelOpen || countdownVisible) return@onKeyEvent false
                val native = ev.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val code = native.keyCode
                val handled = when {
                    isOk(code) && introActive && intro != null -> {
                        exo.seekTo(intro.endMs)
                        true
                    }
                    isOk(code) || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_SPACE -> {
                        if (exo.isPlaying) exo.pause() else exo.play()
                        true
                    }
                    code == KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        exo.play()
                        true
                    }
                    code == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        exo.pause()
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        // Håll inne för att spola snabbare.
                        val step = if (native.repeatCount > 10) 60_000 else 10_000
                        exo.seekTo((exo.currentPosition - step).coerceAtLeast(0))
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val step = if (native.repeatCount > 10) 120_000 else 30_000
                        val target = exo.currentPosition + step
                        val max = if (exo.duration > 0) exo.duration - 2_000 else target
                        exo.seekTo(minOf(target, max))
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_MENU ||
                        code == KeyEvent.KEYCODE_CAPTIONS -> {
                        panelOpen = true
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_INFO -> true
                    code == KeyEvent.KEYCODE_MEDIA_NEXT && nextEpisode != null -> {
                        playNext()
                        true
                    }
                    code == KeyEvent.KEYCODE_MEDIA_STOP -> {
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
        if (!started) MessageBox("Startar…")
        if ((overlayVisible || !playing) && !panelOpen && !countdownVisible) {
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
        if (introActive && !panelOpen && !countdownVisible) {
            Chip("OK = Hoppa över intro", Modifier.align(Alignment.TopEnd).padding(32.dp))
        }
        if (panelOpen) {
            TrackPanel(
                exo = exo,
                version = tracksVersion,
                onClose = { panelOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        val next = nextEpisode
        if (countdownVisible && next != null) {
            NextEpisodeCard(
                next = next,
                seconds = countdown,
                onPlayNow = { playNext() },
                onCancel = { dismissCountdown() },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        error?.let { MessageBox(it) }
    }
    LaunchedEffect(panelOpen, countdownVisible) {
        if (!panelOpen && !countdownVisible) focus.requestWhenReady()
    }
}

@OptIn(UnstableApi::class)
private fun applyPlexTrackPreferences(exo: ExoPlayer, item: PlexItem) {
    val audio = item.streams.firstOrNull { it.type == 2 && it.selected }?.languageCode
    val sub = item.streams.firstOrNull { it.type == 3 && it.selected }
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().apply {
        if (audio != null) setPreferredAudioLanguage(audio)
        val subLang = sub?.languageCode
        if (subLang != null) {
            setPreferredTextLanguage(subLang)
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
    }.build()
}

private fun externalSubtitle(vm: AppViewModel, stream: PlexStream): MediaItem.SubtitleConfiguration? {
    val url = vm.plex.subtitleUrl(stream) ?: return null
    val mime = subtitleMime(stream.codec) ?: return null
    return MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
        .setMimeType(mime)
        .setLanguage(stream.languageCode)
        .setLabel(stream.title)
        .setSelectionFlags(if (stream.selected) C.SELECTION_FLAG_DEFAULT else 0)
        .build()
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        style = MaterialTheme.typography.titleMedium,
    )
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
            if (duration > 0) {
                Spacer(Modifier.width(16.dp))
                Text("slutar ${clock(System.currentTimeMillis() + duration - position)}", color = JptvColors.TextDim)
            }
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
            "OK paus · ◀ −10 s · ▶ +30 s (håll för snabbare) · ▲ ljud & undertext · Tillbaka avsluta",
            color = JptvColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private data class TrackChoice(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean)

@OptIn(UnstableApi::class)
private fun formatLabel(format: Format, fallback: String): String {
    val language = format.language
        ?.takeIf { it != C.LANGUAGE_UNDETERMINED && it != "und" }
        ?.let { code -> Locale(code).getDisplayLanguage(Locale("sv", "SE")).replaceFirstChar { it.uppercase() } }
    val codec = when (format.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.APPLICATION_PGS -> "PGS"
        else -> null
    }
    val channels = when (format.channelCount) {
        Format.NO_VALUE -> null
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${format.channelCount} kanaler"
    }
    return listOfNotNull(language ?: fallback, format.label?.takeIf { it != language }, codec, channels)
        .distinct()
        .joinToString(" · ")
}

private fun trackChoices(tracks: Tracks, type: Int): List<TrackChoice> {
    val result = mutableListOf<TrackChoice>()
    var n = 1
    for (group in tracks.groups) {
        if (group.type != type) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            result += TrackChoice(formatLabel(group.getTrackFormat(i), "Spår $n"), group, i, group.isTrackSelected(i))
            n++
        }
    }
    return result
}

/** Panel för ljudspår och undertexter. ▲ öppnar, Tillbaka stänger. */
@OptIn(UnstableApi::class)
@Composable
private fun TrackPanel(exo: ExoPlayer, version: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val tracks = remember(version) { exo.currentTracks }
    val audio = remember(tracks) { trackChoices(tracks, C.TRACK_TYPE_AUDIO) }
    val text = remember(tracks) { trackChoices(tracks, C.TRACK_TYPE_TEXT) }
    val textOff = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.selected }
    val firstFocus = remember { FocusRequester() }

    fun select(type: Int, choice: TrackChoice?) {
        val builder = exo.trackSelectionParameters.buildUpon()
        if (choice == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
            builder.setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
        }
        exo.trackSelectionParameters = builder.build()
        onClose()
    }

    Row(
        modifier
            .fillMaxHeight()
            .width(760.dp)
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ljud", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (audio.isEmpty()) Text("Inga ljudspår", color = JptvColors.TextDim)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(audio) { i, choice ->
                    ListItem(
                        selected = choice.selected,
                        onClick = { select(C.TRACK_TYPE_AUDIO, choice) },
                        headlineContent = { Text(choice.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Undertexter", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    ListItem(
                        selected = textOff,
                        onClick = { select(C.TRACK_TYPE_TEXT, null) },
                        headlineContent = { Text("Av") },
                        modifier = if (audio.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                itemsIndexed(text) { _, choice ->
                    ListItem(
                        selected = choice.selected && !textOff,
                        onClick = { select(C.TRACK_TYPE_TEXT, choice) },
                        headlineContent = { Text(choice.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestWhenReady() }
}

@Composable
private fun NextEpisodeCard(
    next: PlexItem,
    seconds: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playFocus = remember { FocusRequester() }
    Column(
        modifier
            .padding(40.dp)
            .width(460.dp)
            .background(Color.Black.copy(alpha = 0.85f), MaterialTheme.shapes.medium)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Nästa avsnitt om $seconds s", color = JptvColors.Accent, style = MaterialTheme.typography.titleMedium)
        Text(next.subtitle ?: next.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlayNow, modifier = Modifier.focusRequester(playFocus)) { Text("Spela nu") }
            OutlinedButton(onClick = onCancel) { Text("Avbryt") }
        }
    }
    LaunchedEffect(Unit) { playFocus.requestWhenReady() }
}

// ======================================================================
// Live-TV från IPTV-listan
// ======================================================================

@OptIn(UnstableApi::class)
@Composable
private fun LivePlayer(vm: AppViewModel, request: PlayRequest.Live) {
    val exo = rememberExoPlayer()
    val focus = remember { FocusRequester() }

    var channels by remember { mutableStateOf(request.channels) }
    var index by remember { mutableIntStateOf(request.index.coerceIn(0, request.channels.lastIndex)) }
    var previousKey by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bannerUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var retries by remember { mutableIntStateOf(0) }
    var listOpen by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }

    val channel = channels[index]

    fun showBanner() {
        bannerUntil = System.currentTimeMillis() + 5_000
    }

    fun switchTo(newChannels: List<Channel>, newIndex: Int) {
        if (newChannels.isEmpty()) return
        previousKey = channels.getOrNull(index)?.key
        channels = newChannels
        index = newIndex.coerceIn(0, newChannels.lastIndex)
    }

    /** Hoppa till en kanal via nummer eller nyckel; utanför gruppen byts listan till hela spellistan. */
    fun jumpTo(target: Channel) {
        val inList = channels.indexOfFirst { it.key == target.key }
        if (inList >= 0) {
            switchTo(channels, inList)
        } else {
            val all = vm.playlist?.channels ?: return
            switchTo(all, (target.number - 1).coerceAtLeast(0))
        }
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
                // IPTV-strömmar stänger ibland anslutningen; ExoPlayer ser det som "slut". Anslut igen.
                if (playbackState == Player.STATE_ENDED) {
                    if (retries < 3) {
                        retries++
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    } else {
                        error = "Strömmen tog slut"
                    }
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // Hem-knappen: stoppa strömmen (den tar en anslutning hos leverantören), starta igen vid återkomst.
    // ON_START skickas även när skärmen öppnas, därför bara omstart efter ett riktigt stopp.
    var stoppedInBackground by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        stoppedInBackground = true
        exo.stop()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (stoppedInBackground) {
            stoppedInBackground = false
            retries = 0
            exo.prepare()
            exo.play()
        }
    }

    // Ny kanal vald -> starta den.
    LaunchedEffect(channel.key) {
        error = null
        retries = 0
        showBanner()
        vm.onChannelTuned(channel)
        exo.setMediaSource(liveSource(channel))
        exo.prepare()
        exo.play()
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    // Sifferknappar: vänta 1,5 s efter sista siffran och byt sedan till det kanalnumret.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val number = digits.toIntOrNull()
        digits = ""
        val target = number?.let { vm.playlist?.byNumber(it) }
        if (target != null) jumpTo(target) else showBanner()
    }

    BackHandler(enabled = listOpen) { listOpen = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (listOpen) return@onKeyEvent false
                val native = ev.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val code = native.keyCode
                val count = channels.size
                when {
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP -> {
                        switchTo(channels, (index + 1) % count)
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        switchTo(channels, (index - 1 + count) % count)
                        true
                    }
                    isOk(code) -> {
                        if (error != null) {
                            retries = 0
                            error = null
                            exo.prepare()
                        } else {
                            listOpen = true
                        }
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_LAST_CHANNEL -> {
                        val prev = previousKey?.let { key -> vm.playlist?.byKey?.get(key) }
                        if (prev != null) jumpTo(prev) else showBanner()
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_INFO ||
                        code == KeyEvent.KEYCODE_GUIDE -> {
                        showBanner()
                        true
                    }
                    code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_BOOKMARK -> {
                        vm.toggleFavorite(channel)
                        showBanner()
                        true
                    }
                    code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        if (digits.length < 4) digits += (code - KeyEvent.KEYCODE_0).toString()
                        true
                    }
                    code == KeyEvent.KEYCODE_MEDIA_STOP -> {
                        vm.back()
                        true
                    }
                    else -> false
                }
            },
    ) {
        VideoSurface(exo)
        if ((now < bannerUntil || error != null) && !listOpen) {
            ChannelBanner(
                vm = vm,
                channel = channel,
                now = now,
                error = error,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        if (digits.isNotEmpty()) {
            Text(
                digits,
                fontSize = 56.sp,
                color = JptvColors.Accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(40.dp)
                    .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (listOpen) {
            ChannelListOverlay(
                vm = vm,
                channels = channels,
                currentIndex = index,
                now = now,
                onSelect = { i ->
                    switchTo(channels, i)
                    listOpen = false
                },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
    LaunchedEffect(listOpen) {
        if (!listOpen) focus.requestWhenReady()
    }
}

@Composable
private fun ChannelListOverlay(
    vm: AppViewModel,
    channels: List<Channel>,
    currentIndex: Int,
    now: Long,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    Column(
        modifier
            .fillMaxHeight()
            .width(680.dp)
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.75f))))
            .padding(start = 32.dp, top = 28.dp, end = 24.dp),
    ) {
        Text("Kanaler", style = MaterialTheme.typography.titleLarge)
        Text(
            "OK byt kanal · håll inne OK för favorit · Tillbaka stäng",
            color = JptvColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(channels, key = { i, ch -> "${i}_${ch.key}" }) { i, ch ->
                ChannelRow(
                    channel = ch,
                    epg = vm.epg,
                    now = now,
                    favorite = vm.isFavorite(ch),
                    onClick = { onSelect(i) },
                    onLongClick = { vm.toggleFavorite(ch) },
                    modifier = if (i == currentIndex) Modifier.focusRequester(currentFocus) else Modifier,
                )
            }
        }
    }
    LaunchedEffect(Unit) { currentFocus.requestWhenReady() }
}

@Composable
private fun ChannelBanner(vm: AppViewModel, channel: Channel, now: Long, error: String?, modifier: Modifier = Modifier) {
    val current = vm.epg?.now(channel, now)
    val next = vm.epg?.next(channel, now)
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
            .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(channel, size = 80)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${channel.number}  ${channel.name}${if (vm.isFavorite(channel)) "  ★" else ""}",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(channel.group, color = JptvColors.TextDim)
            if (current != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${clock(current.start)}–${clock(current.stop)}", color = JptvColors.Accent)
                    Spacer(Modifier.width(12.dp))
                    Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                ProgressBar(current.progress(now), Modifier.width(420.dp))
                current.desc?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = JptvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (next != null) {
                Spacer(Modifier.height(4.dp))
                Text("Sedan ${clock(next.start)}  ${next.title}", color = JptvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text("$error · Tryck OK för att försöka igen", color = JptvColors.Error)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "OK kanallista · ▲▼ byt kanal · ◀ förra kanalen · siffror = kanalnummer · ☰ favorit",
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
