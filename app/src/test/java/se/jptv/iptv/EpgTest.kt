package se.jptv.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream

class EpgTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="test">
          <channel id="SVT1.se"><display-name lang="sv">SVT1</display-name></channel>
          <channel id="tv4.se"><display-name>TV4 HD</display-name></channel>
          <channel id="annan.de"><display-name>Annan Kanal</display-name></channel>
          <programme start="20261019180000 +0200" stop="20261019190000 +0200" channel="SVT1.se">
            <title lang="sv">Rapport</title><desc lang="sv">Nyheter &amp; väder</desc>
          </programme>
          <programme start="20261019190000 +0200" stop="20261019200000 +0200" channel="SVT1.se">
            <title>Sportnytt</title><category>Sport</category>
          </programme>
          <programme start="20261019183000 +0200" stop="20261019193000 +0200" channel="tv4.se">
            <title>Nyhetsmorgon</title>
          </programme>
          <programme start="20261019180000 +0200" stop="20261019190000 +0200" channel="annan.de">
            <title>Ska inte med</title>
          </programme>
          <programme start="20261001080000 +0200" stop="20261001090000 +0200" channel="SVT1.se">
            <title>Gammalt</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun channel(name: String, tvgId: String?) =
        Channel(name = name, url = "http://x/$name", group = "Sverige", logo = null, tvgId = tvgId, userAgent = null, referrer = null)

    private val t1800 = XmltvParser.parseTime("20261019180000 +0200")!!

    private fun parse(): Epg = XmltvParser.parse(
        input = ByteArrayInputStream(xml.toByteArray()),
        parser = KXmlParser(),
        wantedIds = setOf("svt1.se"),
        wantedNames = setOf(Epg.normalize("SVT1 HD"), Epg.normalize("TV4")),
        from = t1800 - 3 * 3_600_000L,
        to = t1800 + 36 * 3_600_000L,
        now = t1800,
    )

    @Test
    fun parsesTimeWithOffset() {
        // 18:00 svensk sommartid = 16:00 UTC.
        assertEquals(1_792_425_600_000L, XmltvParser.parseTime("20261019180000 +0200"))
        assertEquals(1_792_425_600_000L, XmltvParser.parseTime("20261019160000 +0000"))
        assertEquals(1_792_425_600_000L, XmltvParser.parseTime("20261019160000"))
        assertNull(XmltvParser.parseTime("igår"))
        // Utan sekunder, negativ tidszon och skottdag.
        assertEquals(1_792_425_600_000L, XmltvParser.parseTime("202610191200 -0400"))
        assertEquals(1_582_934_400_000L, XmltvParser.parseTime("20200229000000 +0000"))
        assertEquals(0L, XmltvParser.parseTime("19700101010000 +0100"))
    }

    @Test
    fun toleratesBadEntitiesAndBrokenTail() {
        val broken = """
            <tv>
              <channel id="svt1.se"><display-name>SVT1</display-name></channel>
              <programme start="20261019180000 +0200" stop="20261019190000 +0200" channel="svt1.se">
                <title>Rapport&nbsp;special</title><desc>A & B</desc>
              </programme>
              <programme start="20261019190000 +0200" stop="20261019200000 +0200" channel="svt1.se">
                <title>Sportnytt</title>
              </programme>
              <programme start="2026
        """.trimIndent()
        val epg = XmltvParser.parse(
            input = ByteArrayInputStream(broken.toByteArray()),
            parser = KXmlParser(),
            wantedIds = setOf("svt1.se"),
            wantedNames = emptySet(),
            from = t1800 - 3_600_000L,
            to = t1800 + 24 * 3_600_000L,
            now = t1800,
        )
        val titles = epg.programmesFor(channel("SVT1", "svt1.se")).map { it.title }
        assertEquals(2, titles.size)
        assertEquals("Sportnytt", titles[1])
    }

    @Test
    fun keepsProgrammesWithoutChannelElementWhenTvgIdMatches() {
        val noChannels = """
            <tv>
              <programme start="20261019180000 +0200" stop="20261019190000 +0200" channel="TV4.se">
                <title>Nyheterna</title>
              </programme>
            </tv>
        """.trimIndent()
        val epg = XmltvParser.parse(
            input = ByteArrayInputStream(noChannels.toByteArray()),
            parser = KXmlParser(),
            wantedIds = setOf("tv4.se"),
            wantedNames = emptySet(),
            from = t1800 - 3_600_000L,
            to = t1800 + 24 * 3_600_000L,
            now = t1800,
        )
        assertEquals("Nyheterna", epg.now(channel("TV4", "TV4.se"), t1800 + 60_000)?.title)
    }

    @Test
    fun keepsOnlyWantedChannelsAndWindow() {
        val epg = parse()
        assertEquals(2, epg.channelCount)
        val svt = channel("SVT1 HD", "svt1.se")
        assertEquals(listOf("Rapport", "Sportnytt"), epg.programmesFor(svt).map { it.title })
        assertEquals("Nyheter & väder", epg.programmesFor(svt).first().desc)
        assertNull(epg.programmesFor(svt)[1].desc)
    }

    @Test
    fun matchesByNameWhenTvgIdIsMissing() {
        val epg = parse()
        val tv4 = channel("SE: TV4 FHD", null)
        assertEquals("Nyhetsmorgon", epg.now(tv4, t1800 + 45 * 60_000L)?.title)
    }

    @Test
    fun nowAndNext() {
        val epg = parse()
        val svt = channel("SVT1", "SVT1.se")
        val at = t1800 + 30 * 60_000L
        assertEquals("Rapport", epg.now(svt, at)?.title)
        assertEquals("Sportnytt", epg.next(svt, at)?.title)
        assertEquals(0.5f, epg.now(svt, at)!!.progress(at), 0.001f)
        assertNull(epg.now(channel("Okänd", null), at))
    }

    @Test
    fun normalizesChannelNames() {
        assertEquals("svt1", Epg.normalize("SE: SVT1 HD"))
        assertEquals("svt1", Epg.normalize("|SE| SVT1 (backup)"))
        assertEquals("tv4sportlive1", Epg.normalize("TV4 Sport Live 1 FHD"))
        assertEquals("kanal5", Epg.normalize("Kanal 5"))
    }

    @Test
    fun guessesXtreamGuideUrl() {
        assertEquals(
            "http://host.example:8080/xmltv.php?username=jonas&password=hemligt",
            IptvUrls.guessEpgUrl("http://host.example:8080/get.php?username=jonas&password=hemligt&type=m3u_plus&output=ts", null),
        )
        assertNull(IptvUrls.guessEpgUrl("http://host.example/lista.m3u", null))
        val withHeader = Playlist(emptyList(), "http://epg.example/guide.xml.gz, http://backup/guide.xml")
        assertEquals("http://epg.example/guide.xml.gz", IptvUrls.guessEpgUrl("http://x/get.php?username=a&password=b", withHeader))
        assertNotNull(IptvUrls.guessEpgUrl("http://x/get.php?username=a&password=b", Playlist(emptyList(), null)))
    }
}
