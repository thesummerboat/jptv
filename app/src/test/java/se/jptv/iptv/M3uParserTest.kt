package se.jptv.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz"
        #EXTINF:-1 tvg-id="svt1.se" tvg-name="SVT1" tvg-logo="http://logo/svt1.png" group-title="Sverige",SVT1 HD
        http://example.com/live/user/pass/1001.ts
        #EXTINF:-1 tvg-id="" group-title="Sport, Hockey",C More Hockey
        #EXTVLCOPT:http-user-agent=VLC/3.0
        #EXTVLCOPT:http-referrer=http://ref.example
        http://example.com/hockey/index.m3u8
        #EXTINF:-1,Utan grupp
        #EXTGRP:Nyheter
        http://example.com/news.ts
        #EXTINF:-1 group-title="Filmer",En film (2020)
        http://example.com/movie/user/pass/555.mkv
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun parsesLiveChannelsAndSkipsVod() {
        val p = M3uParser.parse(sample)
        assertEquals("http://epg.example/guide.xml.gz", p.epgUrl)
        assertEquals(3, p.channels.size)

        val svt = p.channels[0]
        assertEquals("SVT1 HD", svt.name)
        assertEquals("Sverige", svt.group)
        assertEquals("svt1.se", svt.tvgId)
        assertEquals("http://logo/svt1.png", svt.logo)
        assertEquals("http://example.com/live/user/pass/1001.ts", svt.url)

        val hockey = p.channels[1]
        assertEquals("C More Hockey", hockey.name)
        assertEquals("Sport, Hockey", hockey.group)
        assertNull(hockey.tvgId)
        assertEquals("VLC/3.0", hockey.userAgent)
        assertEquals("http://ref.example", hockey.referrer)

        val news = p.channels[2]
        assertEquals("Utan grupp", news.name)
        assertEquals("Nyheter", news.group)
        assertNull(news.userAgent)

        assertEquals(listOf("Sverige", "Sport, Hockey", "Nyheter"), p.groups)
    }

    @Test
    fun keepsVodWhenAsked() {
        assertEquals(4, M3uParser.parse(sample, liveOnly = false).channels.size)
    }

    @Test
    fun recognisesLiveAndVodUrls() {
        assertTrue(M3uParser.isLive("http://host:8080/user/pass/12345"))
        assertTrue(M3uParser.isLive("http://host/stream.m3u8?token=abc"))
        assertFalse(M3uParser.isLive("http://host:8080/series/user/pass/99.mp4"))
        assertFalse(M3uParser.isLive("http://host/film.MKV"))
    }

    @Test
    fun missingGroupFallsBackToOvrigt() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-name=\"Kanal X\",\nhttp://x/1.ts\n")
        assertEquals(1, p.channels.size)
        assertEquals("Kanal X", p.channels[0].name)
        assertEquals("Övrigt", p.channels[0].group)
        assertNull(p.epgUrl)
    }
}
