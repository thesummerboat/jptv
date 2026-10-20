package se.jptv.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvCastTest {
    @Test
    fun hlsVariantOfChannelUrls() {
        assertEquals("http://x.tv/live/u/p/123.m3u8", IptvCast.hlsUrl("http://x.tv/live/u/p/123.ts"))
        assertEquals("http://x.tv/u/p/123.m3u8", IptvCast.hlsUrl("http://x.tv/u/p/123"))
        assertEquals("https://x.tv/a.m3u8?t=1", IptvCast.hlsUrl("https://x.tv/a.m3u8?t=1"))
        assertEquals("http://x.tv/live/u/p/9.m3u8?token=a", IptvCast.hlsUrl("http://x.tv/live/u/p/9.ts?token=a"))
        assertEquals("http://x.tv/stream.mpd", IptvCast.hlsUrl("http://x.tv/stream.mpd"))
    }
}
