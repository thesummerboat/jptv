package se.jptv.plex

import org.junit.Assert.assertEquals
import org.junit.Test

class RatingFormatTest {
    @Test
    fun formatsKnownSources() {
        val r = RatingFormat.format(
            listOf(
                "imdb://image.rating" to 7.6,
                "rottentomatoes://image.rating.ripe" to 9.1,
                "rottentomatoes://image.rating.spilled" to 4.5,
                "themoviedb://image.rating" to 7.84,
                "okänd://x" to 5.0,
                "imdb://image.rating" to 8.0,
            ),
        )
        assertEquals(listOf("IMDb 7,6", "RT 91 %", "RT publik 45 %", "TMDB 7,8"), r.map { it.text })
    }
}
