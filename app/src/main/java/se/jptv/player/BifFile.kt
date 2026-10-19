package se.jptv.player

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Läser BIF (Roku-formatet som Plex använder): rubrik, tabell med tid och position,
 * sedan JPEG-bilder efter varandra. Bara tabellen läses in; bilderna hämtas från disk vid behov.
 */
class BifFile(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val intervalMs: Long
    private val timestamps: LongArray
    private val offsets: LongArray

    init {
        val header = ByteArray(64)
        raf.readFully(header)
        val magic = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)
        if (!header.copyOfRange(0, 8).contentEquals(magic)) throw IOException("Inte en BIF-fil")
        val count = le32(header, 12).toInt()
        if (count < 0 || count > 200_000) throw IOException("Orimligt antal bilder")
        intervalMs = le32(header, 16).let { if (it == 0L) 1_000L else it }
        val table = ByteArray((count + 1) * 8)
        raf.readFully(table)
        timestamps = LongArray(count) { le32(table, it * 8) * intervalMs }
        offsets = LongArray(count + 1) { le32(table, it * 8 + 4) }
    }

    val count: Int get() = timestamps.size

    /** Index för bilden som gäller vid [ms] (senaste bilden som börjar före eller vid tiden). */
    private fun indexFor(ms: Long): Int? {
        if (timestamps.isEmpty()) return null
        var lo = 0
        var hi = timestamps.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (timestamps[mid] <= ms) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Starttiden för bilden som visas vid [ms]; används som nyckel i cachen. */
    fun frameStart(ms: Long): Long? = indexFor(ms)?.let { timestamps[it] }

    @Synchronized
    fun frame(ms: Long): ByteArray? {
        val i = indexFor(ms) ?: return null
        val start = offsets[i]
        val end = offsets[i + 1]
        val size = (end - start).toInt()
        if (size <= 0 || size > 2_000_000) return null
        val bytes = ByteArray(size)
        raf.seek(start)
        raf.readFully(bytes)
        return bytes
    }

    override fun close() = raf.close()

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)
}
