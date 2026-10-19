package se.jptv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSyncTest {
    private val srt = """
        1
        00:00:01,000 --> 00:00:02,500
        Hej!

        2
        01:59:59,900 --> 02:00:00,100
        Klockan 12:00:00,000 i texten ska inte ändras
    """.trimIndent()

    @Test
    fun shiftsLaterAndEarlier() {
        val later = SubtitleSync.shiftSrt(srt, 500)
        assert(later.contains("00:00:01,500 --> 00:00:03,000"))
        assert(later.contains("02:00:00,400 --> 02:00:00,600"))
        assert(later.contains("Klockan 12:00:00,000 i texten ska inte ändras"))

        val earlier = SubtitleSync.shiftSrt(srt, -1_500)
        assert(earlier.contains("00:00:00,000 --> 00:00:01,000"))
    }

    @Test
    fun zeroOffsetIsUnchanged() {
        assertEquals(srt, SubtitleSync.shiftSrt(srt, 0))
    }

    @Test
    fun labels() {
        assertEquals("+0,5 s", SubtitleSync.label(500))
        assertEquals("−1,2 s", SubtitleSync.label(-1_200))
        assertEquals("0,0 s", SubtitleSync.label(0))
    }
}
