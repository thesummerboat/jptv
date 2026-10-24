package se.jptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtAboutTest {
    @Test
    fun tidIKommentar() {
        assertEquals(260_000L, firstTimestampMs("kolla 4:20 haha"))
        assertEquals(3_723_000L, firstTimestampMs("1:02:03 är bäst"))
        assertEquals(5_000L, firstTimestampMs("(0:05) börjar det"))
        assertNull(firstTimestampMs("inget här"))
        assertNull(firstTimestampMs("klockan 12:345"))
    }
}
