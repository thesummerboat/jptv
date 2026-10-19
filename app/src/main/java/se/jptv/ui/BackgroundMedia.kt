package se.jptv.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.jptv.AppViewModel
import se.jptv.Screen
import se.jptv.plex.PlexItem

// ======================================================================
// Ledmotiv på seriesidan
// ======================================================================

/**
 * Seriens ledmotiv, lågt, som i Plex. Spelas en gång (ingen loop) och tonas ut när man lämnar
 * seriesidan. Ett objekt i appen, så att tonen kan klinga av även efter att skärmen är borta.
 */
object ThemePlayer {
    private const val VOLUME = 0.3f
    private val scope = MainScope()
    private var exo: ExoPlayer? = null
    private var url: String? = null
    private var fadeJob: Job? = null

    @OptIn(UnstableApi::class)
    fun play(context: Context, url: String) {
        fadeJob?.cancel()
        val current = exo
        if (current != null && this.url == url) {
            // Samma serie (t.ex. tillbaka från en annan säsong): fortsätt, ingen omstart.
            fade(current, VOLUME, 600)
            return
        }
        release()
        val player = ExoPlayer.Builder(context.applicationContext).build().apply {
            // Ingen ljudfokus: ledmotivet ska inte stoppa något annat, och slutar av sig själv.
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            volume = 0f
            addListener(object : Player.Listener {
                // this@ThemePlayer: annars träffar release() spelaren själv (apply-blocket).
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) this@ThemePlayer.release()
                }

                override fun onPlayerError(error: PlaybackException) = this@ThemePlayer.release()
            })
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            play()
        }
        exo = player
        this.url = url
        fade(player, VOLUME, 1_500)
    }

    /** fade = tona ut (tillbaka till menyerna); annars tyst direkt (en film startar). */
    fun stop(fade: Boolean) {
        val player = exo ?: return
        fadeJob?.cancel()
        if (!fade) {
            release()
            return
        }
        fadeJob = scope.launch {
            ramp(player, 0f, 900)
            if (exo === player) release()
        }
    }

    private fun fade(player: ExoPlayer, to: Float, ms: Long) {
        fadeJob?.cancel()
        fadeJob = scope.launch { ramp(player, to, ms) }
    }

    private suspend fun ramp(player: ExoPlayer, to: Float, ms: Long) {
        val from = player.volume
        val steps = 15
        for (i in 1..steps) {
            delay(ms / steps)
            if (exo !== player) return
            player.volume = from + (to - from) * i / steps
        }
    }

    private fun release() {
        fadeJob?.cancel()
        exo?.release()
        exo = null
        url = null
    }
}

/** Spelar seriens ledmotiv när seriesidan öppnas (inte när man kommer tillbaka från ett avsnitt). */
@Composable
fun ThemeMusic(vm: AppViewModel, url: String?) {
    val context = LocalContext.current
    val openedNow = remember { !vm.cameBack }
    LaunchedEffect(url) {
        if (url != null && openedNow && vm.themeMusic && vm.pip == null) ThemePlayer.play(context, url)
    }
    DisposableEffect(Unit) {
        // Till spelaren: tyst direkt. Annars tonas det ut.
        onDispose { ThemePlayer.stop(fade = vm.current !is Screen.Player) }
    }
}

// ======================================================================
// Trailer i bakgrunden på hemskärmen
// ======================================================================

/** Om trailern syns just nu (bakgrundsbilden tonas då bort). */
class TrailerState {
    var showing by mutableStateOf(false)
}

/**
 * Ljudlös trailer bakom hemskärmens stora ruta, som på Netflix: startar när ett kort har haft fokus
 * i tre sekunder, stannar när fokus flyttas. En spelare, som släpps när hemskärmen lämnas.
 */
@OptIn(UnstableApi::class)
@Composable
fun HeroTrailer(vm: AppViewModel, item: PlexItem?, enabled: Boolean, state: TrailerState) {
    val context = LocalContext.current
    var exo by remember { mutableStateOf<ExoPlayer?>(null) }
    /** Videoytan finns bara medan en trailer laddas eller spelar. */
    var mounted by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(0) }

    fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(context).build().apply {
        volume = 0f
        setAudioAttributes(AudioAttributes.DEFAULT, false)
        // Ljudlös: inget ljudspår alls, så att ljudet (och förstärkaren) inte berörs.
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setMaxVideoSize(1920, 1080)
            .build()
        addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                state.showing = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended++
            }

            override fun onPlayerError(error: PlaybackException) {
                ended++
            }
        })
        exo = this
    }

    fun releasePlayer() {
        state.showing = false
        mounted = false
        exo?.release()
        exo = null
    }

    val key = item?.ratingKey?.takeIf { enabled && vm.heroTrailers && vm.pip == null }
    LaunchedEffect(key) {
        // Fokus flyttades: tillbaka till bilden först, stoppa sedan.
        if (state.showing) {
            state.showing = false
            delay(350)
        }
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        mounted = false
        if (key == null || item == null) return@LaunchedEffect
        delay(3_000)
        val trailer = vm.trailerFor(item) ?: return@LaunchedEffect
        try {
            val url = vm.plex.directPlayUrl(trailer)
                ?: vm.plex.metadata(trailer.ratingKey)?.let { vm.plex.directPlayUrl(it) }
                ?: return@LaunchedEffect
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(vm.plex.streamHeaders())
            val source = DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(url))
            mounted = true
            val p = player()
            // De första sekunderna är ofta åldersgräns och bolagslogotyper.
            p.setMediaSource(source, if (trailer.durationMs > 60_000) 4_000L else 0L)
            p.prepare()
            p.play()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mounted = false
        }
    }

    // Trailern är slut: tona tillbaka till bilden och släpp videoytan.
    LaunchedEffect(ended) {
        if (ended == 0) return@LaunchedEffect
        state.showing = false
        delay(400)
        exo?.stop()
        mounted = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { releasePlayer() }
    DisposableEffect(Unit) {
        onDispose { releasePlayer() }
    }

    val player = exo
    if (mounted && player != null) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.player = player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
