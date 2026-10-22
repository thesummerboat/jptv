package se.jptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.jptv.plex.PlexChapter
import se.jptv.plex.PlexMarker

class PlayerControlsTest {
    @Test
    fun tidslinjen_namnger_intro_eftertexter_och_kapitel() {
        val marks = TimelineMarks(
            intro = PlexMarker("intro", 60_000, 120_000),
            credits = PlexMarker("credits", 3_000_000, 3_200_000, final = true),
            chapters = listOf(
                PlexChapter("Början", 0, 600_000),
                PlexChapter("Rånet", 600_000, 1_800_000),
                PlexChapter("Slutet", 1_800_000, 3_200_000),
            ),
        )
        assertEquals("Början", marks.labelAt(10_000))
        assertEquals("Intro", marks.labelAt(90_000))
        assertEquals("Rånet", marks.labelAt(600_000))
        assertEquals("Slutet", marks.labelAt(2_000_000))
        assertEquals("Eftertexter", marks.labelAt(3_100_000))
        assertNull(TimelineMarks(null, null, emptyList()).labelAt(5_000))
    }

    @Test
    fun undertextstil_bläddrar_runt_och_standard_är_tv_ns() {
        val look = SubtitleLook()
        assertTrue(look.isDefault)
        assertNull("standardstorleken följer TV:n", look.sizeFraction)
        assertEquals("Normal", look.sizeLabel)
        assertEquals("TV:ns standard", look.styleLabel)

        var l = look
        repeat(SubtitleLook.SIZES.size) { l = l.nextSize() }
        assertEquals(look, l)

        val big = look.nextSize()
        assertFalse(big.isDefault)
        assertEquals("Stor", big.sizeLabel)
        assertEquals(0.0533f * 1.25f, big.sizeFraction!!, 0.0001f)
        assertEquals(0.15f, look.nextPosition().bottomPadding, 0f)
        assertEquals("Gul", look.nextColor().colorLabel)
    }
}
