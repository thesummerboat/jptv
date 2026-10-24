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

    private val recs = """
        {"recommended": [{"id": "rel00000001", "title": "Förslag 1", "channel": "Kanal A", "channelId": "UCaaaaaaaaaaaaaaaaaaaaaa", "avatar": "https://yt3.ggpht.com/UCaaaaaaaaaaaaaaaaaaaaaa=s68", "duration": 754, "views": 1200000, "published": 1792583305640, "thumb": "https://i.ytimg.com/vi/rel00000001/hqdefault.jpg?sqp=big", "live": false, "short": false}, {"id": "rel00000005", "title": "Förslag 5", "channel": "Kanal A", "channelId": "UCaaaaaaaaaaaaaaaaaaaaaa", "avatar": null, "duration": 754, "views": 1200000, "published": 1792583314229, "thumb": "https://i.ytimg.com/vi/rel00000005/hqdefault.jpg?sqp=big", "live": false, "short": false}],
         "because": [{"seed": {"id": "abcdefghijk", "title": "Testvideo"}, "videos": [{"id": "rel00000001", "title": "Förslag 1", "channel": "Kanal A", "channelId": "UCaaaaaaaaaaaaaaaaaaaaaa", "duration": 754, "views": 1200000, "published": 1792583305640, "thumb": "https://i.ytimg.com/vi/rel00000001/hqdefault.jpg?sqp=big", "live": false, "short": false}]},
                     {"seed": {"id": "tomtomtomto", "title": "Tom rad"}, "videos": []}],
         "channels": [{"id": "UCaaaaaaaaaaaaaaaaaaaaaa", "name": "Kanal A", "avatar": "https://yt3.ggpht.com/UCaaaaaaaaaaaaaaaaaaaaaa=s68", "videos": 5, "subscribed": false}],
         "seeds": 2}
    """.trimIndent()

    @Test
    fun forslagen() {
        val r = YtJson.recs(JSONObject(recs))
        assertEquals(listOf("rel00000001", "rel00000005"), r.recommended.map { it.id })
        assertEquals(754_000L, r.recommended[0].durationMs)
        assertEquals(1_200_000L, r.recommended[0].views)
        assertEquals(1792583305640L, r.recommended[0].published)
        // Rader utan videor visas inte.
        assertEquals(1, r.because.size)
        assertEquals("Testvideo", r.because[0].seedTitle)
        assertEquals("Kanal A", r.channels[0].name)
        assertFalse(r.channels[0].subscribed)
        assertEquals(2, r.seeds)
        // Tomt svar (inget sett, inga kanaler).
        val empty = YtJson.recs(JSONObject("""{"recommended": [], "because": [], "channels": [], "seeds": 0}"""))
        assertTrue(empty.recommended.isEmpty() && empty.because.isEmpty() && empty.channels.isEmpty())
        assertEquals("1 visning", YtJson.views(1))
    }

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
