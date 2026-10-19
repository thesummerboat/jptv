package se.jptv.imdb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * IMDb-betyg för allt i Plex, från IMDb:s öppna datafil (fri för privat, icke-kommersiellt bruk).
 * Filen hämtas en gång i veckan, packas till ~13 MB och läses direkt från disk vid uppslag.
 */
class ImdbRatings(private val context: Context, private val http: OkHttpClient) {
    private val file: File get() = File(context.filesDir, "imdb_ratings.bin")

    @Volatile
    private var buffer: LongBuffer? = null

    val count: Int get() = buffer?.limit() ?: 0

    val updatedAt: Long get() = if (file.exists()) file.lastModified() else 0L

    suspend fun load() = withContext(Dispatchers.IO) {
        if (file.exists() && file.length() >= 8) buffer = runCatching { map(file) }.getOrNull()
    }

    private fun map(f: File): LongBuffer = RandomAccessFile(f, "r").use { raf ->
        // Minnesmappad: ligger inte på appens heap, och mappningen gäller även när filen stängts.
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).asLongBuffer()
    }

    /** Betyg 0–10 och antal röster, eller null om titeln saknas. */
    fun lookup(imdbId: String): Pair<Float, Int>? {
        val b = buffer ?: return null
        val id = ImdbParser.numericId(imdbId) ?: return null
        val packed = ImdbParser.find(b.limit(), id) { b.get(it) } ?: return null
        return ImdbParser.ratingOf(packed) to ImdbParser.votesOf(packed)
    }

    /** Hämtar senaste betygen från IMDb. Returnerar antal titlar. */
    suspend fun refresh(): Int = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(URL).header("User-Agent", "JPTV").get().build()
        val packed = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("IMDb svarade HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Tomt svar från IMDb")
            GZIPInputStream(body.byteStream(), 64 * 1024).bufferedReader().useLines { ImdbParser.parse(it) }
        }
        if (packed.size < 1_000) throw IOException("Betygsfilen från IMDb verkar trasig")
        val tmp = File(context.filesDir, "imdb_ratings.tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { out ->
            for (v in packed) out.writeLong(v)
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        buffer = map(file)
        packed.size
    }

    companion object {
        const val URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"
    }
}
