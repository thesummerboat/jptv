package se.jptv.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JptvWebAddressTest {
    @Test
    fun fromPlex() {
        assertEquals("http://192.168.199.3:8099", JptvWebAddress.fromPlex("https://192-168-199-3.abc123.plex.direct:32400"))
        assertEquals("http://192.168.1.10:8099", JptvWebAddress.fromPlex("http://192.168.1.10:32400"))
        assertEquals("http://unraid.local:8099", JptvWebAddress.fromPlex("http://unraid.local:32400/"))
        assertNull(JptvWebAddress.fromPlex(null))
        assertNull(JptvWebAddress.fromPlex(""))
    }

    @Test
    fun normalize() {
        assertEquals("http://192.168.1.10:8099", JptvWebAddress.normalize("192.168.1.10"))
        assertEquals("http://unraid:9000", JptvWebAddress.normalize("unraid:9000/"))
        assertEquals("https://jptv.example:443", JptvWebAddress.normalize("https://jptv.example:443"))
        assertEquals("http://jptv:8099/x", JptvWebAddress.normalize("http://jptv/x"))
        assertNull(JptvWebAddress.normalize("  "))
    }
}
