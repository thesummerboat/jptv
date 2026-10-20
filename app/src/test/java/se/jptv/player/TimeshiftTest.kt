package se.jptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeshiftTest {
    @Test
    fun offsetForTimeBehindLive() {
        // 1 MB/s, 100 MB inspelat: 30 s efter live = 70 MB in i filen, på en paketgräns.
        val offset = TimeshiftMath.offsetFor(30_000, 100_000_000, 1_000_000.0)!!
        assertEquals(0L, offset % 188)
        assertEquals(70_000_000.0, offset.toDouble(), 188.0)
        // Längre bak än inspelningen: början av filen.
        assertEquals(0L, TimeshiftMath.offsetFor(600_000, 100_000_000, 1_000_000.0))
        // Live: slutet av filen.
        assertEquals(100_000_000L - 100_000_000L % 188, TimeshiftMath.offsetFor(0, 100_000_000, 1_000_000.0))
        assertNull(TimeshiftMath.offsetFor(30_000, 1_000, 0.0))
    }
}
