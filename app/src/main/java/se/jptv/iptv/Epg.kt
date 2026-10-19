package se.jptv.iptv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
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
    /** Längsta beskrivning som sparas. Håller minnet nere med tusentals kanaler. */
    const val MAX_DESC = 150

    /** XMLTV-tid "20261019180000 +0200" -> millisekunder sedan 1970 (UTC). Ren aritmetik, utan Calendar. */
    fun parseTime(value: String): Long? {
        val s = value.trim()
        if (s.length < 12) return null
        for (i in 0 until 12) if (!s[i].isDigit()) return null
        fun num(from: Int, to: Int): Int {
            var n = 0
            for (i in from until to) n = n * 10 + (s[i] - '0')
            return n
        }
        val month = num(4, 6)
        val day = num(6, 8)
        if (month !in 1..12 || day !in 1..31) return null
        val seconds = if (s.length >= 14 && s[12].isDigit() && s[13].isDigit()) num(12, 14) else 0
        var t = (daysFromCivil(num(0, 4), month, day) * 86_400L +
            num(8, 10) * 3_600L + num(10, 12) * 60L + seconds) * 1_000L
        val signAt = s.indexOfAny(charArrayOf('+', '-'), 12)
        if (signAt >= 0 && s.length >= signAt + 5 && (signAt + 1 until signAt + 5).all { s[it].isDigit() }) {
            val sign = if (s[signAt] == '-') -1 else 1
            val minutes = num(signAt + 1, signAt + 3) * 60 + num(signAt + 3, signAt + 5)
            t -= sign * minutes * 60_000L
        }
        return t
    }

    /** Antal dagar sedan 1970-01-01 för ett datum (Howard Hinnants algoritm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
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
        // Tolerant läge: ett enda "&nbsp;" eller lösryckt "&" i guiden ska inte stoppa allt.
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(input, null)

        val wantedEpgIds = HashSet<String>()
        val nameIndex = HashMap<String, String>()
        val programmes = HashMap<String, MutableList<Programme>>()
        // Samma titel ("Nyheter", "Rapport") återkommer tusentals gånger; spara en kopia.
        val titles = HashMap<String, String>()
        var channelId: String? = null

        try {
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
                            // Kolla kanalen först: tiderna tolkas bara för program vi ska ha.
                            if (id == null || (id !in wantedEpgIds && id !in wantedIds)) {
                                skip(parser)
                            } else {
                                val start = parser.getAttributeValue(null, "start")?.let { parseTime(it) }
                                val stop = parser.getAttributeValue(null, "stop")?.let { parseTime(it) }
                                if (start != null && stop != null && stop > from && start < to) {
                                    val (title, desc) = readProgrammeBody(parser)
                                    val t = (title ?: "Okänt program").let { titles.getOrPut(it) { it } }
                                    programmes.getOrPut(id) { ArrayList() } += Programme(start, stop, t, desc)
                                } else {
                                    skip(parser)
                                }
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "channel") {
                    channelId = null
                }
                event = parser.next()
            }
        } catch (e: org.xmlpull.v1.XmlPullParserException) {
            // Trasig XML längre ner i filen: behåll det som hann läsas.
            if (programmes.isEmpty()) throw e
        } catch (e: java.io.EOFException) {
            if (programmes.isEmpty()) throw e
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
                            desc = parser.nextText().trim().take(MAX_DESC)
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
