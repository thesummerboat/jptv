package se.jptv.mobile

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import se.jptv.AppViewModel
import se.jptv.iptv.Channel
import se.jptv.plex.PlexItem
import java.lang.ref.WeakReference
import java.util.UUID

/** Casting med Googles vanliga mottagare, som finns i alla Chromecast och Google TV (även på hotell). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Castar från telefonen. Plex skickas som omkodad HLS (H.264 + AAC) med en tillfällig token, så att
 * kontots riktiga nyckel aldrig hamnar på hotellets TV. Live-kanaler skickas som HLS om leverantören har det.
 * TV:n hämtar själv strömmen; telefonen spelar inget under tiden (fortfarande en ström).
 */
@OptIn(UnstableApi::class)
object CastManager {
    /** false om telefonen saknar Google Play-tjänster. */
    var available by mutableStateOf(false)
        private set
    var devices by mutableStateOf<List<MediaRouter.RouteInfo>>(emptyList())
        private set
    /** Ansluten TV, null om ingen. */
    var deviceName by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var subtitle by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isLive by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** Fel eller besked att visa (t.ex. att kanalen inte gick att casta). */
    var message by mutableStateOf<String?>(null)

    val connected: Boolean get() = deviceName != null

    private var castContext: CastContext? = null
    private var player: CastPlayer? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private val scope = MainScope()
    private var job: Job? = null
    private var vmRef: WeakReference<AppViewModel>? = null

    private sealed interface Now {
        data class Plex(val item: PlexItem, val session: String, val offsetMs: Long) : Now
        data class Live(val channel: Channel) : Now
    }

