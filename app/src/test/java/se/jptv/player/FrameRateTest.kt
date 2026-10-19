package se.jptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FrameRateTest {
    private val modes4k = listOf(
        ModeInfo(1, 3840, 2160, 60f),
        ModeInfo(2, 3840, 2160, 59.94f),
        ModeInfo(3, 3840, 2160, 50f),
        ModeInfo(4, 3840, 2160, 30f),
        ModeInfo(5, 3840, 2160, 25f),
        ModeInfo(6, 3840, 2160, 24f),
        ModeInfo(7, 3840, 2160, 23.976f),
        ModeInfo(8, 1920, 1080, 23.976f),
    )
    private val at60 = modes4k[0]

    @Test
    fun choosesMatchingRefreshRate() {
        assertEquals(7, FrameRateMatcher.choose(23.976f, at60, modes4k)?.id)
        assertEquals(6, FrameRateMatcher.choose(24f, at60, modes4k)?.id)
        // 25 och 29,97 bild/s: dubbla frekvensen, inte 25/30 Hz.
        assertEquals(3, FrameRateMatcher.choose(25f, at60, modes4k)?.id)
        assertEquals(2, FrameRateMatcher.choose(29.97f, at60, modes4k)?.id)
        assertEquals(3, FrameRateMatcher.choose(50f, at60, modes4k)?.id)
    }

    @Test
    fun keepsCurrentModeWhenItFits() {
        assertNull(FrameRateMatcher.choose(60f, at60, modes4k))
        assertNull(FrameRateMatcher.choose(25f, modes4k[2], modes4k))
        assertNull(FrameRateMatcher.choose(30f, at60, modes4k))
    }

    @Test
    fun keepsResolutionAndGivesUpWhenNothingFits() {
        val only60 = listOf(ModeInfo(1, 1920, 1080, 60f), ModeInfo(2, 3840, 2160, 50f))
        assertNull(FrameRateMatcher.choose(25f, only60[0], only60))
        assertNull(FrameRateMatcher.choose(0f, at60, modes4k))
    }

    @Test
    fun measuresFrameRateFromTimestamps() {
        val film = LongArray(48) { it * 1_000_000L * 1001 / 24_000 }
        assertEquals(23.976f, FrameRateMatcher.fromTimestamps(film, film.size))
        val pal = LongArray(48) { it * 40_000L }
        assertEquals(25f, FrameRateMatcher.fromTimestamps(pal, pal.size))
        val ntsc = LongArray(48) { it * 1_000_000L * 1001 / 60_000 }
        assertEquals(59.94f, FrameRateMatcher.fromTimestamps(ntsc, ntsc.size))
    }

    @Test
    fun readsBifFile() {
        val images = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), byteArrayOf(6))
        val file = File.createTempFile("test", ".bif")
        try {
            val out = java.io.ByteArrayOutputStream()
            fun le(v: Long) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            out.write(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a))
            le(0)
            le(images.size.toLong())
            le(2_000)
            out.write(ByteArray(44))
            var offset = 64L + (images.size + 1) * 8
            images.forEachIndexed { i, img ->
                le(i.toLong())
                le(offset)
                offset += img.size
            }
            le(0xFFFFFFFFL)
            le(offset)
            images.forEach { out.write(it) }
            file.writeBytes(out.toByteArray())

            BifFile(file).use { bif ->
                assertEquals(3, bif.count)
                assertEquals(2_000L, bif.intervalMs)
                assertEquals(listOf<Byte>(1, 2, 3), bif.frame(0)!!.toList())
                assertEquals(listOf<Byte>(4, 5), bif.frame(3_999)!!.toList())
                assertEquals(listOf<Byte>(6), bif.frame(60_000)!!.toList())
                assertEquals(4_000L, bif.frameStart(5_000))
            }
        } finally {
            file.delete()
        }
    }
}
