package se.jptv.plex

/** Tolkning av Plex-adresser, utan Android (testbar). */
object PlexAddress {
    /**
     * true för adresser på hemmanätet: "http://192.168.1.10:32400" eller
     * "https://192-168-1-10.abc123.plex.direct:32400". Publika adresser och relä ger false.
     */
    fun isLocal(uri: String): Boolean {
        val hostPort = uri.substringAfter("://").substringBefore('/')
        val host = hostPort.substringBefore(':')
        val ip = if (host.endsWith(".plex.direct")) host.substringBefore('.').replace('-', '.') else host
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        return n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31) || n[0] == 127
    }

    /**
     * Vanlig http på samma IP som en lokal plex.direct-adress:
     * "https://192-168-1-10.abc123.plex.direct:32400" -> "http://192.168.1.10:32400".
     * Plex.direct-namn slås upp via Plex DNS på internet, så utan internet är det här enda vägen till servern.
     * null för adresser som inte är lokala plex.direct (vanlig http har inget alternativ, publika ska inte gissas).
     */
    fun localHttp(uri: String): String? {
        val hostPort = uri.substringAfter("://").substringBefore('/')
        val host = hostPort.substringBefore(':')
        if (!host.endsWith(".plex.direct") || !isLocal(uri)) return null
        val ip = host.substringBefore('.').replace('-', '.')
        val port = hostPort.substringAfter(':', "").toIntOrNull() ?: 32400
        return "http://$ip:$port"
    }
}
