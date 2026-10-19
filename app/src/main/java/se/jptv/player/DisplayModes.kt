package se.jptv.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.OptIn
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

    private fun target(fps: Float): Pair<Activity, ModeInfo>? {
        if (!enabled) return null
        val activity = activityRef?.get() ?: return null
        val d = display(activity) ?: return null
        val cur = d.mode
        val current = ModeInfo(cur.modeId, cur.physicalWidth, cur.physicalHeight, cur.refreshRate)
        val modes = d.supportedModes.map { ModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val chosen = FrameRateMatcher.choose(fps, current, modes) ?: return null
        return activity to chosen
    }

    /** true om ett byte behövs för den här bildfrekvensen. */
    fun needsSwitch(fps: Float): Boolean = target(fps) != null

    /**
     * Byter skärmläge och väntar tills TV:n har bytt (bilden är svart en kort stund).
     * Returnerar true om läget byttes.
     */
    suspend fun switchTo(fps: Float): Boolean {
        restoreJob?.cancel()
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
            withTimeoutOrNull(5_000) { changed.await() }
        } finally {
            manager.unregisterDisplayListener(listener)
        }
        // HDMI behöver en stund att synka innan bilden visas.
        delay(1_200)
        return true
    }

    /**
     * Spelaren stängs: tillbaka till TV:ns vanliga läge. Väntar lite först, så att nästa avsnitt
     * kan behålla samma läge utan ett extra svart byte.
     */
    fun release() {
        val original = originalModeId ?: return
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(2_500)
            val activity = activityRef?.get() ?: return@launch
            val attrs = activity.window.attributes
            attrs.preferredDisplayModeId = original
            activity.window.attributes = attrs
            originalModeId = null
        }
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
