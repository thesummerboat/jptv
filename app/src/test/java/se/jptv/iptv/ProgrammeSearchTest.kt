package se.jptv.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ProgrammeSearchTest {
    private fun t(s: String) = XmltvParser.parseTime("$s +0100")!!
    private val stockholm = TimeZone.getTimeZone("Europe/Stockholm")

    // Torsdag 29 oktober 2026, 20:30 svensk tid.
    private val now = t("20261029203000")

    private fun channel(name: String, tvgId: String) =
        Channel(name = name, url = "http://x/$name", group = "Sverige", logo = null, tvgId = tvgId, userAgent = null, referrer = null)

    private val svt1Hd = channel("SVT1 HD", "svt1.se")
    private val svt1Fhd = channel("SVT1 FHD", "svt1.se")
    private val cmore = channel("C More Hockey", "cmorehockey.se")

    private val epg = Epg(
        programmes = mapOf(
            "svt1.se" to listOf(
                Programme(t("20261029180000"), t("20261029183000"), "Rapport", null),
                Programme(t("20261029200000"), t("20261029210000"), "Hockeykväll", null),
                Programme(t("20261030193000"), t("20261030200000"), "Rapport", null),
            ),
            "cmorehockey.se" to listOf(
                Programme(t("20261029190000"), t("20261029220000"), "Hockey: Frölunda – Luleå", "SHL"),
                Programme(t("20261031150000"), t("20261031173000"), "Hockey: Skellefteå – Frölunda", "SHL"),
            ),
        ),
        nameIndex = emptyMap(),
        loadedAt = 0L,
    )

    private fun titles(hits: List<ProgrammeHit>) = hits.map { it.programme.title }

    @Test
    fun allaOrdenMåsteFinnasIOrdning() {
        val hits = ProgrammeSearch.find(listOf(svt1Hd, cmore), epg, "frölunda HOCKEY", now)
        assertEquals(listOf("Hockey: Frölunda – Luleå", "Hockey: Skellefteå – Frölunda"), titles(hits))
        assertEquals(cmore, hits[0].channel)
    }

    @Test
    fun detSomGårNuFörstOchSlutadeProgramMedInte() {
        val hits = ProgrammeSearch.find(listOf(svt1Hd, cmore), epg, "hockey", now)
        // Matchen började 19:00, Hockeykväll 20:00; båda går nu. Lördagens match sist.
        assertEquals(listOf("Hockey: Frölunda – Luleå", "Hockeykväll", "Hockey: Skellefteå – Frölunda"), titles(hits))
        assertTrue(hits[0].isLive(now))
        assertFalse(hits[2].isLive(now))
        // Kvällens Rapport har slutat; morgondagens finns kvar.
        assertEquals(listOf(t("20261030193000")), ProgrammeSearch.find(listOf(svt1Hd), epg, "rapport", now).map { it.programme.start })
    }

    @Test
    fun sammaProgramPåFleraKanalvarianterVisasEnGång() {
        // Favoriten (FHD) först: den kanalen vinner.
        val hits = ProgrammeSearch.find(listOf(svt1Fhd, svt1Hd), epg, "hockeykväll", now)
        assertEquals(1, hits.size)
        assertEquals(svt1Fhd, hits[0].channel)
    }

    @Test
    fun förKortSökning() {
        assertTrue(ProgrammeSearch.find(listOf(svt1Hd), epg, "r", now).isEmpty())
        assertTrue(ProgrammeSearch.find(listOf(svt1Hd), epg, "   ", now).isEmpty())
        assertTrue(ProgrammeSearch.find(listOf(svt1Hd, cmore), epg, "o", now).isEmpty())
        assertEquals(1, ProgrammeSearch.find(listOf(svt1Hd, cmore), epg, "ho", now, limit = 1).size)
    }

    @Test
    fun börjarSnartGårAttTitta() {
        val soon = ProgrammeHit(svt1Hd, Programme(now + 3 * 60_000, now + 60 * 60_000, "Snart", null))
        val later = ProgrammeHit(svt1Hd, Programme(now + 10 * 60_000, now + 60 * 60_000, "Senare", null))
        assertTrue(soon.isWatchable(now))
        assertFalse(later.isWatchable(now))
    }

    @Test
    fun närProgrammetGår() {
        fun text(start: String, stop: String) = ProgrammeSearch.whenText(Programme(t(start), t(stop), "x", null), now, stockholm)
        assertEquals("Nu · 20:00–21:00", text("20261029200000", "20261029210000"))
        assertEquals("I dag 22:00–23:00", text("20261029220000", "20261029230000"))
        assertEquals("I morgon 06:00–07:00", text("20261030060000", "20261030070000"))
        assertEquals("Lör 15:00–17:30", text("20261031150000", "20261031173000"))
    }
}
