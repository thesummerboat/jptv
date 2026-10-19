package se.jptv.iptv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String?) {
    fun progress(now: Long): Float =
        if (stop > start) ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f) else 0f
}

/**
 * TV-guiden: program per kanal. Kanaler i spellistan kopplas till guiden via tvg-id,
 * och om det saknas via kanalnamnet ("SVT1 HD" matchar "SVT1").
 */
class Epg(
    /** Guidens kanal-id (gemener) -> program sorterade på starttid. */
    private val programmes: Map<String, List<Programme>>,
    /** Normaliserat kanalnamn -> guidens kanal-id. */
    private val nameIndex: Map<String, String>,
    val loadedAt: Long,
) {
    private val resolved = ConcurrentHashMap<String, String>()

    val channelCount: Int get() = programmes.size

    private fun epgIdFor(channel: Channel): String? {
        resolved[channel.key]?.let { return it.ifEmpty { null } }
        val id = channel.tvgId?.lowercase()?.takeIf { programmes.containsKey(it) }
            ?: nameIndex[normalize(channel.name)]
            ?: channel.tvgName?.let { nameIndex[normalize(it)] }
        resolved[channel.key] = id ?: ""
        return id
    }

    fun programmesFor(channel: Channel): List<Programme> =
        epgIdFor(channel)?.let { programmes[it] } ?: emptyList()

    fun now(channel: Channel, at: Long = System.currentTimeMillis()): Programme? =
        programmesFor(channel).firstOrNull { it.start <= at && it.stop > at }

    fun next(channel: Channel, at: Long = System.currentTimeMillis()): Programme? =
        programmesFor(channel).firstOrNull { it.start > at }

    fun window(channel: Channel, from: Long, to: Long): List<Programme> =
        programmesFor(channel).filter { it.stop > from && it.start < to }

    /** Allt guidedata i ett platt format, för att spara i appens cache. */
    fun export(): Pair<Map<String, List<Programme>>, Map<String, String>> = programmes to nameIndex

    companion object {
        private val prefixRegex = Regex("""^(\|[a-z]{2,3}\||[a-z]{2,3}\s*[:|])\s*""")
        private val qualityRegex = Regex("""\b(fhd|uhd|hd|sd|4k|hevc|h265|h264|backup|raw|50fps|60fps)\b""")
        private val bracketRegex = Regex("""\(.*?\)|\[.*?]""")

        /** "SE: SVT1 HD" -> "svt1", så att kanalnamn från olika källor kan jämföras. */
        fun normalize(name: String): String {
            var n = name.lowercase().trim()
            n = n.replace(prefixRegex, "")
            n = n.replace(bracketRegex, " ")
            n = n.replace(qualityRegex, " ")
            return n.filter { it.isLetterOrDigit() }
        }
    }
}

object XmltvParser {
    private val timeRegex = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?""")

    /** XMLTV-tid "20261019180000 +0200" -> millisekunder sedan 1970 (UTC). */
    fun parseTime(value: String): Long? {
        val m = timeRegex.find(value.trim()) ?: return null
        val g = m.groupValues
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        var t = cal.timeInMillis
        val offset = g[7]
        if (offset.isNotEmpty()) {
            val sign = if (offset[0] == '-') -1 else 1
            val minutes = offset.substring(1, 3).toInt() * 60 + offset.substring(3, 5).toInt()
            t -= sign * minutes * 60_000L
        }
        return t
    }

    /**
     * Läser en XMLTV-fil strömmande och behåller bara kanaler som finns i spellistan och
     * program inom tidsfönstret. Det håller minnet nere även med guider på flera hundra MB.
     */
    fun parse(
        input: InputStream,
        parser: XmlPullParser,
        wantedIds: Set<String>,
        wantedNames: Set<String>,
        from: Long,
        to: Long,
        now: Long = System.currentTimeMillis(),
    ): Epg {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val wantedEpgIds = HashSet<String>()
        val nameIndex = HashMap<String, String>()
        val programmes = HashMap<String, MutableList<Programme>>()
        var channelId: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id")?.lowercase()
                        if (channelId != null && channelId in wantedIds) wantedEpgIds += channelId
                    }
                    "display-name" -> {
                        val id = channelId
                        val text = parser.nextText()
                        if (id != null) {
                            val norm = Epg.normalize(text)
                            if (norm.isNotEmpty() && norm in wantedNames) {
                                wantedEpgIds += id
                                nameIndex.putIfAbsent(norm, id)
                            }
                        }
                    }
                    "programme" -> {
                        val id = parser.getAttributeValue(null, "channel")?.lowercase()
                        val start = parser.getAttributeValue(null, "start")?.let { parseTime(it) }
                        val stop = parser.getAttributeValue(null, "stop")?.let { parseTime(it) }
                        val keep = id != null && id in wantedEpgIds && start != null && stop != null &&
                            stop > from && start < to
                        if (keep) {
                            val (title, desc) = readProgrammeBody(parser)
                            programmes.getOrPut(id!!) { ArrayList() } +=
                                Programme(start!!, stop!!, title ?: "Okänt program", desc)
                        } else {
                            skip(parser)
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "channel") {
                channelId = null
            }
            event = parser.next()
        }
        val sorted = programmes.mapValues { (_, list) -> list.sortedBy { it.start } }
        return Epg(sorted, nameIndex, now)
    }

    /** Läser titel och beskrivning ur ett <programme>-element. Parsern står på dess START_TAG. */
    private fun readProgrammeBody(parser: XmlPullParser): Pair<String?, String?> {
        var title: String? = null
        var desc: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "title" -> if (title == null) {
                            title = parser.nextText().trim()
                            depth--
                        }
                        "desc" -> if (desc == null) {
                            desc = parser.nextText().trim().take(400)
                            depth--
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return title to desc
            }
        }
        return title to desc
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

object IptvUrls {
    /**
     * Gissar guidens adress så att den inte behöver skrivas in på TV:n:
     * först url-tvg i spellistan, annars Xtream-mönstret get.php -> xmltv.php.
     */
    fun guessEpgUrl(m3uUrl: String?, playlist: Playlist?): String? {
        playlist?.epgUrl?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("http") }?.let { return it }
        val url = m3uUrl?.trim()?.toHttpUrlOrNull() ?: return null
        if (!url.encodedPath.endsWith("get.php")) return null
        val user = url.queryParameter("username") ?: return null
        val pass = url.queryParameter("password") ?: return null
        return url.newBuilder()
            .encodedPath("/xmltv.php")
            .query(null)
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .build()
            .toString()
    }
}
