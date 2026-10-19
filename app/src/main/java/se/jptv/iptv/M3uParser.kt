package se.jptv.iptv

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val userAgent: String?,
    val referrer: String?,
    /** Kanalnummer = plats i hela listan (1, 2, 3 …). Används för sifferknapparna på fjärren. */
    val number: Int = 0,
    val tvgName: String? = null,
) {
    /** Stabil nyckel för favoriter och senaste kanaler. */
    val key: String get() = url
}

data class Playlist(val channels: List<Channel>, val epgUrl: String?) {
    /** Grupperna i den ordning de först förekommer i listan. */
    val groups: List<String> by lazy { channels.map { it.group }.distinct() }

    val byKey: Map<String, Channel> by lazy { channels.associateBy { it.key } }

    fun byNumber(number: Int): Channel? = channels.getOrNull(number - 1)
}

/**
 * Läser M3U/M3U8-spellistor som IPTV-leverantörer ger ut, inklusive Xtream-listor
 * (get.php?...&type=m3u_plus). Film och serier (VOD) hoppas över som standard,
 * eftersom de hör hemma i Plex-delen, inte i live-TV-listan.
 */
object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val vodExtensions = setOf("mkv", "mp4", "avi", "mov", "wmv", "m4v")

    fun parse(text: String, liveOnly: Boolean = true): Playlist = parse(text.lineSequence(), liveOnly)

    /** Läser rad för rad, så att även listor på 100 MB går att läsa utan att hela filen ligger i minnet. */
    fun parse(lines: Sequence<String>, liveOnly: Boolean = true): Playlist {
        val channels = ArrayList<Channel>()
        var epgUrl: String? = null
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var extGroup: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        lines.forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val a = attributes(line)
                    epgUrl = a["url-tvg"] ?: a["x-tvg-url"]
                }
                line.startsWith("#EXTINF") -> {
                    attrs = attributes(line)
                    name = displayName(line)
                    extGroup = null
                    userAgent = null
                    referrer = null
                }
                line.startsWith("#EXTGRP:") -> {
                    extGroup = line.substringAfter(':').trim()
                }
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.substringAfter(':')
                    if (opt.startsWith("http-user-agent=")) {
                        userAgent = opt.substringAfter('=').trim()
                    } else if (opt.startsWith("http-referrer=")) {
                        referrer = opt.substringAfter('=').trim()
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    // Kodi-format: "url|User-Agent=...&Referer=..." -> separata fält.
                    val url = line.substringBefore('|')
                    if (line.contains('|')) {
                        for (part in line.substringAfter('|').split('&')) {
                            val k = part.substringBefore('=').trim().lowercase()
                            val v = part.substringAfter('=', "").trim()
                            if (v.isEmpty()) continue
                            if (k == "user-agent") userAgent = userAgent ?: v
                            if (k == "referer" || k == "referrer") referrer = referrer ?: v
                        }
                    }
                    if (!liveOnly || isLive(url)) {
                        val n = name?.takeIf { it.isNotBlank() } ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: url
                        channels += Channel(
                            name = n,
                            url = url,
                            group = (attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup)?.trim()?.takeIf { it.isNotBlank() } ?: "Övrigt",
                            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                            userAgent = userAgent ?: attrs["user-agent"]?.takeIf { it.isNotBlank() },
                            referrer = referrer,
                            number = channels.size + 1,
                            tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
                        )
                    }
                    name = null
                    attrs = emptyMap()
                    extGroup = null
                    userAgent = null
                    referrer = null
                }
            }
        }
        return Playlist(channels, epgUrl)
    }

    fun isLive(url: String): Boolean {
        val u = url.lowercase()
        if ("/movie/" in u || "/series/" in u) return false
        val ext = u.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        return ext !in vodExtensions
    }

    /** Kommat som skiljer attributen från kanalnamnet, utan att räkna komman inom citattecken. */
    private fun nameCommaIndex(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return i
        }
        return -1
    }

    private fun attributes(line: String): Map<String, String> {
        val idx = nameCommaIndex(line)
        val head = if (idx >= 0) line.substring(0, idx) else line
        return attrRegex.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
    }

    private fun displayName(line: String): String {
        val idx = nameCommaIndex(line)
        return if (idx >= 0) line.substring(idx + 1).trim() else ""
    }
}