    private var now: Now? = null

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
    }

    fun init(context: Context) {
        if (castContext != null) return
        val cc = try {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(context.applicationContext)
        } catch (e: Exception) {
            null
        } ?: return
        castContext = cc
        router = MediaRouter.getInstance(context.applicationContext)
        selector = cc.mergedSelector
        val p = CastPlayer(cc)
        p.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                deviceName = currentDeviceName()
                message = null
            }

            override fun onCastSessionUnavailable() {
                reportStopped(ended = false)
                deviceName = null
                title = null
                subtitle = null
                isPlaying = false
                loading = false
            }
        })
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@CastManager.isPlaying = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                loading = false
                message = if (now is Now.Live) {
                    "TV:n kunde inte spela kanalen. Många IPTV-leverantörer går inte att casta (TV:n kräver https och HLS)."
                } else {
                    "TV:n kunde inte spela det här (${error.errorCodeName})."
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) loading = false
                if (playbackState == Player.STATE_ENDED && now is Now.Plex) {
                    reportStopped(ended = true)
                    title = null
                    subtitle = null
                }
            }
        })
        player = p
        if (p.isCastSessionAvailable) deviceName = currentDeviceName()
        available = true
        // Position för reglaget, och "Fortsätt titta" i Plex var tionde sekund.
        scope.launch {
            var tick = 0
            while (true) {
                delay(1_000)
                val pl = player ?: continue
                if (!connected) continue
                positionMs = pl.currentPosition
                durationMs = pl.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                if (++tick % 10 == 0) reportProgress()
            }
        }
    }

    private fun currentDeviceName(): String =
        castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName ?: "TV"

    // ---------- Hitta och ansluta ----------

    /** Leta efter TV-apparater medan valet visas. */
    fun startDiscovery() {
        val r = router ?: return
        val s = selector ?: return
        r.addCallback(s, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refreshDevices()
    }

    fun stopDiscovery() {
        router?.removeCallback(routerCallback)
    }

    private fun refreshDevices() {
        val r = router ?: return
        val s = selector ?: return
        devices = r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(s) }
    }

    fun connect(route: MediaRouter.RouteInfo) {
        message = null
        router?.selectRoute(route)
    }

    fun disconnect() {
        reportStopped(ended = false)
        castContext?.sessionManager?.endCurrentSession(true)
    }

    // ---------- Spela på TV:n ----------

    /** startMs: var filmen ska börja (t.ex. där telefonen var); null = där Plex säger. */
    fun castPlex(vm: AppViewModel, item: PlexItem, startMs: Long?) {
        val p = player ?: return
        vmRef = WeakReference(vm)
        job?.cancel()
        reportStopped(ended = false)
        loading = true
        message = null
        isLive = false
        title = item.displayTitle
        subtitle = item.subtitle?.takeIf { item.type == "episode" }
        job = scope.launch {
            try {
                val fresh = withTimeoutOrNull(5_000) {
                    try {
                        vm.plex.metadata(item.ratingKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                } ?: item
                val token = vm.plex.transientToken()
                if (token == null) {
                    message = "Plex gav ingen nyckel till TV:n. Försök igen."
                    loading = false
                    return@launch
                }
                val startAt = startMs ?: fresh.viewOffsetMs
                val offsetSec = startAt / 1000
                val session = UUID.randomUUID().toString()
                // Hemma: full kvalitet. Hemifrån: som valt i inställningarna (hemmets uppladdning sätter gränsen).
                val quality = if (vm.plex.serverIsLocal()) null else vm.remoteStream()
                val url = vm.plex.prepareTranscode(
                    fresh,
                    session,
                    maxKbps = quality?.first ?: 20000,
                    resolution = quality?.second ?: "1920x1080",
                    offsetSec = offsetSec,
                    token = token,
                )
                val art = vm.plex.imageUrl(fresh.thumb, 400, 600, token)
                val meta = MediaMetadata.Builder()
                    .setTitle(fresh.displayTitle)
                    .apply {
                        if (fresh.type == "episode") setSubtitle(fresh.subtitle)
                        art?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
                now = Now.Plex(fresh, session, offsetSec * 1000)
                positionMs = 0L
                p.setMediaItem(
                    MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).setMediaMetadata(meta).build(),
                    0L,
                )
                p.playWhenReady = true
                p.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Kunde inte casta: ${e.message}"
                loading = false
            }
        }
    }

    fun castChannel(channel: Channel) {
        val p = player ?: return
        job?.cancel()
        reportStopped(ended = false)
        loading = true
        message = null
        isLive = true
        title = "${channel.number}  ${channel.name}"
        subtitle = "Live-TV"
        val meta = MediaMetadata.Builder()
            .setTitle(channel.name)
            .apply { channel.logo?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        now = Now.Live(channel)
        positionMs = 0L
        p.setMediaItem(
            MediaItem.Builder().setUri(IptvCast.hlsUrl(channel.url)).setMimeType(MimeTypes.APPLICATION_M3U8).setMediaMetadata(meta).build(),
        )
        p.playWhenReady = true
        p.prepare()
    }

    fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0L))
    }

    /** Sluta spela på TV:n men behåll anslutningen. */
    fun stopCasting() {
        job?.cancel()
        reportStopped(ended = false)
        player?.stop()
        player?.clearMediaItems()
        title = null
        subtitle = null
        isPlaying = false
        loading = false
    }

    /** Hela filmens position (strömmen från Plex börjar där man startade). */
    private fun absolutePosition(n: Now.Plex): Long = n.offsetMs + positionMs

    private fun reportProgress() {
        val n = now as? Now.Plex ?: return
        val vm = vmRef?.get() ?: return
        if (positionMs <= 0L) return
        vm.reportProgress(n.item, if (isPlaying) "playing" else "paused", absolutePosition(n), n.item.durationMs, n.session)
    }

    private fun reportStopped(ended: Boolean) {
        val n = now ?: return
        now = null
        if (n !is Now.Plex) return
        val vm = vmRef?.get() ?: return
        if (positionMs > 0L || ended) {
            val pos = if (ended) n.item.durationMs else absolutePosition(n)
            vm.reportStopped(n.item, pos, n.item.durationMs, n.session, ended)
        } else {
            vm.abandonPlayback(n.session)
        }
    }
}
