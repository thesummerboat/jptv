package se.jptv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import se.jptv.AppViewModel
import se.jptv.iptv.Channel
import se.jptv.ui.JptvColors
import kotlin.math.roundToInt

/**
 * Live-kanalen när den spelar vidare utanför helskärmsspelaren (bild-i-bild). Det är samma spelare
 * och samma ström som i helskärm, så det blir aldrig mer än en ström hos IPTV-leverantören.
 */
class LiveSession(
    val exo: ExoPlayer,
    val channels: List<Channel>,
    val index: Int,
    val previousKey: String?,
) {
    val channel: Channel get() = channels[index]

    /**
     * Byter kanal i rutan. Samma spelare: den gamla strömmen stängs när den nya startar, så det
     * blir fortfarande bara en ström.
     */
    fun retune(newChannels: List<Channel>, newIndex: Int): LiveSession {
        val target = newChannels[newIndex]
        exo.setMediaSource(liveSource(target))
        exo.prepare()
        exo.play()
        return LiveSession(exo, newChannels, newIndex, previousKey = channel.key)
    }
}

/**
 * Plats för bild-i-bild i en skärms layout (t.ex. i guidens rubrik). Själva bilden ritas överst i
 * appen och flyttas hit, så att videoytan inte byts ut när man går mellan skärmar.
 */
@Composable
fun PipSlot(vm: AppViewModel, modifier: Modifier) {
    Box(modifier.onGloballyPositioned { vm.pipSlot = it.boundsInRoot() })
    DisposableEffect(Unit) {
        onDispose { vm.pipSlot = null }
    }
}

/** Kanalen i ett hörn medan man bläddrar. ▶‖ = helskärm; Stopp eller Tillbaka på hemskärmen stänger. */
@Composable
fun BoxScope.PipWindow(vm: AppViewModel, session: LiveSession) {
    val slot = vm.pipSlot
    val density = LocalDensity.current
    val placement = if (slot != null) {
        with(density) {
            Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(slot.left.roundToInt(), slot.top.roundToInt()) }
                .size(slot.width.toDp(), slot.height.toDp())
        }
    } else {
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 32.dp, bottom = 28.dp)
            .size(256.dp, 144.dp)
    }

    // Leverantören bryter ibland strömmen: anslut igen några gånger, stäng sedan.
    DisposableEffect(session) {
        var retries = 0
        val exo = session.exo
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                } else if (retries++ < 2) {
                    exo.prepare()
                } else {
                    vm.closePip()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> retries = 0
                    Player.STATE_ENDED -> if (retries++ < 3) {
                        exo.seekToDefaultPosition()
                        exo.prepare()
                    } else {
                        vm.closePip()
                    }
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // Sovtimern gäller även bild-i-bild.
    val sleepAt = vm.sleepAt
    LaunchedEffect(sleepAt) {
        if (sleepAt == null) return@LaunchedEffect
        delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0))
        vm.sleepAt = null
        vm.closePip()
    }

    Box(
        placement
            .background(Color.Black)
            .border(2.dp, JptvColors.Accent),
    ) {
        VideoSurface(session.exo, scale = 0)
        if (slot == null) {
            val ch = session.channel
            Text(
                "${ch.number}  ${ch.name}  ·  ▶‖ helskärm",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
