package se.jptv

import android.content.Context
import java.util.UUID

/** Små inställningar som ska överleva omstart: Plex-inloggning, vald server och M3U-adress. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jptv", Context.MODE_PRIVATE)

    /** Unikt ID för den här installationen. Plex använder det för att känna igen enheten. */
    val clientId: String
        get() = sp.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("client_id", it).apply()
        }

    var plexToken: String?
        get() = sp.getString("plex_token", null)
        set(value) { sp.edit().putString("plex_token", value).apply() }

    var serverUri: String?
        get() = sp.getString("server_uri", null)
        set(value) { sp.edit().putString("server_uri", value).apply() }

    var serverToken: String?
        get() = sp.getString("server_token", null)
        set(value) { sp.edit().putString("server_token", value).apply() }

    var serverName: String?
        get() = sp.getString("server_name", null)
        set(value) { sp.edit().putString("server_name", value).apply() }

    var m3uUrl: String?
        get() = sp.getString("m3u_url", null)
        set(value) { sp.edit().putString("m3u_url", value).apply() }

    var lastChannelUrl: String?
        get() = sp.getString("last_channel_url", null)
        set(value) { sp.edit().putString("last_channel_url", value).apply() }
}
