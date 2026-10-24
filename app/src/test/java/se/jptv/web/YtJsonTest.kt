package se.jptv.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Samma JSON som JPTV Web skickar (hämtad från servern), så att TV:n och servern förstår varandra. */
class YtJsonTest {
    private val home = """
        {"continue":[{"id":"abcdefghijk","title":"Nyaste videon","channel":"Testkanalen","channelId":"UCabcdefghijklmnopqrstuv","thumb":"https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg","duration":600,"position":125,"published":null,"views":null,"live":false,"short":false}],
         "feed":[{"id":"abcdefghijk","title":"Nyaste videon","channel":"Testkanalen","channelId":"UCabcdefghijklmnopqrstuv","duration":600,"views":1234,"published":1792490400000,"thumb":"https://i1.ytimg.com/vi/abcdefghijk/hqdefault.jpg","live":false,"short":false,"watched":false,"position":125},
                 {"id":"olderolder1","title":"Äldre video","channel":"Testkanalen","channelId":"UCabcdefghijklmnopqrstuv","duration":null,"views":null,"published":1790848800000,"thumb":"https://i.ytimg.com/vi/olderolder1/hqdefault.jpg","live":false,"short":false},
                 {"id":"kort","title":"Trasig"}],
         "subs":[{"id":"UCabcdefghijklmnopqrstuv","name":"Testkanalen","avatar":"https://yt3.ggpht.com/avatar=s88","added":1792839634469}]}
    """.trimIndent()

    private val video = """
        {"id": "abcdefghijk", "title": "Testvideo", "channel": "Testkanalen", "channelId": "UCabcdefghijklmnopqrstuv", "description": "En video för testerna.", "duration": 12, "views": 4242, "likes": 42, "published": 1760000000000, "thumb": "https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg", "chapters": [{"title": "Början", "start": 0.0, "end": 6.0}, {"title": "Slutet", "start": 6.0, "end": 12.0}], "sponsor": [{"start": 2.0, "end": 4.5, "category": "sponsor"}], "subtitles": [{"lang": "sv", "name": "Svenska"}], "hls": "/yt/abcdefghijk/master.m3u8", "position": 125.5, "subscribed": true, "maxHeight": 180}
    """.trimIndent()

    @Test
    fun hemmet() {
        val h = YtJson.home(JSONObject(home))
        assertEquals(1, h.continueWatching.size)
        assertEquals(125_000L, h.continueWatching[0].positionMs)
        assertEquals(600_000L, h.continueWatching[0].durationMs)
        assertEquals(125f / 600f, h.continueWatching[0].progress, 0.001f)
        // Trasiga poster (fel id) hoppas över.
        assertEquals(listOf("abcdefghijk", "olderolder1"), h.feed.map { it.id })
        assertNull(h.feed[1].durationMs)
        assertNull(h.feed[1].views)
        assertEquals(1234L, h.feed[0].views)
        assertEquals("Testkanalen", h.subs[0].name)
        assertTrue(h.subs[0].subscribed)
    }

    @Test
    fun videon() {
        val v = YtJson.videoInfo(JSONObject(video))!!
        assertEquals(12_000L, v.durationMs)
        assertEquals(125_500L, v.positionMs)
        assertEquals(listOf(0L, 6_000L), v.chapters.map { it.startMs })
        assertEquals(YtSegment(2_000L, 4_500L, "sponsor"), v.sponsor.single())
        assertEquals("/yt/abcdefghijk/master.m3u8", v.hls)
        assertTrue(v.subscribed)
    }

    @Test
    fun text() {
        assertEquals("1234 tn visningar".replace("1234", "1"), YtJson.views(1_234))
        assertEquals("999 visningar", YtJson.views(999))
        assertEquals("för 2 dagar sedan", YtJson.ago(0L + 1, now = 2 * 86_400_000L + 10))
        assertEquals("nyss", YtJson.ago(1_000L, now = 5_000L))
        assertNull(YtJson.ago(null))
        assertFalse(YtJson.views(2_500_000)!!.isBlank())
    }
}
