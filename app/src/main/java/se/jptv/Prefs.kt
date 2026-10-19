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

    /** Egen guide-adress. Tom = gissas från spellistan. */
    var epgUrl: String?
        get() = sp.getString("epg_url", null)
        set(value) { sp.edit().putString("epg_url", value).apply() }

    var playlistLoadedAt: Long
        get() = sp.getLong("playlist_loaded_at", 0L)
        set(value) { sp.edit().putLong("playlist_loaded_at", value).apply() }

    var favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        set(value) { sp.edit().putStringSet("favorites", value).apply() }

    /** Senast tittade kanaler, nyast först. */
    var recents: List<String>
        get() = sp.getString("recents", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) { sp.edit().putString("recents", value.joinToString("\n")).apply() }

    var hiddenGroups: Set<String>
        get() = sp.getStringSet("hidden_groups", emptySet())?.toSet() ?: emptySet()
        set(value) { sp.edit().putStringSet("hidden_groups", value).apply() }

    var startInLastChannel: Boolean
        get() = sp.getBoolean("start_in_last_channel", false)
        set(value) { sp.edit().putBoolean("start_in_last_channel", value).apply() }

    /** Byt TV:ns bildfrekvens efter filmen/kanalen. */
    var autoFrameRate: Boolean
        get() = sp.getBoolean("auto_frame_rate", true)
        set(value) { sp.edit().putBoolean("auto_frame_rate", value).apply() }

    /** Valt ljud-/undertextspråk per serie: "ljud|undertext", där undertext kan vara "off". */
    fun trackPreference(showKey: String): Pair<String?, String?>? {
        val raw = sp.getString("track_$showKey", null) ?: return null
        val audio = raw.substringBefore('|').ifEmpty { null }
        val sub = raw.substringAfter('|', "").ifEmpty { null }
        return audio to sub
    }

    fun setTrackPreference(showKey: String, audio: String?, subtitle: String?) {
        val old = trackPreference(showKey)
        val a = audio ?: old?.first ?: ""
        val s = subtitle ?: old?.second ?: ""
        sp.edit().putString("track_$showKey", "$a|$s").apply()
    }
}
