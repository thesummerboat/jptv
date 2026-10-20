package se.jptv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterIndexTest {
    @Test
    fun lettersForTitles() {
        assertEquals("M", LetterIndex.letterOf("Matrix, The"))
        assertEquals("Ä", LetterIndex.letterOf("älskade"))
        assertEquals("Ö", LetterIndex.letterOf("Ötzi"))
        assertEquals("E", LetterIndex.letterOf("Éclair"))
        assertEquals("#", LetterIndex.letterOf("2001: A Space Odyssey"))
        assertEquals("#", LetterIndex.letterOf("  "))
        assertEquals("A", LetterIndex.letterOf("\"Alien\""))
    }

    @Test
    fun firstPlaceForEachLetterInListOrder() {
        val titles = listOf("21 Jump Street", "Alien", "Amélie", "Batman", "Bourne", "Matrix, The", "Älskade", "Öster")
        assertEquals(
            listOf("#" to 0, "A" to 1, "B" to 3, "M" to 5, "Ä" to 6, "Ö" to 7),
            LetterIndex.build(titles),
        )
    }
}
