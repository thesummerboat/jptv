package se.jptv.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import se.jptv.AppViewModel
import se.jptv.PlayRequest
import se.jptv.Screen
import se.jptv.plex.PlexChapter
import se.jptv.ui.MessageBox
import se.jptv.ui.requestWhenReady
import se.jptv.web.YtVideoInfo

private enum class YtPanel { NONE, TRACKS, STYLE, OPTIONS }

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

private fun sponsorName(category: String) = when (category) {
    "selfpromo" -> "egenreklam"
    "interaction" -> "prenumerera-påminnelse"
    else -> "sponsorsnack"
}

/**
 * YouTube-video från JPTV Web (HLS som servern bygger av YouTubes filer). Samma kontroller som för
 * filmer: ◀/▶ spolar, ▼ knappraden, ▲ undertexter. SponsorBlock hoppar över sponsordelar och
 * positionen sparas i JPTV Web, så att Fortsätt titta fungerar på alla TV-apparater och i webben.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun YouTubePlayer(vm: AppViewModel, request: PlayRequest.YouTube) {
    val context = LocalContext.current
    val exo = remember { buildExoPlayer(context, vm.useTunneling(), vod = true) }
    val focus = remember { FocusRequester() }

    var info by remember { mutableStateOf<YtVideoInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var everReady by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var playing by remember { mutableStateOf(false) }
    var wantPlay by remember { mutableStateOf(true) }
    var overlayVisible by remember { mutableStateOf(true) }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var controlFocus by remember { mutableStateOf<VodButton?>(null) }
    var panel by remember { mutableStateOf(YtPanel.NONE) }
    var tracksVersion by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(1f) }
    val skipped = remember { HashSet<Long>() }
    var reload by remember { mutableIntStateOf(0) }

    val panelOpen = panel != YtPanel.NONE

    // Förhandsbilder när man spolar (YouTubes storyboard via JPTV Web).
    val previews = remember(info?.storyboard) { info?.storyboard?.let { YtPreview(vm.web, it) } }
    LaunchedEffect(previews) {
        val p = previews ?: return@LaunchedEffect
        delay(5_000)
        runCatching { p.frame(exo.currentPosition) }
    }
    val previewKey = scrubTarget?.let { it / 1_000 }
    val preview by produceState<ImageBitmap?>(null, previewKey, previews) {
        val target = scrubTarget
        val p = previews
        value = if (target == null || p == null) null else runCatching { p.frame(target) }.getOrNull() ?: value
    }

    // Hämta videon och starta.
    LaunchedEffect(request.videoId, reload) {
        error = null
        val v = try {
            vm.yt().ytVideo(request.videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Videon gick inte att hämta"
            return@LaunchedEffect
        }
        info = v
        duration = v.durationMs
        val url = vm.web.url(v.hls) ?: run {
            error = "JPTV Web hittades inte"
            return@LaunchedEffect
        }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(60_000)
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        val resume = if (request.fromStart || v.positionMs <= 0 || v.positionMs > v.durationMs - 20_000) 0L else v.positionMs
        exo.setMediaSource(HlsMediaSource.Factory(http).createMediaSource(item), resume)
        exo.prepare()
        exo.play()
    }

    fun report() {
        val v = info ?: return
        if (!everReady) return
        vm.ytProgress(v, exo.currentPosition, if (exo.duration > 0) exo.duration else v.durationMs)
    }

    fun togglePlay() {
        if (exo.playWhenReady) exo.pause() else exo.play()
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
                error = "Videon gick inte att spela (${e.errorCodeName})"
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                wantPlay = playWhenReady
                if (!playWhenReady) report()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) everReady = true
                if (playbackState == Player.STATE_ENDED) {
                    info?.let { vm.ytProgress(it, it.durationMs, it.durationMs) }
                    vm.back()
                }
            }
        }
        exo.addListener(listener)
        // Undertexter av från början (YouTube-videor har oftast inga, och automatiska stör).
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        onDispose {
            exo.removeListener(listener)
            if (exo.playbackState != Player.STATE_ENDED) report()
            exo.release()
        }
    }

    // Tid, göm kontrollerna, SponsorBlock och position till JPTV Web var tionde sekund.
    LaunchedEffect(exo) {
        var ticks = 0
        while (true) {
            position = exo.currentPosition
            now = System.currentTimeMillis()
            if (exo.duration > 0) duration = exo.duration
            buffered = exo.bufferedPosition
            if (overlayVisible && exo.isPlaying && now - lastKeyAt > 5_000) {
                overlayVisible = false
                controlFocus = null
            }
            val v = info
            if (v != null && exo.isPlaying && scrubTarget == null) {
                val seg = v.sponsor.firstOrNull { position >= it.startMs && position < it.endMs - 500 && it.startMs !in skipped }
                if (seg != null) {
                    skipped += seg.startMs
                    exo.seekTo(seg.endMs)
                    toast = "Hoppade över ${sponsorName(seg.category)} (${(seg.endMs - seg.startMs) / 1000} s)"
                }
            }
            if (ticks % 40 == 0 && exo.isPlaying) report()
            ticks++
            delay(250)
        }
    }

    LaunchedEffect(scrubTarget) {
        if (scrubTarget == null) return@LaunchedEffect
        delay(1_000)
        commitScrub()
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3_000)
            toast = null
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        exo.pause()
        report()
    }

    val chapters = remember(info) { info?.chapters?.map { PlexChapter(it.title, it.startMs, it.endMs) }.orEmpty() }
    val marks = remember(chapters) { TimelineMarks(null, null, chapters) }
    val buttons = buildList {
        add(ControlButton(VodButton.PLAY_PAUSE, if (wantPlay) "Pausa" else "Spela", if (wantPlay) PlayerIcons.Pause else PlayerIcons.Play))
        add(ControlButton(VodButton.BACK, "Bakåt 10 s", text = "−10"))
        add(ControlButton(VodButton.FORWARD, "Framåt 30 s", text = "+30"))
        add(ControlButton(VodButton.TRACKS, "Undertexter och ljud", PlayerIcons.Subtitles))
        add(ControlButton(VodButton.RESTART, "Från början", PlayerIcons.Restart))
        add(ControlButton(VodButton.MORE, "Hastighet, kanal och mer", PlayerIcons.More))
    }

    fun press(id: VodButton, repeat: Int) {
        when (id) {
            VodButton.PLAY_PAUSE -> if (repeat == 0) togglePlay()
            VodButton.BACK -> scrubBy(if (repeat > 10) -60_000L else -10_000L)
            VodButton.FORWARD -> scrubBy(if (repeat > 10) 120_000L else 30_000L)
            VodButton.TRACKS -> if (repeat == 0) panel = YtPanel.TRACKS
            VodButton.RESTART -> if (repeat == 0) scrubTarget = 0L
            VodButton.MORE -> if (repeat == 0) panel = YtPanel.OPTIONS
            else -> Unit
        }
    }

    val controlsShown = (overlayVisible || !playing || scrubTarget != null) && !panelOpen && info != null

    BackHandler(enabled = controlFocus != null && scrubTarget == null && !panelOpen) {
        controlFocus = null
        overlayVisible = false
    }
    BackHandler(enabled = scrubTarget != null && !panelOpen) { scrubTarget = null }
    BackHandler(enabled = panelOpen) {
        panel = if (panel == YtPanel.STYLE) YtPanel.TRACKS else YtPanel.NONE
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (panelOpen) return@onKeyEvent false
                val native = ev.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val code = native.keyCode
                val repeat = native.repeatCount
                val button = controlFocus?.takeIf { id -> controlsShown && buttons.any { it.id == id } }
                val handled = when {
                    button != null && code == KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val i = buttons.indexOfFirst { it.id == button }
                        controlFocus = buttons[(i - 1).coerceAtLeast(0)].id as VodButton
                        true
                    }
                    button != null && code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val i = buttons.indexOfFirst { it.id == button }
                        controlFocus = buttons[(i + 1).coerceAtMost(buttons.lastIndex)].id as VodButton
                        true
                    }
                    button != null && code == KeyEvent.KEYCODE_DPAD_UP -> {
                        controlFocus = null
                        true
                    }
                    button != null && code == KeyEvent.KEYCODE_DPAD_DOWN -> true
                    button != null && isOk(code) -> {
                        press(button, repeat)
                        true
                    }
                    isOk(code) && scrubTarget != null -> {
                        commitScrub()
                        true
                    }
                    isOk(code) || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_SPACE -> {
                        if (repeat == 0) togglePlay()
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
                        scrubBy(if (repeat > 10) -60_000L else -10_000L)
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        scrubBy(if (repeat > 10) 120_000L else 30_000L)
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CAPTIONS -> {
                        panel = YtPanel.TRACKS
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                        controlFocus = VodButton.PLAY_PAUSE
                        true
                    }
                    code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO -> {
                        panel = YtPanel.OPTIONS
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
        VideoSurface(exo, vm.videoScale, subtitles = vm.subtitleLook)
        val v = info
        when {
            error != null -> MessageBox(error!!, buttonText = "Försök igen", onButton = {
                error = null
                reload++
            })
            v == null -> MessageBox("Hämtar videon från YouTube…")
        }
        if (controlsShown && v != null) {
            val status = buildList {
                if (speed != 1f) add("${speed.toString().replace('.', ',').removeSuffix(",0")}×" to true)
                if (v.sponsor.isNotEmpty()) add("SponsorBlock: ${v.sponsor.size}" to false)
                add("YouTube" to false)
            }
            PlayerTopBar(
                now = now,
                endsAt = if (duration > 0) now + ((duration - position).coerceAtLeast(0) / speed).toLong() else null,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            VodControls(
                title = v.title,
                subtitle = listOfNotNull(v.channel.takeIf { it.isNotBlank() }, se.jptv.web.YtJson.views(v.views), se.jptv.web.YtJson.ago(v.published))
                    .joinToString(" · "),
                scrubTarget = scrubTarget,
                preview = preview,
                position = position,
                duration = duration,
                buffered = buffered,
                marks = marks,
                buttons = buttons,
                focused = controlFocus?.takeIf { id -> buttons.any { it.id == id } },
                status = status,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        if (vm.showStreamInfo && !panelOpen) {
            StreamInfo(exo = exo, mode = "YouTube via JPTV Web", contentFps = null, modifier = Modifier.align(Alignment.TopStart))
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

        val close = { panel = YtPanel.NONE }
        when (panel) {
            YtPanel.NONE -> Unit
            YtPanel.TRACKS -> {
                val tracks = remember(tracksVersion) { exo.currentTracks }
                val text = trackChoices(tracks, C.TRACK_TYPE_TEXT)
                val audio = trackChoices(tracks, C.TRACK_TYPE_AUDIO)
                val textOff = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.selected }
                val sections = buildList {
                    add(
                        PanelSection(
                            "Undertexter",
                            listOf(PanelOption("Av", selected = textOff) { selectTrack(exo, C.TRACK_TYPE_TEXT, null); close() }) +
                                text.map { c -> PanelOption(c.label, selected = c.selected && !textOff) { selectTrack(exo, C.TRACK_TYPE_TEXT, c); close() } } +
                                PanelOption("Undertextstil…", detail = "${vm.subtitleLook.sizeLabel} · ${vm.subtitleLook.styleLabel}") { panel = YtPanel.STYLE },
                            subtitle = if (text.isEmpty()) "Videon har inga svenska eller engelska undertexter" else null,
                        ),
                    )
                    if (audio.size > 1) {
                        add(PanelSection("Ljud", audio.map { c -> PanelOption(c.label, selected = c.selected) { selectTrack(exo, C.TRACK_TYPE_AUDIO, c); close() } }))
                    }
                    add(
                        PanelSection(
                            "Ljudförbättring",
                            listOf(
                                PanelOption("Dialogförstärkning", selected = vm.dialogueBoost, detail = "Tydligare röster") {
                                    vm.changeAudioBoost(!vm.dialogueBoost, vm.nightMode)
                                },
                                PanelOption("Nattläge", selected = vm.nightMode, detail = "Jämnare volym") {
                                    vm.changeAudioBoost(vm.dialogueBoost, !vm.nightMode)
                                },
                            ),
                        ),
                    )
                }
                OptionsPanel(title = "Undertexter och ljud", sections = sections, modifier = Modifier.align(Alignment.CenterEnd))
            }
            YtPanel.STYLE -> SubtitleStylePanel(
                look = vm.subtitleLook,
                transcoding = false,
                onChange = { vm.changeSubtitleLook(it) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            YtPanel.OPTIONS -> {
                val sections = buildList {
                    add(
                        PanelSection(
                            "Hastighet",
                            SPEEDS.map { s ->
                                PanelOption(if (s == 1f) "Normal" else "${s.toString().replace('.', ',')}×", selected = speed == s) {
                                    speed = s
                                    exo.setPlaybackSpeed(s)
                                    close()
                                }
                            },
                        ),
                    )
                    if (v != null && v.channelId.isNotBlank()) {
                        add(
                            PanelSection(
                                "Kanal",
                                listOf(
                                    PanelOption(v.channel, detail = "Öppna kanalen") {
                                        close()
                                        vm.replaceTop(Screen.YouTubeChannel(v.channelId, v.channel))
                                    },
                                ),
                            ),
                        )
                    }
                    add(sleepSection(vm, now, includeEnd = true, done = close))
                    add(scaleSection(vm, done = close))
                    add(infoSection(vm, done = close))
                }
                OptionsPanel(title = v?.title ?: "YouTube", sections = sections, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
    LaunchedEffect(panel, vm.dueReminder == null) {
        if (panel == YtPanel.NONE && vm.dueReminder == null) focus.requestWhenReady()
    }
}
