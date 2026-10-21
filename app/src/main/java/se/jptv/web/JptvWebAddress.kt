package se.jptv.web

/** Var JPTV Web (på Unraid) bör finnas, utifrån Plex-serverns adress. Ren logik, testbar. */
object JptvWebAddress {
    const val PORT = 8099

    /**
     * "https://192-168-1-10.abc.plex.direct:32400" eller "http://192.168.1.10:32400" ->
     * "http://192.168.1.10:8099". JPTV Web körs oftast på samma Unraid som Plex.
     */
    fun fromPlex(plexUri: String?): String? {
        if (plexUri.isNullOrBlank()) return null
        val host = plexUri.substringAfter("://").substringBefore('/').substringBefore(':')
        val ip = if (host.endsWith(".plex.direct")) host.substringBefore('.').replace('-', '.') else host
        if (ip.isBlank()) return null
        return "http://$ip:$PORT"
    }

    /** Användarens egen adress: "192.168.1.10" eller "unraid:8099" blir en hel adress. */
    fun normalize(input: String?): String? {
        val s = input?.trim()?.trimEnd('/') ?: return null
        if (s.isEmpty()) return null
        val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
        val scheme = withScheme.substringBefore("://")
        val rest = withScheme.substringAfter("://")
        val host = rest.substringBefore('/')
        val path = rest.removePrefix(host)
        return if (':' in host) withScheme else "$scheme://$host:$PORT$path"
    }
}
