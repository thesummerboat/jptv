package se.jptv.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Samma JSON som JPTV Web skickar från /api/discover, med och utan Radarr 4K. */
class WishItemTest {
    private fun parse(json: String) = WishItem.parse(JSONObject(json))

    private val base = """"guid":"plex://movie/nyfilm1","key":"nyfilm1","type":"movie","title":"Polarexpressen 2","year":2026"""

    @Test
    fun utan4K() {
        val w = parse("""{$base,"libraryKey":null,"hdKey":null,"request":null}""")
        assertFalse(w.uhdEnabled)
        assertTrue(w.canWish)
        assertFalse(w.canWishUhd)
        assertNull(w.statusText)
    }

    @Test
    fun finnsIPlexMenKanÖnskasI4K() {
        val w = parse("""{$base,"libraryKey":"101","hdKey":"101","uhdKey":null,"request":null,"requestUhd":null}""")
        assertTrue(w.uhdEnabled)
        assertFalse(w.canWish)
        assertTrue(w.canWishUhd)
    }

    @Test
    fun önskadI4K() {
        val w = parse("""{$base,"libraryKey":null,"hdKey":null,"uhdKey":null,"request":{"id":"a","status":"approved"},"requestUhd":{"id":"b","status":"pending"}}""")
        assertFalse(w.canWish)
        assertFalse(w.canWishUhd)
        assertEquals("Godkänd · på väg · 4K: Önskad · väntar på OK", w.statusText)
    }

    @Test
    fun finnsBaraI4K() {
        val w = parse("""{$base,"libraryKey":"uhd1","hdKey":null,"uhdKey":"uhd1","request":null,"requestUhd":null}""")
        assertTrue(w.canWish)
        assertFalse(w.canWishUhd)
    }

    @Test
    fun nekat4KGårAttÖnskaIgen() {
        val w = parse("""{$base,"libraryKey":"101","hdKey":"101","uhdKey":null,"request":null,"requestUhd":{"id":"b","status":"declined"}}""")
        assertTrue(w.canWishUhd)
    }

    @Test
    fun serierÖnskasInteI4K() {
        val w = parse("""{"guid":"plex://show/s","key":"s","type":"show","title":"Serie","libraryKey":null,"uhdKey":null}""")
        assertFalse(w.canWishUhd)
        assertTrue(w.canWish)
    }

    @Test
    fun skickarUhd() {
        val w = parse("""{$base,"libraryKey":null}""")
        assertTrue(w.toJson("Jonas", uhd = true).getBoolean("uhd"))
        assertFalse(w.toJson("Jonas").getBoolean("uhd"))
        assertEquals("Jonas", w.toJson("Jonas").getString("by"))
    }
}
