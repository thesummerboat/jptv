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
    fun kommentarer() {
        val json = """{"count": 1234, "comments": [{"id": "c1", "text": "Tack f\u00f6r att ni tittar!", "author": "@kanalen", "authorId": "UCcccccccccccccccccccccc", "avatar": "https://yt3.ggpht.com/c1=s88", "creator": true, "verified": false, "likes": 3400, "replies": 0, "published": 1791653803454, "pinned": true, "hearted": true, "repliesToken": null}, {"id": "c2", "text": "B\u00e4sta videon hittills\nrad tv\u00e5", "author": "@anna", "authorId": "UCcccccccccccccccccccccc", "avatar": "https://yt3.ggpht.com/c2=s88", "creator": false, "verified": false, "likes": 58, "replies": 12, "published": 1792604203454, "pinned": false, "hearted": false, "repliesToken": "REPLIES1"}, {"id": "", "text": "trasig"}], "next": "PAGE2", "disabled": false}"""
        val c = YtJson.comments(JSONObject(json))
        assertEquals(1234L, c.count)
        assertEquals(listOf("c1", "c2"), c.comments.map { it.id })
        assertTrue(c.comments[0].pinned && c.comments[0].hearted && c.comments[0].creator)
        assertNull(c.comments[0].repliesToken)
        assertEquals("REPLIES1", c.comments[1].repliesToken)
        assertEquals(12, c.comments[1].replies)
        assertEquals("Bästa videon hittills\nrad två", c.comments[1].text)
        assertEquals("PAGE2", c.next)
        val off = YtJson.comments(JSONObject("""{"count": null, "comments": [], "next": null, "disabled": true}"""))
        assertTrue(off.disabled && off.comments.isEmpty())
        assertNull(off.count)
        assertNull(off.next)
    }

    @Test
    fun forhandsbilder() {
        val json = JSONObject(video).put(
            "storyboard",
            JSONObject("""{"width": 160, "height": 90, "columns": 3, "rows": 3, "fps": 0.75, "frames": 9, "sheets": 1, "url": "/yt/abcdefghijk/sb/{n}.jpg"}"""),
        )
        val sb = YtJson.videoInfo(json)!!.storyboard!!
        assertEquals("/yt/abcdefghijk/sb/0.jpg", sb.sheetPath(0))
        // 9 s × 0,75 = bild 6: rad 2, kolumn 0. Efter slutet: sista bilden.
        assertEquals(YtStoryboard.Spot(0, 0, 2), sb.spot(9_000))
        assertEquals(YtStoryboard.Spot(0, 1, 0), sb.spot(2_400))
        assertEquals(YtStoryboard.Spot(0, 2, 2), sb.spot(60_000))
        assertEquals(YtStoryboard.Spot(0, 0, 0), sb.spot(-5))
        // Flera ark: bild 10 med 3×3 per ark ligger i ark 1.
        val many = sb.copy(frames = 30, sheets = 4)
        assertEquals(YtStoryboard.Spot(1, 1, 0), many.spot((10 / 0.75 * 1000).toLong() + 1))
        // Utan förhandsbilder (äldre JPTV Web eller video utan) blir det null.
        assertNull(YtJson.videoInfo(JSONObject(video))!!.storyboard)
        assertNull(YtJson.videoInfo(JSONObject(video).put("storyboard", JSONObject.NULL))!!.storyboard)
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
