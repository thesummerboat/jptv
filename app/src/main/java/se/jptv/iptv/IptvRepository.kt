package se.jptv.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Hämtar spellistan från leverantören och sparar en kopia, så att kanalerna finns direkt vid nästa start.
 * Listan strömmas till fil och läses rad för rad; Xtream-listor med film och serier kan vara 100 MB.
 */
class IptvRepository(private val context: Context, private val http: OkHttpClient) {

    private val cacheFile: File get() = File(context.filesDir, "playlist.m3u")

    suspend fun download(url: String): Playlist = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "playlist.m3u.tmp")
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Spellistan svarade HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Tom spellista")
            body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        }
        val playlist = tmp.useLines { M3uParser.parse(it) }
        if (playlist.channels.isEmpty()) {
            val hasEntries = tmp.useLines { lines -> lines.any { it.startsWith("#EXTINF") } }
            tmp.delete()
            throw IOException(if (hasEntries) "Spellistan innehöll inga live-kanaler" else "Adressen gav ingen M3U-spellista")
        }
        tmp.delete()
        // Spara bara live-kanalerna. Leverantörens filmer och serier kan vara 50 MB som annars
        // skulle läsas igenom vid varje start.
        val compact = File(context.filesDir, "playlist.m3u.new")
        compact.bufferedWriter().use { M3uParser.write(playlist, it) }
        if (!compact.renameTo(cacheFile)) {
            compact.copyTo(cacheFile, overwrite = true)
            compact.delete()
        }
        playlist
    }

    suspend fun cached(): Playlist? = withContext(Dispatchers.IO) {
        if (cacheFile.exists()) cacheFile.useLines { M3uParser.parse(it) } else null
    }

    fun clearCache() {
        cacheFile.delete()
    }

    companion object {
        /** Många leverantörer släpper bara in kända spelare, så vi presenterar oss som VLC. */
        const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"
    }
}
