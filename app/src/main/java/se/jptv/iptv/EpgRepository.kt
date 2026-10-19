package se.jptv.iptv

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Hämtar XMLTV-guiden, filtrerar fram spellistans kanaler och sparar resultatet kompakt. */
class EpgRepository(private val context: Context, private val http: OkHttpClient) {

    private val cacheFile: File get() = File(context.filesDir, "epg.tsv")

    suspend fun download(url: String, playlist: Playlist): Epg = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val wantedIds = playlist.channels.mapNotNull { it.tvgId?.lowercase() }.toHashSet()
        val wantedNames = HashSet<String>()
        for (ch in playlist.channels) {
            wantedNames += Epg.normalize(ch.name)
            ch.tvgName?.let { wantedNames += Epg.normalize(it) }
        }
        wantedNames.remove("")

        val req = Request.Builder().url(url).header("User-Agent", IptvRepository.USER_AGENT).get().build()
        val epg = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Guiden svarade HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Tom guide")
            maybeGunzip(body.byteStream()).use { stream ->
                XmltvParser.parse(
                    input = stream,
                    parser = Xml.newPullParser(),
                    wantedIds = wantedIds,
                    wantedNames = wantedNames,
                    from = now - 2 * HOUR,
                    to = now + 24 * HOUR,
                    now = now,
                )
            }
        }
        if (epg.channelCount == 0) {
            throw IOException("Guiden innehöll inga kanaler som finns i spellistan")
        }
        save(epg)
        epg
    }

    suspend fun cached(): Epg? = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext null
        try {
            load()
        } catch (e: Exception) {
            null
        }
    }

    fun clearCache() {
        cacheFile.delete()
    }

    /** Guider levereras ofta som .xml.gz. Känn igen gzip på de två första byten. */
    private fun maybeGunzip(raw: InputStream): InputStream {
        val buffered = BufferedInputStream(raw, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun save(epg: Epg) {
        val (programmes, names) = epg.export()
        val tmp = File(context.filesDir, "epg.tsv.tmp")
        tmp.bufferedWriter().use { w ->
            w.write("T\t${epg.loadedAt}\n")
            for ((name, id) in names) w.write("N\t${clean(name)}\t${clean(id)}\n")
            for ((id, list) in programmes) {
                for (p in list) {
                    w.write("P\t${clean(id)}\t${p.start}\t${p.stop}\t${clean(p.title)}\t${clean(p.desc ?: "")}\n")
                }
            }
        }
        tmp.renameTo(cacheFile)
    }

    private fun load(): Epg {
        var loadedAt = 0L
        val names = HashMap<String, String>()
        val programmes = HashMap<String, MutableList<Programme>>()
        cacheFile.bufferedReader().useLines { lines ->
            for (line in lines) {
                val f = line.split('\t')
                when (f.firstOrNull()) {
                    "T" -> loadedAt = f.getOrNull(1)?.toLongOrNull() ?: 0L
                    "N" -> if (f.size >= 3) names[f[1]] = f[2]
                    "P" -> if (f.size >= 5) {
                        val start = f[2].toLongOrNull() ?: continue
                        val stop = f[3].toLongOrNull() ?: continue
                        programmes.getOrPut(f[1]) { ArrayList() } +=
                            Programme(start, stop, f[4], f.getOrNull(5)?.takeIf { it.isNotBlank() })
                    }
                }
            }
        }
        return Epg(programmes, names, loadedAt)
    }

    companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
