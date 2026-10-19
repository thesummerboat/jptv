package se.jptv.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Automatisk bildfrekvens: byter TV:ns skärmläge så att det passar filmen (t.ex. 23,976 Hz för film,
 * 50 Hz för svensk TV). Shield (Android 11) gör det inte själv, så appen ber om läget via fönstret,
 * som Kodi och Plex gör. Upplösningen behålls.
 */
object DisplayModes {
    private var activityRef: WeakReference<Activity>? = null
    private val scope = MainScope()
    private var restoreJob: Job? = null
    private var originalModeId: Int? = null

    /** Av/på från inställningarna. */
    var enabled: Boolean = true

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    private fun display(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }

    /** Senaste beslutet, för teknisk info: "byter till …", "står redan rätt", "inget läge passar" … */
    var lastDecision: String? = null
        private set

    private fun hz(v: Float): String =
        String.format(java.util.Locale("sv", "SE"), "%.3f", v).trimEnd('0').trimEnd(',')

    private fun target(fps: Float): Pair<Activity, ModeInfo>? {
        if (!enabled) {
            lastDecision = "automatiskt byte är avstängt"
            return null
        }
        val activity = activityRef?.get() ?: return null
        val d = display(activity) ?: return null
        val cur = d.mode
        val current = ModeInfo(cur.modeId, cur.physicalWidth, cur.physicalHeight, cur.refreshRate)
        val modes = d.supportedModes.map { ModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val chosen = FrameRateMatcher.choose(fps, current, modes)
        lastDecision = when {
            chosen != null -> "byter till ${chosen.width}×${chosen.height} @ ${hz(chosen.refreshRate)} Hz"
            FrameRateMatcher.fits(fps, current) -> "TV:n står redan rätt"
            else -> "TV:n erbjuder inget läge för ${hz(fps)} bilder/s"
        }
        return if (chosen == null) null else activity to chosen
    }

    /** Alla lägen TV:n erbjuder, t.ex. "3840×2160: 60/50/23,976 Hz · 1920×1080: 60 Hz". */
    fun modesSummary(): String? {
        val activity = activityRef?.get() ?: return null
        val d = display(activity) ?: return null
        return d.supportedModes
            .groupBy { it.physicalWidth to it.physicalHeight }
            .entries
            .sortedByDescending { it.key.first * it.key.second }
            .joinToString(" · ") { (res, list) ->
                val rates = list.map { it.refreshRate }.distinctBy { Math.round(it * 100) }.sortedDescending()
                "${res.first}×${res.second}: ${rates.joinToString("/") { hz(it) }} Hz"
            }
    }

    /** TV:ns läge just nu, t.ex. "3840×2160 @ 23,976 Hz" (för teknisk info). */
    fun currentModeText(): String? {
        val activity = activityRef?.get() ?: return null
        val mode = display(activity)?.mode ?: return null
        val hz = String.format(java.util.Locale("sv", "SE"), "%.3f", mode.refreshRate).trimEnd('0').trimEnd(',')
        return "${mode.physicalWidth}×${mode.physicalHeight} @ $hz Hz" + if (enabled) "" else " (automatiskt byte av)"
    }

    /** true om ett byte behövs för den här bildfrekvensen. */
    fun needsSwitch(fps: Float): Boolean = target(fps) != null

    /** Läget vi bytt till (null = TV:ns vanliga läge). */
    private var switchedTo: Int? = null

    /**
     * Helsvart skärm medan TV:n byter tillbaka till menyernas läge, så att menyn inte syns
     * först och sedan blinkar svart. Fjärren är pausad så länge.
     */
    var blackout by mutableStateOf(false)
        private set

    /** Lägger svart över allt direkt (anropas när man går ut ur spelaren, innan menyn ritas). */
    fun beginBlackout() {
        if (switchedTo != null && enabled) blackout = true
    }

    /**
     * Byter skärmläge och väntar tills TV:n har bytt (bilden är svart en kort stund).
     * Returnerar true om läget byttes.
     */
    suspend fun switchTo(fps: Float): Boolean {
        restoreJob?.cancel()
        blackout = false
        val (activity, mode) = target(fps) ?: return false
        val d = display(activity) ?: return false
        val window = activity.window
        if (originalModeId == null) originalModeId = window.attributes.preferredDisplayModeId
        val manager = activity.getSystemService(DisplayManager::class.java)
        val changed = CompletableDeferred<Unit>()
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == d.displayId && d.mode.modeId == mode.id) changed.complete(Unit)
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        try {
            val attrs = window.attributes
            attrs.preferredDisplayModeId = mode.id
            window.attributes = attrs
            switchedTo = mode.id
            withTimeoutOrNull(5_000) { changed.await() }
        } finally {
            manager.unregisterDisplayListener(listener)
        }
        // HDMI behöver en stund att synka innan bilden visas.
        delay(1_200)
        return true
    }

    /**
     * Spelaren stängs. restore = man går tillbaka till menyerna: byt till TV:ns vanliga läge
     * (hög frekvens i menyerna) bakom svart skärm. Vid nästa avsnitt (restore = false) behålls läget.
     */
    fun release(restore: Boolean) {
        restoreJob?.cancel()
        if (!restore || switchedTo == null) {
            blackout = false
            return
        }
        blackout = true
        restoreJob = scope.launch {
            try {
                restoreAndWait()
            } finally {
                blackout = false
            }
        }
    }

    private suspend fun restoreAndWait() {
        val original = originalModeId ?: return
        val activity = activityRef?.get() ?: return
        val d = display(activity) ?: return
        val from = switchedTo
        val manager = activity.getSystemService(DisplayManager::class.java)
        val changed = CompletableDeferred<Unit>()
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == d.displayId && d.mode.modeId != from) changed.complete(Unit)
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        try {
            val attrs = activity.window.attributes
            attrs.preferredDisplayModeId = original
            activity.window.attributes = attrs
            switchedTo = null
            originalModeId = null
            withTimeoutOrNull(4_000) { changed.await() }
        } finally {
            manager.unregisterDisplayListener(listener)
        }
        delay(1_200)
    }

    /**
     * Tillbaka till TV:ns vanliga läge direkt, utan att vänta. Används när appen lämnas och när
     * automatisk bildfrekvens stängs av.
     */
    fun resetNow() {
        restoreJob?.cancel()
        blackout = false
        val original = originalModeId ?: return
        val activity = activityRef?.get() ?: return
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = original
        activity.window.attributes = attrs
        originalModeId = null
        switchedTo = null
    }
}

/**
 * Mäter bildfrekvensen från bildrutorna som visas, för strömmar som inte talar om den själva
 * (vanligt för IPTV). Rapporterar en gång per [reset], på huvudtråden.
 */
@OptIn(UnstableApi::class)
class FpsMeter(private val onResult: (Float) -> Unit) : VideoFrameMetadataListener {
    private val main = Handler(Looper.getMainLooper())
    private val times = LongArray(48)
    private var count = 0
    private var done = false
    private var generation = 0

    fun reset() {
        synchronized(this) {
            count = 0
            done = false
            generation++
        }
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        var gen = 0
        val fps = synchronized(this) {
            if (done) return@synchronized null
            // Hopp bakåt (spolning, ny ström): börja om.
            if (count > 0 && presentationTimeUs <= times[count - 1]) count = 0
            times[count++] = presentationTimeUs
            if (count < times.size) return@synchronized null
            done = true
            gen = generation
            FrameRateMatcher.fromTimestamps(times, count)
        } ?: return
        main.post {
            if (gen == generation) onResult(fps)
        }
    }
}
