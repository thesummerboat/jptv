package se.jptv.imdb

import kotlin.math.roundToInt

/**
 * Läser IMDb:s öppna betygsfil (title.ratings.tsv: tconst, averageRating, numVotes) till en
 * sorterad lista av tal: id i de övre 32 bitarna, betyg×10 i 8 bitar och antal röster i 24 bitar.
 * Kompakt nog att ligga som fil och slås upp utan att läsas in i minnet.
 */
object ImdbParser {
    fun pack(id: Long, rating: Float, votes: Int): Long {
        val r = (rating * 10).roundToInt().coerceIn(0, 100).toLong()
        val v = votes.coerceIn(0, 0xFFFFFF).toLong()
        return (id shl 32) or (r shl 24) or v
    }

    fun idOf(packed: Long): Long = packed ushr 32

    fun ratingOf(packed: Long): Float = ((packed ushr 24) and 0xFF).toFloat() / 10f

    fun votesOf(packed: Long): Int = (packed and 0xFFFFFF).toInt()

    /** "tt0111161" -> 111161. */
    fun numericId(imdbId: String): Long? = imdbId.removePrefix("tt").toLongOrNull()

    fun parse(lines: Sequence<String>): LongArray {
        var out = LongArray(1 shl 21)
        var n = 0
        for (line in lines) {
            if (!line.startsWith("tt")) continue
            val t1 = line.indexOf('\t')
            if (t1 < 0) continue
            val t2 = line.indexOf('\t', t1 + 1)
            if (t2 < 0) continue
            val id = line.substring(2, t1).toLongOrNull() ?: continue
            val rating = line.substring(t1 + 1, t2).toFloatOrNull() ?: continue
            val votes = line.substring(t2 + 1).trim().toIntOrNull() ?: 0
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = pack(id, rating, votes)
        }
        val result = out.copyOf(n)
        result.sort()
        return result
    }

    /** Binärsökning i en sorterad lista; [get] läser element nummer i. */
    inline fun find(size: Int, id: Long, get: (Int) -> Long): Long? {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = get(mid)
            val vid = v ushr 32
            when {
                vid < id -> lo = mid + 1
                vid > id -> hi = mid - 1
                else -> return v
            }
        }
        return null
    }
}
