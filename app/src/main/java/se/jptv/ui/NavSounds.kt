package se.jptv.ui

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock

/**
 * Diskret ljud när man bläddrar i menyerna. Använder TV:ns egna systemljud, så det låter
 * som resten av Shield/Google TV, men på en nivå man väljer själv. Spelas aldrig i spelaren.
 */
object NavSounds {
    /** 0 = av, 1 = lågt, 2 = mellan. */
    var level: Int = 1

    private var audio: AudioManager? = null
    private var lastPlayed = 0L

    fun init(context: Context) {
        if (audio != null) return
        audio = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also {
            // Systemljuden laddas annars bara om de är påslagna i TV:ns inställningar.
            runCatching { it.loadSoundEffects() }
        }
    }

    private fun volume(): Float = when (level) {
        1 -> 0.25f
        2 -> 0.5f
        else -> 0f
    }

    /** Fokus flyttas. Snabba upprepningar (håll inne en pil) ger högst ett ljud per 70 ms. */
    fun move(direction: Int) {
        val v = volume()
        if (v == 0f) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayed < 70) return
        lastPlayed = now
        runCatching { audio?.playSoundEffect(direction, v) }
    }

    fun select() {
        val v = volume()
        if (v == 0f) return
        lastPlayed = SystemClock.uptimeMillis()
        runCatching { audio?.playSoundEffect(AudioManager.FX_KEY_CLICK, v) }
    }

    const val UP = AudioManager.FX_FOCUS_NAVIGATION_UP
    const val DOWN = AudioManager.FX_FOCUS_NAVIGATION_DOWN
    const val LEFT = AudioManager.FX_FOCUS_NAVIGATION_LEFT
    const val RIGHT = AudioManager.FX_FOCUS_NAVIGATION_RIGHT
}
