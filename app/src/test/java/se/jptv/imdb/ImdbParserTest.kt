package se.jptv.imdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImdbParserTest {
    private val tsv = """
        tconst	averageRating	numVotes
        tt0111161	9.3	3012345
        tt0000001	5.7	2100
        tt0068646	9.2	2100000
        trasig rad
        tt9999999	7.0	notanumber
    """.trimIndent()

    @Test
    fun parsesSortsAndFinds() {
        val data = ImdbParser.parse(tsv.lineSequence())
        assertEquals(4, data.size)
        assertEquals(listOf(1L, 68646L, 111161L, 9999999L), data.map { ImdbParser.idOf(it) })
        val found = ImdbParser.find(data.size, ImdbParser.numericId("tt0111161")!!) { data[it] }!!
        assertEquals(9.3f, ImdbParser.ratingOf(found))
        assertEquals(3012345, ImdbParser.votesOf(found))
        assertEquals(0, ImdbParser.votesOf(ImdbParser.find(data.size, 9999999L) { data[it] }!!))
        assertNull(ImdbParser.find(data.size, 42L) { data[it] })
    }

    @Test
    fun packsLargeIdsAndCapsVotes() {
        val p = ImdbParser.pack(35_000_000L, 10f, 50_000_000)
        assertEquals(35_000_000L, ImdbParser.idOf(p))
        assertEquals(10f, ImdbParser.ratingOf(p))
        assertEquals(0xFFFFFF, ImdbParser.votesOf(p))
    }
}
