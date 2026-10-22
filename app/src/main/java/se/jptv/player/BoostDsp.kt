package se.jptv.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Ljudförbättring för avkodat ljud: dialogförstärkning och nattläge.
 *
 * Samplen är interfolierade flyttal mellan −1 och 1, i Androids kanalordning
 * (vänster, höger, center, LFE, bak vänster, bak höger …).
 *
 * - Dialog: rösterna ligger i centerkanalen (5.1/7.1) eller i "mitten" av stereobilden.
 *   Den delen lyfts, med extra tyngd kring 2–3 kHz där konsonanterna sitter,
 *   och resten (musik, effekter, bas) sänks något.
 * - Nattläge: en kompressor jämnar ut volymen, så att smällar blir lägre och tal starkare.
 *
 * Allt som går ut passerar en mjuk begränsare, så att lyftet aldrig ger klipp.
 */
internal class BoostDsp(sampleRate: Int, private val channels: Int) {
    var dialogue = false
    var night = false
    val enabled: Boolean get() = dialogue || night

    private val presence = Biquad.peaking(sampleRate, PRESENCE_HZ, PRESENCE_Q, PRESENCE_DB)

    /** Kanalen som bär rösterna (−1 = ingen, använd mitten av vänster/höger). */
    private val center = when {
        channels == 3 || channels == 5 || channels >= 6 -> 2
        else -> -1
    }
    private val lfe = if (channels >= 6) 3 else -1

    // Kompressorn: följer nivån snabbt uppåt och långsamt nedåt.
    private val attack = exp(-1.0 / (ATTACK_S * sampleRate)).toFloat()
    private val release = exp(-1.0 / (RELEASE_S * sampleRate)).toFloat()
    private var envelope = 0f

    fun reset() {
        presence.reset()
        envelope = 0f
    }

    /** Bearbetar [frames] bildrutor i [buf] på plats. */
    fun process(buf: FloatArray, frames: Int) {
        if (!enabled || channels <= 0) return
        val ch = channels
        for (f in 0 until frames) {
            val o = f * ch
            if (dialogue) dialogueFrame(buf, o)
            var gain = 1f
            if (night) gain = compressorGain(buf, o)
            for (c in 0 until ch) buf[o + c] = softClip(buf[o + c] * gain)
        }
    }

    private fun dialogueFrame(buf: FloatArray, o: Int) {
        when {
            center >= 0 -> {
                for (c in 0 until channels) {
                    val i = o + c
                    buf[i] = when (c) {
                        center -> presence.process(buf[i]) * CENTER_GAIN
                        lfe -> buf[i] * LFE_GAIN
                        else -> buf[i] * OTHER_GAIN
                    }
                }
            }
            channels >= 2 -> {
                // Mitten (det som är lika i båda kanalerna) är oftast rösterna; sidan är musik och rum.
                val l = buf[o]
                val r = buf[o + 1]
                val mid = presence.process((l + r) * 0.5f) * MID_GAIN
                val side = (l - r) * 0.5f * SIDE_GAIN
                buf[o] = mid + side
                buf[o + 1] = mid - side
                for (c in 2 until channels) buf[o + c] *= OTHER_GAIN
            }
            else -> buf[o] = presence.process(buf[o])
        }
    }

    private fun compressorGain(buf: FloatArray, o: Int): Float {
        var peak = 0f
        for (c in 0 until channels) {
            if (c == lfe) continue
            val a = abs(buf[o + c])
            if (a > peak) peak = a
        }
        envelope = if (peak > envelope) {
            attack * envelope + (1f - attack) * peak
        } else {
            release * envelope + (1f - release) * peak
        }
        val levelDb = toDb(envelope)
        val over = levelDb - THRESHOLD_DB
        val reduction = if (over > 0f) over * (1f - 1f / RATIO) else 0f
        // Lyft inte brus och tystnad lika mycket som tal.
        val makeup = MAKEUP_DB * ((levelDb - SILENCE_DB) / 20f).coerceIn(0f, 1f)
        return fromDb(makeup - reduction)
    }

    companion object {
        const val PRESENCE_HZ = 2_400.0
        const val PRESENCE_Q = 0.8
        const val PRESENCE_DB = 6.0

        /** +4 dB på centerkanalen, −2 dB på övriga, −3 dB på basen. */
        val CENTER_GAIN = fromDb(4f)
        val OTHER_GAIN = fromDb(-2f)
        val LFE_GAIN = fromDb(-3f)
        val MID_GAIN = fromDb(1f)
        val SIDE_GAIN = fromDb(-4.5f)

        const val THRESHOLD_DB = -28f
        const val RATIO = 3.5f
        const val MAKEUP_DB = 8f
        const val SILENCE_DB = -75f
        const val ATTACK_S = 0.005
        const val RELEASE_S = 0.3

        /** Begränsaren börjar runda av vid −1 dBFS och når aldrig över 1. */
        const val KNEE = 0.89f

        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            val y = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
            return if (x < 0f) -y else y
        }

        private const val LN10_OVER_20 = 0.11512925f

        fun toDb(linear: Float): Float = (20.0 * kotlin.math.log10(max(linear, 1e-6f).toDouble())).toFloat()

        fun fromDb(db: Float): Float = exp(db * LN10_OVER_20)
    }
}

/** Ett andra ordningens filter (RBJ-receptet), transponerad direktform II. */
internal class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + z1
        z1 = b1 * xd - a1 * y + z2
        z2 = b2 * xd - a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    /** Förstärkning i dB vid frekvensen [hz] (för tester). */
    fun gainDbAt(hz: Double, sampleRate: Int): Double {
        val w = 2 * PI * hz / sampleRate
        // |H(e^jw)| för H = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)
        val nr = b0 + b1 * cos(w) + b2 * cos(2 * w)
        val ni = -(b1 * sin(w) + b2 * sin(2 * w))
        val dr = 1 + a1 * cos(w) + a2 * cos(2 * w)
        val di = -(a1 * sin(w) + a2 * sin(2 * w))
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10 * ln(mag2) / ln(10.0)
    }

    companion object {
        fun peaking(sampleRate: Int, hz: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * hz / sampleRate
            val alpha = sin(w0) / (2 * q)
            val cosw = cos(w0)
            val a0 = 1 + alpha / a
            return Biquad(
                b0 = (1 + alpha * a) / a0,
                b1 = (-2 * cosw) / a0,
                b2 = (1 - alpha * a) / a0,
                a1 = (-2 * cosw) / a0,
                a2 = (1 - alpha / a) / a0,
            )
        }
    }
}
