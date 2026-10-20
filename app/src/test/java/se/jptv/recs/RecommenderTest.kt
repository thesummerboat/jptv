package se.jptv.recs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun movie(
        id: String,
        vararg tags: String,
        engagement: Float = 0f,
        daysAgo: Int = 10,
        rating: Float? = 7f,
        excluded: Boolean = false,
    ) = RecItem(
        id = id,
        type = "movie",
        tags = tags.toList(),
        engagement = engagement,
        lastViewedAt = if (engagement != 0f) now - daysAgo * day else 0L,
        unseen = engagement == 0f,
        rating = rating,
        votes = 200_000,
        addedAt = now - 400 * day,
        excluded = excluded,
    )

    private val library = listOf(
        // Sett och gillat: Nolan och sci-fi.
        movie("inception", "g:Sci-Fi", "g:Thriller", "d:Christopher Nolan", "a:Michael Caine", "y:2010", engagement = 1f),
        movie("interstellar", "g:Sci-Fi", "g:Drama", "d:Christopher Nolan", "a:Michael Caine", "y:2010", engagement = 1.2f),
        movie("arrival", "g:Sci-Fi", "g:Drama", "d:Denis Villeneuve", "y:2010", engagement = 1f, daysAgo = 40),
        // Gav upp tidigt.
        movie("notebook", "g:Romance", "g:Drama", "d:Nick Cassavetes", "y:2000", engagement = -0.4f, daysAgo = 60),
        // Osett.
        movie("tenet", "g:Sci-Fi", "g:Action", "d:Christopher Nolan", "a:Michael Caine", "y:2020"),
        movie("dune", "g:Sci-Fi", "g:Adventure", "d:Denis Villeneuve", "y:2020"),
        movie("prestige", "g:Mystery", "g:Thriller", "d:Christopher Nolan", "a:Michael Caine", "y:2000"),
        movie("loveactually", "g:Romance", "g:Comedy", "d:Richard Curtis", "y:2000"),
        movie("dearjohn", "g:Romance", "g:Drama", "d:Lasse Hallström", "y:2010"),
        movie("blade", "g:Sci-Fi", "g:Thriller", "d:Denis Villeneuve", "y:2010", excluded = true),
        movie("badscifi", "g:Sci-Fi", "y:2010", rating = 3.1f),
        movie("comedy2", "g:Comedy", "d:Richard Curtis", "y:2010"),
    )

    @Test
    fun recommendsTasteAndSkipsWhatYouDislike() {
        val model = Recommender.build(library, now)
        val recs = Recommender.forYou(model, "movie", now)
        assertTrue(recs.isNotEmpty())
        assertTrue("Nolan/sci-fi först: $recs", recs.take(2).all { it in setOf("tenet", "dune", "prestige") })
        assertFalse("Inte intresserad syns inte", "blade" in recs)
        assertFalse("Sedda titlar syns inte", "inception" in recs)
        val romance = recs.indexOf("loveactually")
        assertTrue("Romantik långt ner eller inte alls", romance == -1 || romance > recs.indexOf("tenet"))
        assertTrue("Dålig sci-fi under bra sci-fi", recs.indexOf("badscifi") == -1 || recs.indexOf("badscifi") > recs.indexOf("dune"))
    }

    @Test
    fun becauseYouWatchedFindsTheSameDirector() {
        val model = Recommender.build(library, now)
        val similar = Recommender.similarTo(model, "inception")
        // Samma regissör och skådespelare först (The Prestige delar dessutom genren Thriller).
        assertEquals(setOf("tenet", "prestige"), similar.take(2).toSet())
    }

    @Test
    fun recencyAndQuality() {
        assertEquals(1f, Recommender.recency(now, now), 0.001f)
        assertEquals(0.5f, Recommender.recency(now - 180 * day, now), 0.01f)
        assertEquals(0.15f, Recommender.recency(now - 3000 * day, now), 0.001f)
        assertTrue(Recommender.quality(8.5f, 1_000_000) > Recommender.quality(6f, 1_000_000))
        assertTrue(Recommender.quality(3f, 1_000) < 0f)
    }

    @Test
    fun nothingWithoutHistory() {
        val fresh = library.map { it.copy(engagement = 0f, unseen = true, lastViewedAt = 0L) }
        val model = Recommender.build(fresh, now)
        assertTrue(Recommender.forYou(model, "movie", now).isEmpty())
        assertTrue(Recommender.seeds(model, now).isEmpty())
    }

    @Test
    fun dailyMixVariesByDayButNotWithinADay() {
        val ids = (1..40).map { "id$it" }
        val monday = Recommender.dailyMix(ids, day = 20_000)
        assertEquals(monday, Recommender.dailyMix(ids, day = 20_000))
        val tuesday = Recommender.dailyMix(ids, day = 20_001)
        assertFalse(monday == tuesday)
        assertEquals(25, monday.size)
        assertEquals(monday.size, monday.toSet().size)
        assertTrue(monday.all { it in ids })
        // Viktat mot toppen: i snitt hamnar de bästa oftare med än de sämsta.
        var top = 0
        var bottom = 0
        for (d in 0L until 200L) {
            val mix = Recommender.dailyMix(ids, d, take = 10)
            top += mix.count { it in ids.take(10) }
            bottom += mix.count { it in ids.takeLast(10) }
        }
        assertTrue("topp $top, botten $bottom", top > bottom * 2)
    }
}
