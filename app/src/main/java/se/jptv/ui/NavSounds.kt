package se.jptv.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Diskret klick när man bläddrar i menyerna. Ljuden skapas av appen själv (korta, mjuka
 * toner som klingar av snabbt), så inga ljudfiler behövs. Spelas aldrig i spelaren.
 */
object NavSounds {
    /** 0 = av, 1 = lågt, 2 = mellan. */
    var level: Int = 1

    private var pool: SoundPool? = null
    private var moveId = 0
    private var selectId = 0
    private var lastPlayed = 0L

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        val dir = File(context.cacheDir, "sounds").apply { mkdirs() }
        moveId = p.load(writeTone(File(dir, "move.wav"), freq = 2_100.0, ms = 22, decay = 160.0).path, 1)
        selectId = p.load(writeTone(File(dir, "select.wav"), freq = 1_250.0, ms = 45, decay = 75.0).path, 1)
        pool = p
    }

    private fun volume(): Float = when (level) {
        1 -> 0.06f
        2 -> 0.16f
        else -> 0f
    }

    /** Fokus flyttas. Snabba upprepningar (håll inne en pil) ger högst ett klick per 70 ms. */
    fun move() {
        val v = volume()
        if (v == 0f) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayed < 70) return
        lastPlayed = now
        pool?.play(moveId, v, v, 1, 0, 1f)
    }

    fun select() {
        val v = volume()
        if (v == 0f) return
        lastPlayed = SystemClock.uptimeMillis()
        pool?.play(selectId, v * 0.9f, v * 0.9f, 1, 0, 1f)
    }

    /** Kort sinuston som klingar av exponentiellt, med mjuk start så att det inte smäller. */
    private fun writeTone(file: File, freq: Double, ms: Int, decay: Double): File {
        if (file.exists() && file.length() > 44) return file
        val rate = 44_100
        val samples = rate * ms / 1000
        val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val attack = rate / 1000 // 1 ms
        for (i in 0 until samples) {
            val t = i.toDouble() / rate
            val env = exp(-t * decay) * minOf(1.0, i.toDouble() / attack)
            val s = sin(2 * PI * freq * t) * 0.9 + sin(2 * PI * freq * 2 * t) * 0.1
            pcm.putShort((s * env * Short.MAX_VALUE * 0.8).toInt().toShort())
        }
        val data = pcm.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        file.outputStream().use {
            it.write(header.array())
            it.write(data)
        }
        return file
    }
}
