package se.jptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class BoostDspTest {
    private val rate = 48_000

    /** Interfolierad buffert med en sinus per kanal: amplitud per kanal (0 = tyst, negativ = motfas). */
    private fun sine(hz: Double, seconds: Double, amps: FloatArray): FloatArray {
        val ch = amps.size
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * ch) { i ->
            val f = i / ch
            amps[i % ch] * sin(2 * PI * hz * f / rate).toFloat()
        }
    }

    /** RMS för en kanal över andra halvan (efter insvängning). */
    private fun rms(buf: FloatArray, ch: Int, channel: Int): Double {
        val frames = buf.size / ch
        var sum = 0.0
        var n = 0
        for (f in frames / 2 until frames) {
            val v = buf[f * ch + channel].toDouble()
            sum += v * v
            n++
        }
        return sqrt(sum / n)
    }

    private fun peak(buf: FloatArray, ch: Int, channel: Int): Float {
        val frames = buf.size / ch
        var p = 0f
        for (f in frames / 2 until frames) p = maxOf(p, abs(buf[f * ch + channel]))
        return p
    }

    private fun db(after: Double, before: Double) = 20 * log10(after / before)

    @Test
    fun avstängd_rör_inte_ljudet() {
        val buf = sine(1000.0, 0.1, floatArrayOf(0.5f, 0.3f))
        val copy = buf.copyOf()
        BoostDsp(rate, 2).process(buf, buf.size / 2)
        assertTrue(buf.contentEquals(copy))
    }

    @Test
    fun dialog_lyfter_mitten_och_sänker_sidan_i_stereo() {
        val dsp = BoostDsp(rate, 2).apply { dialogue = true }
        val voice = sine(2_400.0, 0.5, floatArrayOf(0.25f, 0.25f))
        val before = rms(voice, 2, 0)
        dsp.process(voice, voice.size / 2)
        val gain = db(rms(voice, 2, 0), before)
        assertEquals("rösten (lika i båda kanalerna) ska lyftas cirka 7 dB", 7.0, gain, 1.0)

        val dsp2 = BoostDsp(rate, 2).apply { dialogue = true }
        val wide = sine(2_400.0, 0.5, floatArrayOf(0.25f, -0.25f))
        val wideBefore = rms(wide, 2, 0)
        dsp2.process(wide, wide.size / 2)
        assertEquals("motfas (musik/rum) ska sänkas", -4.5, db(rms(wide, 2, 0), wideBefore), 0.5)
    }

    @Test
    fun dialog_lyfter_centerkanalen_i_5_1() {
        val dsp = BoostDsp(rate, 6).apply { dialogue = true }
        val buf = sine(2_400.0, 0.5, floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f, 0.2f))
        val center = rms(buf, 6, 2)
        val left = rms(buf, 6, 0)
        val lfe = rms(buf, 6, 3)
        dsp.process(buf, buf.size / 6)
        assertEquals(10.0, db(rms(buf, 6, 2), center), 1.0)
        assertEquals(-2.0, db(rms(buf, 6, 0), left), 0.3)
        assertEquals(-3.0, db(rms(buf, 6, 3), lfe), 0.3)
    }

    @Test
    fun nattläge_sänker_starkt_och_lyfter_svagt() {
        val loud = sine(440.0, 1.0, floatArrayOf(0.9f, 0.9f))
        BoostDsp(rate, 2).apply { night = true }.process(loud, loud.size / 2)
        assertTrue("starkt ljud ska sänkas, var ${peak(loud, 2, 0)}", peak(loud, 2, 0) < 0.45f)

        val quiet = sine(440.0, 1.0, floatArrayOf(0.01f, 0.01f))
        BoostDsp(rate, 2).apply { night = true }.process(quiet, quiet.size / 2)
        assertTrue("svagt ljud ska lyftas, var ${peak(quiet, 2, 0)}", peak(quiet, 2, 0) > 0.015f)
    }

    @Test
    fun aldrig_över_full_nivå() {
        val dsp = BoostDsp(rate, 2).apply {
            dialogue = true
            night = true
        }
        val buf = sine(2_400.0, 0.3, floatArrayOf(1f, 1f))
        dsp.process(buf, buf.size / 2)
        assertTrue(buf.all { abs(it) <= 1f })
        assertEquals(0.5f, BoostDsp.softClip(0.5f), 0f)
        assertTrue(BoostDsp.softClip(5f) <= 1f)
        assertTrue(BoostDsp.softClip(-5f) >= -1f)
    }

    @Test
    fun filtret_lyfter_röstområdet_men_inte_basen() {
        val f = Biquad.peaking(rate, 2_400.0, 0.8, 6.0)
        assertEquals(6.0, f.gainDbAt(2_400.0, rate), 0.05)
        assertEquals(0.0, f.gainDbAt(100.0, rate), 0.1)
    }

    @Test
    fun bufferten_följer_minnet_med_tak_och_golv() {
        val mb = 1024L * 1024L
        assertEquals((256 * mb).toInt(), PlayerBuffer.targetBytes(512 * mb))
        assertEquals((400 * mb).toInt(), PlayerBuffer.targetBytes(2048 * mb))
        assertEquals((64 * mb).toInt(), PlayerBuffer.targetBytes(96 * mb))
    }
}
