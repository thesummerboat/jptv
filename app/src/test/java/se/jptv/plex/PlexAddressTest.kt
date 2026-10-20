package se.jptv.plex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexAddressTest {
    @Test
    fun localAndRemoteAddresses() {
        assertTrue(PlexAddress.isLocal("http://192.168.1.10:32400"))
        assertTrue(PlexAddress.isLocal("https://192-168-1-10.0123abcd.plex.direct:32400"))
        assertTrue(PlexAddress.isLocal("https://10-0-0-5.0123abcd.plex.direct:32400"))
        assertTrue(PlexAddress.isLocal("http://172.20.0.2:32400"))
        assertFalse(PlexAddress.isLocal("https://85-228-10-20.0123abcd.plex.direct:32400"))
        assertFalse(PlexAddress.isLocal("https://172-32-1-1.0123abcd.plex.direct:32400"))
        assertFalse(PlexAddress.isLocal("https://abc.relay.plex.direct:8443"))
        assertFalse(PlexAddress.isLocal("https://plex.example.com"))
    }
}
