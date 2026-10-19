package se.jptv.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Hämtar spellistan från leverantören och sparar en kopia, så att kanalerna finns direkt vid nästa start. */
class IptvRepository(private val context: Context, private val http: OkHttpClient) {

    private val cacheFile: File get() = File(context.filesDir, "playlist.m3u")

    suspend fun download(url: String): Playlist = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        val text = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Spellistan svarade HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        if (!text.contains("#EXTINF")) throw IOException("Adressen gav ingen M3U-spellista")
        cacheFile.writeText(text)
        M3uParser.parse(text)
    }

    suspend fun cached(): Playlist? = withContext(Dispatchers.IO) {
        if (cacheFile.exists()) M3uParser.parse(cacheFile.readText()) else null
    }

    fun clearCache() {
        cacheFile.delete()
    }

    companion object {
        /** Många leverantörer släpper bara in kända spelare, så vi presenterar oss som VLC. */
        const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"
    }
}
