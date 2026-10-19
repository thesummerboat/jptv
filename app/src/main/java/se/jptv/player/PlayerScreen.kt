package se.jptv.player

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import se.jptv.AppViewModel
import se.jptv.PlayRequest
import se.jptv.Screen
import se.jptv.iptv.Channel
import se.jptv.iptv.IptvRepository
import se.jptv.plex.PlexItem
import se.jptv.plex.PlexStream
import se.jptv.plex.SubtitleResult
import se.jptv.ui.ChannelLogo
import se.jptv.ui.ChannelRow
import se.jptv.ui.JptvColors
import se.jptv.ui.MessageBox
import se.jptv.ui.ProgressBar
import se.jptv.ui.clock
import se.jptv.ui.formatTime
import se.jptv.ui.requestWhenReady
import java.io.File
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

/** scale: 0 = anpassa, 1 = fyll (sträck), 2 = zooma (beskär). */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(exo: ExoPlayer, scale: Int) {
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
        update = { view ->
            view.resizeMode = when (scale) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
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

private fun isSrt(codec: String?): Boolean = codec?.lowercase().let { it == "srt" || it == "subrip" }

@OptIn(UnstableApi::class)
private fun plexSource(
    context: Context,
    url: String,
    headers: Map<String, String>,
    hls: Boolean,
    subtitles: List<MediaItem.SubtitleConfiguration> = emptyList(),
): MediaSource {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headers)
    // DefaultDataSource klarar även lokala filer (synkade undertexter i cachen).
    val data = DefaultDataSource.Factory(context, http)
    val item = MediaItem.Builder().setUri(url).apply {
        if (hls) setMimeType(MimeTypes.APPLICATION_M3U8)
        if (subtitles.isNotEmpty()) setSubtitleConfigurations(subtitles)
    }.build()
    return if (hls) {
        HlsMediaSource.Factory(data).createMediaSource(item)
    } else {
        DefaultMediaSourceFactory(data).createMediaSource(item)
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

/** Språkkod som tre bokstäver ("sv" och "swe" blir båda "swe"), så att Plex och spelaren kan jämföras. */
private fun iso3(code: String?): String? {
    if (code.isNullOrBlank() || code == "und" || code == C.LANGUAGE_UNDETERMINED) return null
    return try {
        Locale(code.substringBefore('-')).isO3Language.lowercase().ifEmpty { code.lowercase() }
    } catch (e: java.util.MissingResourceException) {
        code.lowercase()
    }
}

private enum class VodPanel { NONE, TRACKS, SEARCH, SYNC, OPTIONS }

// ======================================================================
// Film och avsnitt från Plex
// ======================================================================

@OptIn(UnstableApi::class)
@Composable
private fun VodPlayer(vm: AppViewModel, request: PlayRequest.Vod) {
    val context = LocalContext.current
    val exo = rememberExoPlayer()
    var session by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var item by remember { mutableStateOf(request.item) }
    /** Trailer: rör inte position, sett-status eller språkval. */
    val isTrailer = request.trailer
    var startAt by remember { mutableLongStateOf(if (request.fromStart) 0L else request.item.viewOffsetMs) }
    var started by remember { mutableStateOf(false) }
    var transcoding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(startAt) }
    var duration by remember { mutableLongStateOf(request.item.durationMs) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var playing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var nextEpisode by remember { mutableStateOf<PlexItem?>(null) }
    var countdown by remember { mutableIntStateOf(-1) }
    var countdownDismissed by remember { mutableStateOf(false) }
    var stillWatching by remember { mutableStateOf(false) }
    var interacted by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(VodPanel.NONE) }
    var tracksVersion by remember { mutableIntStateOf(0) }
    var everReady by remember { mutableStateOf(false) }
    var transcodeAttempts by remember { mutableIntStateOf(0) }
    /** Efter (om)laddning: välj exakt de spår som Plex har markerat, inte bara rätt språk. */
    var needsExactSelection by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Automatisk bildfrekvens: från Plex före start, annars uppmätt från de första bildrutorna.
    var switchingMode by remember { mutableStateOf(false) }
    var fpsHandled by remember { mutableStateOf(false) }
    var measuredFps by remember { mutableStateOf<Float?>(null) }
    /** Bildfrekvensen som gäller, för att byta tillbaka om man lämnat appen och kommer tillbaka. */
    var videoFps by remember { mutableStateOf<Float?>(null) }
    val fpsMeter = remember { FpsMeter { measuredFps = it } }

    // Spolning: ◀/▶ flyttar en markör med förhandsbild; hoppet görs när man släpper (eller trycker OK).
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    val thumbs = remember(item.partId, item.hasPreviews) {
        val partId = item.partId
        if (item.hasPreviews && partId != null) PreviewThumbnails(context, vm.plex, partId) else null
    }

    // Manuell undertextsynk: förskjutning per undertext och den synkade kopian i cachen.
    var syncTarget by remember { mutableStateOf<PlexStream?>(null) }
    val subOffsets = remember { mutableStateMapOf<Long, Long>() }
    val appliedOffsets = remember { HashMap<Long, Long>() }
    val syncedFiles = remember { HashMap<Long, Uri>() }
    val srtCache = remember { HashMap<Long, String>() }

    val intro = item.intro
    val introActive = intro != null && position >= intro.startMs && position < intro.endMs - 1_000
    val countdownVisible = countdown >= 0
    val cardVisible = countdownVisible || stillWatching
    val panelOpen = panel != VodPanel.NONE

    fun externalSubs(): List<MediaItem.SubtitleConfiguration> =
        item.streams.filter { it.isExternalSubtitle }.mapNotNull { externalSubtitle(vm, it, syncedFiles[it.id]) }

    /** Startar Direct Play från given position. false om filen saknar adress. */
    fun loadDirect(atMs: Long): Boolean {
        val direct = vm.plex.directPlayUrl(item) ?: return false
        needsExactSelection = true
        exo.setMediaSource(plexSource(context, direct, vm.plex.streamHeaders(), hls = false, subtitles = externalSubs()), atMs)
        exo.prepare()
        return true
    }

    fun switchToTranscode(atMs: Long) {
        if (transcodeAttempts >= 2) return
        transcodeAttempts++
        transcoding = true
        error = null
        scope.launch {
            try {
                val url = vm.plex.prepareTranscode(item, session)
                exo.setMediaSource(plexSource(context, url, vm.plex.streamHeaders(), hls = true), atMs)
                exo.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Plex kunde inte koda om filen: ${e.message}"
            }
        }
    }

    /** Ny omkodning med de spår som nu är valda i Plex (ljud byts och undertext bränns in). */
    fun restartTranscode(atMs: Long) {
        val old = session
        session = UUID.randomUUID().toString()
        transcoding = true
        error = null
        scope.launch {
            vm.plex.stopTranscode(old)
            try {
                val url = vm.plex.prepareTranscode(item, session)
                exo.setMediaSource(plexSource(context, url, vm.plex.streamHeaders(), hls = true), atMs)
                exo.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Plex kunde inte koda om filen: ${e.message}"
            }
        }
    }

    /**
     * Sparar ett spårval: i Plex (så att mobilen och andra appar ser samma sak) och per serie här,
     * så att nästa avsnitt startar med samma språk. off = undertexter av.
     */
    suspend fun saveSelection(type: Int, stream: PlexStream?, language: String?, off: Boolean) {
        if (isTrailer) return
        val current = item
        val plexType = if (type == C.TRACK_TYPE_AUDIO) 2 else 3
        if (stream != null || off) {
            item = current.copy(
                streams = current.streams.map { s ->
                    if (s.type == plexType) s.copy(selected = !off && s.id == stream?.id) else s
                },
            )
        }
        current.showKey?.let { show ->
            if (type == C.TRACK_TYPE_AUDIO) {
                if (language != null) vm.prefs.setTrackPreference(show, language, null)
            } else {
                vm.prefs.setTrackPreference(show, null, if (off) "off" else language)
            }
        }
        try {
            if (type == C.TRACK_TYPE_AUDIO) {
                if (stream != null) vm.plex.setSelectedStreams(current, audioStreamId = stream.id)
            } else {
                val subId = if (off) 0L else stream?.id
                if (subId != null) vm.plex.setSelectedStreams(current, subtitleStreamId = subId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Plex sparade inte valet; uppspelningen fortsätter ändå med det valda spåret.
        }
    }

    /** Val i spårpanelen vid Direct Play: byt direkt i spelaren och spara valet. */
    fun selectExo(type: Int, choice: TrackChoice?) {
        selectTrack(exo, type, choice)
        val stream = choice?.let { plexStreamFor(item, it, type) }
        val language = iso3(choice?.language ?: stream?.languageCode)
        panel = VodPanel.NONE
        scope.launch { saveSelection(type, stream, language, off = choice == null) }
    }

    /** Val i spårpanelen vid omkodning: spara i Plex och starta om omkodningen med det nya spåret. */
    fun selectPlex(type: Int, stream: PlexStream?) {
        panel = VodPanel.NONE
        val pos = exo.currentPosition
        scope.launch {
            saveSelection(type, stream, iso3(stream?.languageCode), off = stream == null)
            restartTranscode(pos)
        }
    }

    fun applyExactSelection(tracks: Tracks) {
        val builder = exo.trackSelectionParameters.buildUpon()
        var changed = false
        for ((type, plexType) in listOf(C.TRACK_TYPE_AUDIO to 2, C.TRACK_TYPE_TEXT to 3)) {
            val want = item.streams.firstOrNull { it.type == plexType && it.selected } ?: continue
            val choice = trackChoices(tracks, type).firstOrNull { plexStreamFor(item, it, type)?.id == want.id } ?: continue
            if (!choice.selected) {
                builder.setTrackTypeDisabled(type, false)
                builder.setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
                changed = true
            }
        }
        if (changed) exo.trackSelectionParameters = builder.build()
    }

    /** Flyttar en extern .srt i tiden: hämtar den, skriver en förskjuten kopia och laddar om. */
    fun applySync(stream: PlexStream, offsetMs: Long) {
        scope.launch {
            try {
                val url = vm.plex.subtitleUrl(stream) ?: return@launch
                val text = srtCache[stream.id] ?: vm.plex.downloadText(url).also { srtCache[stream.id] = it }
                val shifted = SubtitleSync.shiftSrt(text, offsetMs)
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "subs").apply { mkdirs() }
                    dir.listFiles()?.filter { it.name.startsWith("sub_${stream.id}_") }?.forEach { it.delete() }
                    File(dir, "sub_${stream.id}_${System.currentTimeMillis()}.srt").apply { writeText(shifted) }
                }
                syncedFiles[stream.id] = Uri.fromFile(file)
                loadDirect(exo.currentPosition)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast = "Kunde inte synka undertexten: ${e.message}"
            }
        }
    }

    /** auto = startat av nedräkningen. Räknas för "Tittar du fortfarande?". */
    fun playNext(auto: Boolean) {
        val next = nextEpisode ?: return
        finished = true
        val count = when {
            !auto -> 0
            interacted -> 1
            else -> request.autoplayCount + 1
        }
        vm.replaceTop(Screen.Player(PlayRequest.Vod(next, autoplayCount = count)))
    }

    /** Erbjud nästa avsnitt, eller fråga först om tre avsnitt har startat av sig själva i rad. */
    fun offerNext(seconds: Int) {
        if (nextEpisode == null || countdownDismissed || countdown >= 0 || stillWatching || vm.sleepAtEnd) return
        if (request.autoplayCount >= 3 && !interacted) stillWatching = true else countdown = seconds
    }

    fun dismissCountdown() {
        countdown = -1
        countdownDismissed = true
        if (exo.playbackState == Player.STATE_ENDED) vm.back()
    }

    fun scrubBy(deltaMs: Long) {
        val base = scrubTarget ?: exo.currentPosition
        val max = if (exo.duration > 0) exo.duration - 2_000 else Long.MAX_VALUE
        scrubTarget = (base + deltaMs).coerceIn(0L, max.coerceAtLeast(0L))
    }

    fun commitScrub() {
        val target = scrubTarget ?: return
        scrubTarget = null
        exo.seekTo(target)
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
                if (audioUnsupported || videoUnsupported) {
                    switchToTranscode(exo.currentPosition)
                    return
                }
                if (needsExactSelection) {
                    needsExactSelection = false
                    applyExactSelection(tracks)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) everReady = true
                if (playbackState == Player.STATE_ENDED) {
                    finished = true
                    when {
                        vm.sleepAtEnd -> {
                            vm.sleepAtEnd = false
                            vm.back()
                        }
                        nextEpisode != null && !countdownDismissed -> offerNext(10)
                        else -> vm.back()
                    }
                }
            }
        }
        exo.addListener(listener)
        exo.setVideoFrameMetadataListener(fpsMeter)
        onDispose {
            exo.removeListener(listener)
            exo.clearVideoFrameMetadataListener(fpsMeter)
            DisplayModes.release()
            val pos = exo.currentPosition
            val dur = if (exo.duration > 0) exo.duration else item.durationMs
            val transcodeSession = if (transcoding) session else null
            if (everReady && !isTrailer) {
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
        var meta = fresh ?: request.item
        // Språk som valts tidigare i samma serie gäller även det här avsnittet.
        val pref = if (isTrailer) null else meta.showKey?.let { vm.prefs.trackPreference(it) }
        val (audioId, subId) = selectionFromPreference(meta, pref)
        if (audioId != null || subId != null) {
            try {
                withTimeout(3_000) { vm.plex.setSelectedStreams(meta, audioId, subId) }
            } catch (e: TimeoutCancellationException) {
                // Plex svarade inte i tid; valet gäller ändå i spelaren.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Samma sak.
            }
            meta = meta.copy(
                streams = meta.streams.map { s ->
                    when {
                        s.type == 2 && audioId != null -> s.copy(selected = s.id == audioId)
                        s.type == 3 && subId != null -> s.copy(selected = s.id == subId)
                        else -> s
                    }
                },
            )
        }
        item = meta
        startAt = if (request.fromStart) 0L else meta.viewOffsetMs
        // Byt bildfrekvens innan bilden startar, så att det svarta bytet inte syns mitt i.
        val fps = meta.streams.firstOrNull { it.type == 1 }?.frameRate
        if (fps != null && fps > 0f) {
            fpsHandled = true
            videoFps = fps
            if (DisplayModes.needsSwitch(fps)) {
                switchingMode = true
                DisplayModes.switchTo(fps)
                switchingMode = false
            }
        }
        applyTrackPreferences(exo, meta)
        if (!loadDirect(startAt)) switchToTranscode(startAt)
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
            now = System.currentTimeMillis()
            if (exo.duration > 0) duration = exo.duration
            if (overlayVisible && exo.isPlaying && now - lastKeyAt > 5_000) {
                overlayVisible = false
            }
            val credits = item.credits
            if (credits != null && position >= credits.startMs && exo.isPlaying) offerNext(15)
            if (!isTrailer && everReady && ticks % 20 == 0 && exo.playbackState == Player.STATE_READY) {
                vm.reportProgress(
                    item, if (exo.isPlaying) "playing" else "paused", position, duration,
                    if (transcoding) session else null,
                )
            }
            ticks++
            delay(500)
        }
    }

    // Plex visste inte bildfrekvensen: byt efter mätningen, med pausad bild under bytet.
    LaunchedEffect(measuredFps) {
        val fps = measuredFps ?: return@LaunchedEffect
        if (fpsHandled) return@LaunchedEffect
        fpsHandled = true
        videoFps = fps
        if (DisplayModes.needsSwitch(fps)) {
            val wasPlaying = exo.playWhenReady
            exo.pause()
            switchingMode = true
            DisplayModes.switchTo(fps)
            switchingMode = false
            if (wasPlaying) exo.play()
        }
    }

    // Hoppa när man har slutat trycka en stund.
    LaunchedEffect(scrubTarget) {
        if (scrubTarget == null) return@LaunchedEffect
        delay(1_000)
        commitScrub()
    }

    DisposableEffect(thumbs) {
        onDispose { thumbs?.close() }
    }

    // Börja hämta förhandsbilderna i bakgrunden när uppspelningen kommit igång.
    LaunchedEffect(thumbs) {
        val t = thumbs ?: return@LaunchedEffect
        delay(8_000)
        runCatching { t.frame(exo.currentPosition) }
    }

    val previewKey = scrubTarget?.let { it / 2_000 }
    val preview by produceState<ImageBitmap?>(null, previewKey) {
        val target = scrubTarget
        val t = thumbs
        value = if (target == null || t == null) null else runCatching { t.frame(target) }.getOrNull() ?: value
    }

    // Nedräkning till nästa avsnitt.
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            countdown -= 1
        } else if (countdown == 0) {
            playNext(auto = true)
        }
    }

    // Undertextsynk: vänta tills knapptrycken har slutat innan vi laddar om.
    val syncId = syncTarget?.id
    val wantedOffset = syncId?.let { subOffsets[it] } ?: 0L
    LaunchedEffect(syncId, wantedOffset) {
        val target = syncTarget ?: return@LaunchedEffect
        if ((appliedOffsets[target.id] ?: 0L) == wantedOffset) return@LaunchedEffect
        delay(800)
        appliedOffsets[target.id] = wantedOffset
        applySync(target, wantedOffset)
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3_500)
            toast = null
        }
    }

    // Tillbaka till appen: TV:n gick till sitt vanliga läge när appen lämnades; byt till filmens igen.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val fps = videoFps ?: return@LifecycleEventEffect
        scope.launch { if (DisplayModes.needsSwitch(fps)) DisplayModes.switchTo(fps) }
    }

    // Hem-knappen eller skärmsläckare: pausa, så att ljudet inte fortsätter i bakgrunden.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        exo.pause()
        if (everReady && !isTrailer) {
            vm.reportProgress(item, "paused", exo.currentPosition, duration, if (transcoding) session else null)
        }
    }

    BackHandler(enabled = scrubTarget != null && !panelOpen) { scrubTarget = null }

    BackHandler(enabled = panelOpen || cardVisible) {
        when {
            panel == VodPanel.SEARCH || panel == VodPanel.SYNC -> panel = VodPanel.TRACKS
            panelOpen -> panel = VodPanel.NONE
            stillWatching -> vm.back()
            else -> dismissCountdown()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                // Öppen panel eller kort: låt knapparna styra fokus där istället.
                if (panelOpen || cardVisible) return@onKeyEvent false
                val native = ev.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val code = native.keyCode
                val handled = when {
                    isOk(code) && scrubTarget != null -> {
                        commitScrub()
                        true
                    }
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
                        scrubBy(if (native.repeatCount > 10) -60_000L else -10_000L)
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        scrubBy(if (native.repeatCount > 10) 120_000L else 30_000L)
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CAPTIONS -> {
                        panel = VodPanel.TRACKS
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MENU ||
                        code == KeyEvent.KEYCODE_INFO -> {
                        panel = VodPanel.OPTIONS
                        true
                    }
                    code == KeyEvent.KEYCODE_MEDIA_NEXT && nextEpisode != null -> {
                        playNext(auto = false)
                        true
                    }
                    code == KeyEvent.KEYCODE_MEDIA_STOP -> {
                        vm.back()
                        true
                    }
                    else -> false
                }
                if (handled) {
                    interacted = true
                    lastKeyAt = System.currentTimeMillis()
                    overlayVisible = true
                }
                handled
            },
    ) {
        VideoSurface(exo, vm.videoScale)
        if (!started) MessageBox("Startar…")
        if (switchingMode) MessageBox("Anpassar bildfrekvensen…")
        if ((overlayVisible || !playing || scrubTarget != null) && !panelOpen && !cardVisible) {
            VodOverlay(
                title = item.displayTitle,
                subtitle = item.subtitle,
                scrubTarget = scrubTarget,
                preview = preview,
                position = position,
                duration = duration,
                playing = playing,
                transcoding = transcoding,
                sleep = sleepLabel(vm, now),
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        if (vm.showStreamInfo && !panelOpen) {
            StreamInfo(
                exo = exo,
                mode = if (transcoding) "Omkodas av Plex (H.264/AAC)" else "Direct Play",
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        if (introActive && !panelOpen && !cardVisible) {
            Chip("OK = Hoppa över intro", Modifier.align(Alignment.TopEnd).padding(32.dp))
        }
        SleepTimer(
            vm = vm,
            onSleep = {
                exo.pause()
                vm.back()
            },
            modifier = Modifier.align(Alignment.TopCenter).padding(32.dp),
        )
        toast?.let { Chip(it, Modifier.align(Alignment.TopCenter).padding(top = 96.dp)) }

        when (panel) {
            VodPanel.NONE -> Unit
            VodPanel.TRACKS -> {
                val tracks = remember(tracksVersion) { exo.currentTracks }
                val searchOption = PanelOption("Sök undertexter…", detail = "Hämta från internet via Plex") {
                    panel = VodPanel.SEARCH
                }
                if (transcoding) {
                    // Omkodning: spåren väljs i Plex och omkodningen startas om.
                    val audioStreams = item.streams.filter { it.type == 2 }
                    val subStreams = item.streams.filter { it.type == 3 }
                    TrackPanel(
                        audio = PanelSection(
                            "Ljud",
                            audioStreams.map { s -> PanelOption(s.title, selected = s.selected) { selectPlex(C.TRACK_TYPE_AUDIO, s) } },
                            empty = "Inga ljudspår",
                        ),
                        text = PanelSection(
                            "Undertexter",
                            listOf(PanelOption("Av", selected = subStreams.none { it.selected }) { selectPlex(C.TRACK_TYPE_TEXT, null) }) +
                                subStreams.map { s -> PanelOption(s.title, selected = s.selected) { selectPlex(C.TRACK_TYPE_TEXT, s) } } +
                                searchOption,
                        ),
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                } else {
                    val audio = trackChoices(tracks, C.TRACK_TYPE_AUDIO)
                    val text = trackChoices(tracks, C.TRACK_TYPE_TEXT)
                    val textOff = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                        text.none { it.selected }
                    val currentText = text.firstOrNull { it.selected }.takeIf { !textOff }
                    val syncable = currentText?.plexId?.let { id ->
                        item.streams.firstOrNull { it.id == id && isSrt(it.codec) }
                    }
                    val textOptions = buildList {
                        add(PanelOption("Av", selected = textOff) { selectExo(C.TRACK_TYPE_TEXT, null) })
                        text.forEach { c -> add(PanelOption(c.label, selected = c.selected && !textOff) { selectExo(C.TRACK_TYPE_TEXT, c) }) }
                        add(searchOption)
                        if (syncable != null) {
                            add(
                                PanelOption("Synka undertext…", detail = SubtitleSync.label(subOffsets[syncable.id] ?: 0L)) {
                                    syncTarget = syncable
                                    panel = VodPanel.SYNC
                                },
                            )
                        }
                    }
                    TrackPanel(
                        audio = PanelSection(
                            "Ljud",
                            audio.map { c -> PanelOption(c.label, selected = c.selected) { selectExo(C.TRACK_TYPE_AUDIO, c) } },
                            empty = "Inga ljudspår",
                        ),
                        text = PanelSection("Undertexter", textOptions),
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            VodPanel.SEARCH -> SubtitleSearchPanel(
                vm = vm,
                item = item,
                onDownloaded = { fresh, stream ->
                    item = fresh
                    panel = VodPanel.NONE
                    val pos = exo.currentPosition
                    scope.launch {
                        saveSelection(C.TRACK_TYPE_TEXT, stream, iso3(stream.languageCode), off = false)
                        if (transcoding) {
                            restartTranscode(pos)
                        } else {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .apply { stream.languageCode?.let { setPreferredTextLanguage(it) } }
                                .build()
                            loadDirect(pos)
                        }
                        toast = "Undertexten är hämtad och vald"
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            VodPanel.SYNC -> {
                val target = syncTarget
                if (target == null) {
                    panel = VodPanel.TRACKS
                } else {
                    SyncPanel(
                        label = target.title,
                        offsetMs = subOffsets[target.id] ?: 0L,
                        onChange = { subOffsets[target.id] = it.coerceIn(-600_000L, 600_000L) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            VodPanel.OPTIONS -> OptionsPanel(
                title = "Alternativ",
                sections = listOf(
                    sleepSection(vm, now, includeEnd = true) { panel = VodPanel.NONE },
                    scaleSection(vm) { panel = VodPanel.NONE },
                    infoSection(vm) { panel = VodPanel.NONE },
                ),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        val next = nextEpisode
        if (countdownVisible && next != null) {
            NextEpisodeCard(
                next = next,
                seconds = countdown,
                onPlayNow = { playNext(auto = false) },
                onCancel = { dismissCountdown() },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        if (stillWatching) {
            StillWatchingCard(
                onContinue = { playNext(auto = false) },
                onStop = { vm.back() },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        error?.let { MessageBox(it) }
    }
    LaunchedEffect(panel, cardVisible) {
        if (panel == VodPanel.NONE && !cardVisible) focus.requestWhenReady()
    }
}

/** Översätter en sparad serieinställning till spår-id:n i Plex. (ljud, undertext där 0 = av), null = ingen ändring. */
private fun selectionFromPreference(item: PlexItem, pref: Pair<String?, String?>?): Pair<Long?, Long?> {
    if (pref == null || item.streams.isEmpty()) return null to null
    val (audioLang, subLang) = pref
    val audios = item.streams.filter { it.type == 2 }
    val selectedAudio = audios.firstOrNull { it.selected }
    val audioId = if (audioLang != null && iso3(selectedAudio?.languageCode) != audioLang) {
        audios.firstOrNull { iso3(it.languageCode) == audioLang }?.id
    } else null
    val subs = item.streams.filter { it.type == 3 }
    val selectedSub = subs.firstOrNull { it.selected }
    val subId = when {
        subLang == null -> null
        subLang == "off" -> if (selectedSub != null) 0L else null
        iso3(selectedSub?.languageCode) == subLang -> null
        else -> subs.firstOrNull { iso3(it.languageCode) == subLang }?.id
    }
    return audioId to subId
}

/** Startval efter Plex: språk för ljud och undertext, och inga undertexter om Plex säger "av". */
@OptIn(UnstableApi::class)
private fun applyTrackPreferences(exo: ExoPlayer, item: PlexItem) {
    if (item.streams.isEmpty()) return
    val audio = item.streams.firstOrNull { it.type == 2 && it.selected }?.languageCode
    val sub = item.streams.firstOrNull { it.type == 3 && it.selected }
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().apply {
        if (audio != null) setPreferredAudioLanguage(audio)
        if (sub == null) {
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            sub.languageCode?.let { setPreferredTextLanguage(it) }
        }
    }.build()
}

private fun externalSubtitle(vm: AppViewModel, stream: PlexStream, syncedFile: Uri?): MediaItem.SubtitleConfiguration? {
    val uri = syncedFile ?: vm.plex.subtitleUrl(stream)?.let { Uri.parse(it) } ?: return null
    val mime = if (syncedFile != null) MimeTypes.APPLICATION_SUBRIP else subtitleMime(stream.codec) ?: return null
    return MediaItem.SubtitleConfiguration.Builder(uri)
        .setId("plexsub-${stream.id}")
        .setMimeType(mime)
        .setLanguage(stream.languageCode)
        .setLabel(stream.title)
        .setSelectionFlags(if (stream.selected) C.SELECTION_FLAG_DEFAULT else 0)
        .build()
}

private val plexSubRegex = Regex("""plexsub-(\d+)""")

/** Plex-id för en extern undertext som vi själva lade till (id:t följer med i spårets format). */
@OptIn(UnstableApi::class)
private fun plexSubId(format: Format): Long? =
    format.id?.let { plexSubRegex.find(it)?.groupValues?.get(1)?.toLongOrNull() }

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
    scrubTarget: Long?,
    preview: ImageBitmap?,
    position: Long,
    duration: Long,
    playing: Boolean,
    transcoding: Boolean,
    sleep: String?,
    modifier: Modifier = Modifier,
) {
    val shown = scrubTarget ?: position
    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
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
        if (scrubTarget != null) {
            // Förhandsbild och tid ovanför markören på tidslinjen.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val bubble = if (preview != null) 256.dp else 180.dp
                val raw = maxWidth * fraction - bubble / 2
                val x = when {
                    raw < 0.dp -> 0.dp
                    raw > maxWidth - bubble -> maxWidth - bubble
                    else -> raw
                }
                Column(
                    Modifier
                        .offset(x = x)
                        .width(bubble),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (preview != null) {
                        Image(
                            bitmap = preview,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(bubble)
                                .height(144.dp)
                                .background(Color.Black, MaterialTheme.shapes.small),
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    val delta = scrubTarget - position
                    Text(
                        "${formatTime(scrubTarget)}  (${if (delta >= 0) "+" else "−"}${formatTime(kotlin.math.abs(delta))})",
                        style = MaterialTheme.typography.titleMedium,
                        color = JptvColors.Accent,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
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
            if (sleep != null) {
                Spacer(Modifier.width(24.dp))
                Text(sleep, color = JptvColors.Accent)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (transcoding) "Omkodas av Plex" else "Direct Play",
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (scrubTarget != null) "Släpp för att hoppa · OK hoppa direkt · Tillbaka avbryt"
            else "OK paus · ◀ −10 s · ▶ +30 s (håll för snabbare) · ▲ ljud & undertexter · ▼ sovtimer, bild & info",
            color = JptvColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------- Spårval ----------

/**
 * Ett spår i spelaren. ordinal = plats bland filens egna spår av samma typ (för att hitta motsvarande
 * spår i Plex), plexId = satt för externa undertexter som vi själva lagt till.
 */
private data class TrackChoice(
    val label: String,
    val group: Tracks.Group,
    val index: Int,
    val selected: Boolean,
    val language: String?,
    val plexId: Long?,
    val ordinal: Int,
    val embeddedCount: Int,
)

@OptIn(UnstableApi::class)
private fun formatLabel(format: Format, fallback: String): String {
    val language = format.language
        ?.takeIf { it != C.LANGUAGE_UNDETERMINED && it != "und" }
        ?.let { code -> Locale(code).getDisplayLanguage(Locale("sv", "SE")).replaceFirstChar { it.uppercase() } }
    // Undertexter som lästs in i förväg har typen "media3-cues"; ursprungsformatet ligger i codecs.
    val mime = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType
    val codec = codecName(mime)
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

@OptIn(UnstableApi::class)
private fun codecName(mime: String?): String? = when (mime) {
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos (DD+)"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.TEXT_SSA -> "ASS"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_VTT -> "WebVTT"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_VOBSUB -> "VobSub"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "HEVC"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    else -> null
}

@OptIn(UnstableApi::class)
private fun trackChoices(tracks: Tracks, type: Int): List<TrackChoice> {
    var embeddedCount = 0
    for (group in tracks.groups) {
        if (group.type != type) continue
        for (i in 0 until group.length) if (plexSubId(group.getTrackFormat(i)) == null) embeddedCount++
    }
    val result = mutableListOf<TrackChoice>()
    var n = 1
    var ordinal = 0
    for (group in tracks.groups) {
        if (group.type != type) continue
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val plexId = plexSubId(format)
            val ord = if (plexId == null) ordinal++ else -1
            if (!group.isTrackSupported(i)) continue
            result += TrackChoice(
                label = formatLabel(format, "Spår $n"),
                group = group,
                index = i,
                selected = group.isTrackSelected(i),
                language = format.language,
                plexId = plexId,
                ordinal = ord,
                embeddedCount = embeddedCount,
            )
            n++
        }
    }
    return result
}

/** Motsvarande spår i Plex: via id för externa undertexter, annars ordning eller språk. */
private fun plexStreamFor(item: PlexItem, choice: TrackChoice, type: Int): PlexStream? {
    choice.plexId?.let { id -> return item.streams.firstOrNull { it.id == id } }
    val plexType = if (type == C.TRACK_TYPE_AUDIO) 2 else 3
    val embedded = item.streams.filter { it.type == plexType && !it.isExternalSubtitle }
    if (embedded.size == choice.embeddedCount && choice.ordinal in embedded.indices) return embedded[choice.ordinal]
    val lang = iso3(choice.language) ?: return null
    return embedded.firstOrNull { iso3(it.languageCode) == lang }
}

private fun selectTrack(exo: ExoPlayer, type: Int, choice: TrackChoice?) {
    val builder = exo.trackSelectionParameters.buildUpon()
    if (choice == null) {
        builder.setTrackTypeDisabled(type, true)
    } else {
        builder.setTrackTypeDisabled(type, false)
        builder.setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
    }
    exo.trackSelectionParameters = builder.build()
}

// ---------- Paneler ----------

private class PanelOption(
    val label: String,
    val selected: Boolean = false,
    val detail: String? = null,
    val onClick: () -> Unit,
)

private class PanelSection(
    val title: String,
    val options: List<PanelOption>,
    val subtitle: String? = null,
    val empty: String? = null,
)

@Composable
private fun OptionItem(option: PanelOption, modifier: Modifier = Modifier) {
    val detail = option.detail
    ListItem(
        selected = option.selected,
        onClick = option.onClick,
        headlineContent = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (detail != null) {
            { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        trailingContent = if (option.selected) {
            { Text("✓", color = JptvColors.Accent) }
        } else null,
        modifier = modifier,
    )
}

/** Panel för ljudspår och undertexter. ▲ öppnar, Tillbaka stänger. */
@Composable
private fun TrackPanel(audio: PanelSection, text: PanelSection, modifier: Modifier = Modifier) {
    val firstFocus = remember { FocusRequester() }
    Row(
        modifier
            .fillMaxHeight()
            .width(760.dp)
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PanelColumn(audio, if (audio.options.isNotEmpty()) firstFocus else null, Modifier.weight(1f))
        PanelColumn(text, if (audio.options.isEmpty()) firstFocus else null, Modifier.weight(1f))
    }
    LaunchedEffect(Unit) { firstFocus.requestWhenReady() }
}

@Composable
private fun PanelColumn(section: PanelSection, focus: FocusRequester?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(section.title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (section.options.isEmpty() && section.empty != null) Text(section.empty, color = JptvColors.TextDim)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(section.options) { i, option ->
                OptionItem(option, if (i == 0 && focus != null) Modifier.focusRequester(focus) else Modifier)
            }
        }
    }
}

/** Lista med rubriker och val, t.ex. sovtimer och bildformat. */
@Composable
private fun OptionsPanel(title: String, sections: List<PanelSection>, modifier: Modifier = Modifier) {
    val firstFocus = remember { FocusRequester() }
    Column(
        modifier
            .fillMaxHeight()
            .width(460.dp)
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(start = 28.dp, top = 28.dp, end = 28.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            sections.forEachIndexed { si, section ->
                item(key = "h_$si") {
                    Column(Modifier.padding(top = if (si == 0) 0.dp else 16.dp, bottom = 4.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium, color = JptvColors.Accent)
                        section.subtitle?.let {
                            Text(it, color = JptvColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                itemsIndexed(section.options, key = { oi, _ -> "o_${si}_$oi" }) { oi, option ->
                    OptionItem(option, if (si == 0 && oi == 0) Modifier.focusRequester(firstFocus) else Modifier)
                }
            }
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestWhenReady() }
}

private fun sleepLabel(vm: AppViewModel, now: Long): String? {
    val at = vm.sleepAt
    return when {
        at != null -> "Sovtimer ${((at - now).coerceAtLeast(0) / 60_000) + 1} min"
        vm.sleepAtEnd -> "Sovtimer: stänger av efter detta"
        else -> null
    }
}

private fun sleepSection(vm: AppViewModel, now: Long, includeEnd: Boolean, done: () -> Unit): PanelSection {
    val active = vm.sleepAt != null || vm.sleepAtEnd
    val options = buildList {
        add(PanelOption("Av", selected = !active) { vm.setSleepTimer(null); done() })
        for (minutes in listOf(15, 30, 60, 90, 120)) {
            add(PanelOption("$minutes min") { vm.setSleepTimer(minutes); done() })
        }
        if (includeEnd) {
            add(PanelOption("När det här är slut", selected = vm.sleepAtEnd) { vm.setSleepAtEnd(); done() })
        }
    }
    return PanelSection("Sovtimer", options, subtitle = sleepLabel(vm, now))
}

private fun scaleSection(vm: AppViewModel, done: () -> Unit): PanelSection = PanelSection(
    "Bildformat",
    listOf("Anpassa" to 0, "Fyll skärmen" to 1, "Zooma" to 2).map { (label, value) ->
        PanelOption(label, selected = vm.videoScale == value) { vm.videoScale = value; done() }
    },
)

private fun infoSection(vm: AppViewModel, done: () -> Unit): PanelSection = PanelSection(
    "Information",
    listOf(
        PanelOption(
            if (vm.showStreamInfo) "Dölj teknisk info" else "Visa teknisk info",
            detail = "Upplösning, codec, bithastighet, buffert",
        ) {
            vm.showStreamInfo = !vm.showStreamInfo
            done()
        },
    ),
)

/** Stänger av när tiden är ute; varnar den sista minuten. Har en egen klocka, så resten av skärmen slipper rita om. */
@Composable
private fun SleepTimer(vm: AppViewModel, onSleep: () -> Unit, modifier: Modifier = Modifier) {
    val at = vm.sleepAt ?: return
    var left by remember(at) { mutableLongStateOf(at - System.currentTimeMillis()) }
    val currentOnSleep by rememberUpdatedState(onSleep)
    LaunchedEffect(at) {
        while (true) {
            left = at - System.currentTimeMillis()
            if (left <= 0) {
                vm.sleepAt = null
                currentOnSleep()
                break
            }
            delay(if (left > 65_000) minOf(left - 60_000, 30_000) else 1_000)
        }
    }
    if (left in 1..60_000) Chip("Sovtimern stänger av om ${left / 1000} s", modifier)
}

private val SUBTITLE_LANGUAGES = listOf(
    "sv" to "Svenska",
    "en" to "Engelska",
    "no" to "Norska",
    "da" to "Danska",
    "fi" to "Finska",
    "de" to "Tyska",
    "es" to "Spanska",
    "fr" to "Franska",
)

/** Sök undertexter via Plex (OpenSubtitles m.fl.), som "Sök" i Plex egna appar. */
@Composable
private fun SubtitleSearchPanel(
    vm: AppViewModel,
    item: PlexItem,
    onDownloaded: (PlexItem, PlexStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf(vm.focusKeys["subtitle_language"] ?: "sv") }
    var results by remember { mutableStateOf<List<SubtitleResult>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(language) {
        vm.focusKeys["subtitle_language"] = language
        results = emptyList()
        status = "Söker…"
        status = try {
            val found = vm.plex.searchSubtitles(item, language)
            results = found
            if (found.isEmpty()) "Inga undertexter hittades på det språket." else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Sökningen misslyckades: ${e.message}"
        }
    }

    fun download(result: SubtitleResult) {
        if (busy) return
        busy = true
        status = "Plex hämtar undertexten…"
        scope.launch {
            try {
                val before = (vm.plex.metadata(item.ratingKey) ?: item).streams
                    .filter { it.type == 3 }.map { it.id }.toSet()
                vm.plex.downloadSubtitle(item, result)
                // Servern hämtar i bakgrunden; vänta tills den dyker upp som ett nytt spår.
                var found: Pair<PlexItem, PlexStream>? = null
                for (attempt in 0 until 20) {
                    delay(1_000)
                    val fresh = vm.plex.metadata(item.ratingKey) ?: continue
                    val stream = fresh.streams.firstOrNull { it.type == 3 && it.isExternalSubtitle && it.id !in before }
                    if (stream != null) {
                        found = fresh to stream
                        break
                    }
                }
                val done = found
                if (done != null) {
                    onDownloaded(done.first, done.second)
                } else {
                    status = "Plex är inte klar än. Vänta en stund och öppna ▲ igen – den dyker upp i listan."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Kunde inte hämta: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Row(
        modifier
            .fillMaxHeight()
            .width(760.dp)
            .background(Color.Black.copy(alpha = 0.9f))
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.width(190.dp)) {
            Text("Språk", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(SUBTITLE_LANGUAGES) { _, (code, name) ->
                    ListItem(
                        selected = code == language,
                        onClick = { language = code },
                        headlineContent = { Text(name) },
                        modifier = if (code == language) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Sök undertexter", style = MaterialTheme.typography.titleLarge)
            Text("Via din Plex-server (OpenSubtitles m.fl.)", color = JptvColors.TextDim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            status?.let {
                Text(it, color = if (busy) JptvColors.Accent else JptvColors.TextDim)
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(results, key = { i, r -> "${i}_${r.key}" }) { _, r ->
                    val detail = listOfNotNull(
                        r.provider,
                        r.score?.let { "poäng $it" },
                        if (r.hearingImpaired) "för hörselskadade" else null,
                        r.codec?.uppercase(),
                    ).joinToString(" · ")
                    ListItem(
                        selected = false,
                        enabled = !busy,
                        onClick = { download(r) },
                        headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { firstFocus.requestWhenReady() }
}

/** Flytta undertexten i tiden, 0,1 eller 1 sekund åt gången. */
@Composable
private fun SyncPanel(label: String, offsetMs: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    Column(
        modifier
            .fillMaxHeight()
            .width(460.dp)
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Synka undertext", style = MaterialTheme.typography.titleLarge)
        Text(label, color = JptvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(SubtitleSync.label(offsetMs), fontSize = 44.sp, color = JptvColors.Accent)
        Text(
            when {
                offsetMs > 0 -> "Texten visas senare"
                offsetMs < 0 -> "Texten visas tidigare"
                else -> "Ingen förskjutning"
            },
            color = JptvColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(offsetMs - 1_000) }) { Text("−1 s") }
            OutlinedButton(onClick = { onChange(offsetMs - 100) }, modifier = Modifier.focusRequester(focus)) { Text("−0,1") }
            OutlinedButton(onClick = { onChange(offsetMs + 100) }) { Text("+0,1") }
            OutlinedButton(onClick = { onChange(offsetMs + 1_000) }) { Text("+1 s") }
        }
        OutlinedButton(onClick = { onChange(0) }) { Text("Nollställ") }
        Text(
            "Kommer texten för tidigt? Tryck +. För sent? Tryck −. Bilden laddar om en kort stund efter ändringen.",
            color = JptvColors.TextDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    LaunchedEffect(Unit) { focus.requestWhenReady() }
}

/** Teknisk information om strömmen, uppdateras varje sekund. */
@OptIn(UnstableApi::class)
@Composable
private fun StreamInfo(exo: ExoPlayer, mode: String, modifier: Modifier = Modifier) {
    val currentMode by rememberUpdatedState(mode)
    var lines by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(exo) {
        while (true) {
            lines = streamInfoLines(exo, currentMode)
            delay(1_000)
        }
    }
    Column(
        modifier
            .padding(28.dp)
            .background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.medium)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(UnstableApi::class)
private fun streamInfoLines(exo: ExoPlayer, mode: String): List<String> = buildList {
    add(mode)
    exo.videoFormat?.let { v ->
        val parts = mutableListOf<String>()
        if (v.width > 0 && v.height > 0) parts += "${v.width}×${v.height}"
        if (v.frameRate > 0) parts += String.format(Locale.US, "%.3f fps", v.frameRate).replace(".000", "")
        (codecName(v.sampleMimeType) ?: v.sampleMimeType)?.let { parts += it }
        when (v.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> parts += "HDR10"
            C.COLOR_TRANSFER_HLG -> parts += "HLG"
            else -> Unit
        }
        val bitrate = listOf(v.averageBitrate, v.bitrate, v.peakBitrate).firstOrNull { it > 0 }
        if (bitrate != null) parts += String.format(Locale.US, "%.1f Mbit/s", bitrate / 1_000_000f)
        add("Bild: " + parts.joinToString(" · "))
    }
    exo.audioFormat?.let { a ->
        val parts = mutableListOf<String>()
        (codecName(a.sampleMimeType) ?: a.sampleMimeType)?.let { parts += it }
        when (a.channelCount) {
            Format.NO_VALUE -> Unit
            1 -> parts += "mono"
            2 -> parts += "stereo"
            6 -> parts += "5.1"
            8 -> parts += "7.1"
            else -> parts += "${a.channelCount} kanaler"
        }
        if (a.sampleRate > 0) parts += "${a.sampleRate / 1000} kHz"
        a.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { parts += it }
        add("Ljud: " + parts.joinToString(" · "))
    }
    DisplayModes.currentModeText()?.let { add("Skärm: $it") }
    add(String.format(Locale.US, "Buffert: %.1f s", exo.totalBufferedDuration / 1000f))
    exo.videoDecoderCounters?.let { add("Tappade bildrutor: ${it.droppedBufferCount}") }
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

@Composable
private fun StillWatchingCard(onContinue: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val continueFocus = remember { FocusRequester() }
    Column(
        modifier
            .padding(40.dp)
            .width(460.dp)
            .background(Color.Black.copy(alpha = 0.88f), MaterialTheme.shapes.medium)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Tittar du fortfarande?", color = JptvColors.Accent, style = MaterialTheme.typography.titleLarge)
        Text(
            "Tre avsnitt har startat av sig själva. Nästa startar när du svarar.",
            color = JptvColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onContinue, modifier = Modifier.focusRequester(continueFocus)) { Text("Fortsätt titta") }
            OutlinedButton(onClick = onStop) { Text("Sluta") }
        }
    }
    LaunchedEffect(Unit) { continueFocus.requestWhenReady() }
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
    var bannerToken by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var firstTune by remember { mutableStateOf(true) }
    /** Kanalen som faktiskt skickats till spelaren (kan släpa efter vid snabb zappning). */
    var playingKey by remember { mutableStateOf<String?>(null) }
    var recordedKey by remember { mutableStateOf<String?>(null) }
    // Automatisk bildfrekvens för live: mäts på varje ny kanal; byter bara om kanalen kräver ett annat läge.
    var liveFps by remember { mutableStateOf<Float?>(null) }
    val liveFpsMeter = remember { FpsMeter { liveFps = it } }
    var retries by remember { mutableIntStateOf(0) }
    var listOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var tracksVersion by remember { mutableIntStateOf(0) }
    // OK-knappen: kort tryck = kanallista, håll inne = alternativ. Avgörs när knappen släpps.
    var okDown by remember { mutableStateOf(false) }
    var okLong by remember { mutableStateOf(false) }

    val channel = channels[index]

    fun showBanner() {
        now = System.currentTimeMillis()
        bannerToken++
    }

    fun switchTo(newChannels: List<Channel>, newIndex: Int) {
        if (newChannels.isEmpty()) return
        val target = newIndex.coerceIn(0, newChannels.lastIndex)
        val currentKey = channels.getOrNull(index)?.key
        // "Förra kanalen" ska bara ändras när kanalen verkligen byts.
        if (newChannels[target].key != currentKey) previousKey = currentKey
        channels = newChannels
        index = target
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

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    error = null
                    retries = 0
                    // Till "Senaste" först när kanalen faktiskt spelar, inte för varje kanal man zappar förbi.
                    val key = playingKey
                    if (key != null && key != recordedKey) {
                        recordedKey = key
                        channels.firstOrNull { it.key == key }?.let { vm.onChannelTuned(it) }
                    }
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
        exo.setVideoFrameMetadataListener(liveFpsMeter)
        onDispose {
            exo.removeListener(listener)
            exo.clearVideoFrameMetadataListener(liveFpsMeter)
            DisplayModes.release()
            exo.release()
        }
    }

    LaunchedEffect(liveFps) {
        val fps = liveFps ?: return@LaunchedEffect
        if (DisplayModes.needsSwitch(fps)) DisplayModes.switchTo(fps)
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
            // TV:n gick till sitt vanliga läge när appen lämnades; mät kanalen igen.
            liveFps = null
            liveFpsMeter.reset()
        }
    }

    // Ny kanal vald -> starta den. Vid snabb zappning (håll ▲/▼) ansluts bara kanalen man stannar på:
    // många leverantörer tillåter en ström åt gången och spärrar vid för många anslutningar.
    LaunchedEffect(channel.key) {
        error = null
        retries = 0
        showBanner()
        vm.focusKeys["live_channel"] = channel.key
        if (!firstTune) delay(350)
        firstTune = false
        playingKey = channel.key
        liveFpsMeter.reset()
        exo.setMediaSource(liveSource(channel))
        exo.prepare()
        exo.play()
    }

    // Banner i fem sekunder efter varje kanalbyte eller knapptryck.
    LaunchedEffect(bannerToken) {
        bannerVisible = true
        delay(5_000)
        bannerVisible = false
    }

    // Klockan för guiden behöver inte gå oftare än så; hela skärmen ritas om vid varje tick.
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
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

    BackHandler(enabled = listOpen || optionsOpen) {
        listOpen = false
        optionsOpen = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (listOpen || optionsOpen) return@onKeyEvent false
                val native = ev.nativeKeyEvent
                val code = native.keyCode
                if (isOk(code)) {
                    when (native.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (native.repeatCount == 0) okDown = true
                            if (native.repeatCount > 0 || native.isLongPress) okLong = true
                        }
                        KeyEvent.ACTION_UP -> {
                            if (okDown) {
                                when {
                                    okLong -> optionsOpen = true
                                    error != null -> {
                                        // Ny källa: prepare() gör inget om strömmen redan har tagit slut.
                                        retries = 0
                                        error = null
                                        exo.setMediaSource(liveSource(channel))
                                        exo.prepare()
                                        exo.play()
                                    }
                                    else -> listOpen = true
                                }
                            }
                            okDown = false
                            okLong = false
                        }
                    }
                    return@onKeyEvent true
                }
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
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
                    code == KeyEvent.KEYCODE_MENU -> {
                        optionsOpen = true
                        true
                    }
                    code == KeyEvent.KEYCODE_BOOKMARK -> {
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
        VideoSurface(exo, vm.videoScale)
        if ((bannerVisible || error != null) && !listOpen && !optionsOpen) {
            ChannelBanner(
                vm = vm,
                channel = channel,
                now = now,
                error = error,
                sleep = sleepLabel(vm, now),
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        if (vm.showStreamInfo && !optionsOpen) {
            StreamInfo(exo = exo, mode = "Live-TV · ${channel.name}", modifier = Modifier.align(Alignment.TopStart))
        }
        SleepTimer(
            vm = vm,
            onSleep = { vm.back() },
            modifier = Modifier.align(Alignment.TopCenter).padding(32.dp),
        )
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
        if (optionsOpen) {
            val tracks = remember(tracksVersion) { exo.currentTracks }
            val audio = trackChoices(tracks, C.TRACK_TYPE_AUDIO)
            val text = trackChoices(tracks, C.TRACK_TYPE_TEXT)
            val textOff = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                text.none { it.selected }
            val close = { optionsOpen = false }
            val favorite = vm.isFavorite(channel)
            val sections = buildList {
                add(
                    PanelSection(
                        "Kanal",
                        listOf(
                            PanelOption(if (favorite) "Ta bort från favoriter" else "Lägg till i favoriter") {
                                vm.toggleFavorite(channel)
                                showBanner()
                                close()
                            },
                        ),
                    ),
                )
                if (audio.size > 1) {
                    add(
                        PanelSection(
                            "Ljud",
                            audio.map { c -> PanelOption(c.label, selected = c.selected) { selectTrack(exo, C.TRACK_TYPE_AUDIO, c); close() } },
                        ),
                    )
                }
                if (text.isNotEmpty()) {
                    add(
                        PanelSection(
                            "Undertext",
                            listOf(PanelOption("Av", selected = textOff) { selectTrack(exo, C.TRACK_TYPE_TEXT, null); close() }) +
                                text.map { c ->
                                    PanelOption(c.label, selected = c.selected && !textOff) { selectTrack(exo, C.TRACK_TYPE_TEXT, c); close() }
                                },
                        ),
                    )
                }
                add(sleepSection(vm, now, includeEnd = false, done = close))
                add(scaleSection(vm, done = close))
                add(infoSection(vm, done = close))
            }
            OptionsPanel(
                title = "${channel.number}  ${channel.name}",
                sections = sections,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
    LaunchedEffect(listOpen, optionsOpen) {
        if (!listOpen && !optionsOpen) focus.requestWhenReady()
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
private fun ChannelBanner(
    vm: AppViewModel,
    channel: Channel,
    now: Long,
    error: String?,
    sleep: String?,
    modifier: Modifier = Modifier,
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${channel.number}  ${channel.name}${if (vm.isFavorite(channel)) "  ★" else ""}",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (sleep != null) {
                    Spacer(Modifier.width(16.dp))
                    Text(sleep, color = JptvColors.Accent)
                }
            }
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
                "OK kanallista · håll OK alternativ · ▲▼ byt kanal · ◀ förra kanalen · siffror = kanalnummer",
                color = JptvColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
