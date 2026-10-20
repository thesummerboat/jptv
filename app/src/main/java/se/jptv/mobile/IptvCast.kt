package se.jptv.mobile

/** Adress som en Chromecast kan spela för en IPTV-kanal (den behöver HLS, inte rå MPEG-TS). */
object IptvCast {
    fun hlsUrl(url: String): String {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        return when {
            path.endsWith(".m3u8", ignoreCase = true) -> url
            // Xtream: …/live/user/pass/123.ts finns oftast också som …/123.m3u8
            path.endsWith(".ts", ignoreCase = true) -> path.dropLast(3) + ".m3u8" + query
            // Xtream utan ändelse: …/user/pass/123
            Regex("/\\d+$").containsMatchIn(path) -> "$path.m3u8$query"
            else -> url
        }
    }
}
