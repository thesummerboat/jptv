package se.jptv

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import se.jptv.iptv.Channel
import se.jptv.iptv.Epg
import se.jptv.iptv.EpgRepository
import se.jptv.iptv.IptvRepository
import se.jptv.iptv.IptvUrls
import se.jptv.iptv.Playlist
import se.jptv.plex.PlexClient
import se.jptv.plex.PlexHomeRow
import se.jptv.plex.PlexItem
import se.jptv.plex.PlexSection
import se.jptv.plex.PlexServer
import se.jptv.update.AppRelease
import se.jptv.update.UpdateState
import se.jptv.update.Updater
import java.util.concurrent.TimeUnit

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data class Section(val section: PlexSection) : Screen
    data class Episodes(val container: PlexItem) : Screen
    data class Details(val item: PlexItem) : Screen
    data object LiveTv : Screen
    data class Guide(val group: String) : Screen
    data object Settings : Screen
    data class Player(val request: PlayRequest) : Screen
}

sealed interface PlayRequest {
    data class Vod(val item: PlexItem, val fromStart: Boolean = false) : PlayRequest
    data class Live(val channels: List<Channel>, val index: Int) : PlayRequest
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val plex = PlexClient(prefs.clientId, http)
    private val iptv = IptvRepository(app, http)
    private val epgRepo = EpgRepository(app, http)
    private val updater = Updater(app, http)

    // ---------- Minne för listor, så att fokus och scroll är kvar när man går tillbaka ----------

    private val listStates = HashMap<String, LazyListState>()
    private val gridStates = HashMap<String, LazyGridState>()

    /** Senast fokuserade element per skärm, t.ex. "home" -> "1_12345". */
    val focusKeys = HashMap<String, String>()

    fun listState(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }

    fun gridState(key: String): LazyGridState = gridStates.getOrPut(key) { LazyGridState() }

    // ---------- Navigering: en enkel stapel av skärmar, Tillbaka plockar bort den översta ----------

    val stack = mutableStateListOf<Screen>()
    val current: Screen get() = stack.lastOrNull() ?: Screen.Login

    fun open(screen: Screen) {
        stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Byter ut översta skärmen, t.ex. när nästa avsnitt tar över efter det förra. */
    fun replaceTop(screen: Screen) {
        if (stack.isEmpty()) stack.add(screen) else stack[stack.lastIndex] = screen
    }

    // ---------- Tillstånd som skärmarna visar ----------

    var loginCode by mutableStateOf<String?>(null)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    var serverName by mutableStateOf<String?>(null)
        private set
    var sections by mutableStateOf<List<PlexSection>>(emptyList())
        private set
    var homeRows by mutableStateOf<List<PlexHomeRow>>(emptyList())
        private set
    var homeLoading by mutableStateOf(false)
        private set
    var homeError by mutableStateOf<String?>(null)
        private set

    var playlist by mutableStateOf<Playlist?>(null)
        private set
    var iptvStatus by mutableStateOf<String?>(null)
        private set
    var iptvLoading by mutableStateOf(false)
        private set

    var epg by mutableStateOf<Epg?>(null)
        private set
    var epgStatus by mutableStateOf<String?>(null)
        private set
    var epgLoading by mutableStateOf(false)
        private set

    var favorites by mutableStateOf(prefs.favorites)
        private set
    var recents by mutableStateOf(prefs.recents)
        private set

    var availableUpdate by mutableStateOf<AppRelease?>(null)
        private set
    var updateBusy by mutableStateOf(false)
        private set
    /** Senaste meddelandet från uppdateraren, även från installationsmottagaren. */
    val updateMessage: String? get() = UpdateState.message
    private var lastUpdateCheck = 0L

    private var loginJob: Job? = null

    init {
        if (prefs.plexToken == null) {
            stack.add(Screen.Login)
            beginLogin()
        } else {
            stack.add(Screen.Home)
            connectAndLoad(rediscover = false)
        }
        viewModelScope.launch {
            val cachedPlaylist = runCatching { iptv.cached() }.getOrNull()
            playlist = cachedPlaylist
            epg = runCatching { epgRepo.cached() }.getOrNull()
            // Spellistan uppdateras i bakgrunden en gång per dygn, guiden var tolfte timme.
            val url = prefs.m3uUrl
            if (url != null && System.currentTimeMillis() - prefs.playlistLoadedAt > 24 * HOUR) {
                loadPlaylist(url, quiet = true)
            } else {
                refreshEpg(force = false)
            }
        }
        viewModelScope.launch {
            delay(3_000)
            checkForUpdate(manual = false)
        }
    }

    // ---------- Uppdateringar ----------

    /** Kollar GitHub efter ny version. Automatiskt högst var sjätte timme, manuellt alltid. */
    fun checkForUpdate(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual && now - lastUpdateCheck < 6 * 60 * 60 * 1000L) return
        lastUpdateCheck = now
        viewModelScope.launch {
            if (manual) UpdateState.message = "Söker efter uppdatering…"
            try {
                val release = updater.checkForUpdate()
                availableUpdate = release
                if (manual) {
                    UpdateState.message = if (release != null) {
                        "JPTV ${release.versionName} finns att installera."
                    } else {
                        "Du har senaste versionen (${BuildConfig.VERSION_NAME})."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) UpdateState.message = "Kunde inte söka efter uppdatering: ${e.message}"
            }
        }
    }

    fun installUpdate() {
        val release = availableUpdate ?: return
        if (updateBusy) return
        if (!updater.canInstall()) {
            val opened = updater.openInstallPermissionSettings()
            UpdateState.message = if (opened) {
                "Tillåt JPTV att installera appar, gå tillbaka och tryck Uppdatera igen."
            } else {
                "Tillåt JPTV under Inställningar → Enhetsinställningar → Säkerhet → Okända källor, och försök igen."
            }
            return
        }
        viewModelScope.launch {
            updateBusy = true
            try {
                var lastPercent = -1
                val apk = updater.download(release) { progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        UpdateState.message = "Laddar ner JPTV ${release.versionName}… $percent %"
                    }
                }
                UpdateState.message = "Startar installationen…"
                updater.install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.message = "Uppdateringen misslyckades: ${e.message}"
            } finally {
                updateBusy = false
            }
        }
    }

    // ---------- Plex ----------

    fun beginLogin() {
        loginJob?.cancel()
        loginError = null
        loginCode = null
        loginJob = viewModelScope.launch {
            try {
                val pin = plex.createPin()
                loginCode = pin.code
                var token: String? = null
                val deadline = System.currentTimeMillis() + 15 * 60_000L
                while (token == null && System.currentTimeMillis() < deadline) {
                    delay(2_000)
                    token = try {
                        plex.checkPin(pin)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                if (token == null) {
                    loginError = "Koden gick ut. Hämta en ny."
                    return@launch
                }
                prefs.plexToken = token
                loginCode = null
                stack.clear()
                stack.add(Screen.Home)
                connectAndLoad(rediscover = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loginError = e.message ?: "Inloggningen misslyckades"
            }
        }
    }

    /** Ansluter till sparad server (eller letar upp den igen) och laddar hemskärmen. */
    fun connectAndLoad(rediscover: Boolean) {
        val token = prefs.plexToken ?: return
        viewModelScope.launch {
            homeLoading = true
            homeError = null
            try {
                val savedUri = prefs.serverUri
                val savedToken = prefs.serverToken
                val server = if (!rediscover && savedUri != null && savedToken != null &&
                    plex.isReachable(savedUri, savedToken)
                ) {
                    PlexServer(prefs.serverName ?: "Plex", savedUri, savedToken)
                } else {
                    plex.findServer(token).also {
                        prefs.serverUri = it.uri
                        prefs.serverToken = it.token
                        prefs.serverName = it.name
                    }
                }
                plex.server = server
                serverName = server.name
                loadHome()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                homeError = e.message ?: "Kunde inte ansluta till Plex"
            } finally {
                homeLoading = false
            }
        }
    }

    fun refreshHome() {
        if (plex.server == null) {
            connectAndLoad(rediscover = true)
            return
        }
        viewModelScope.launch {
            homeLoading = true
            homeError = null
            try {
                loadHome()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                homeError = e.message ?: "Kunde inte hämta från Plex"
            } finally {
                homeLoading = false
            }
        }
    }

    private suspend fun loadHome() {
        val secs = plex.sections()
        sections = secs
        val rows = mutableListOf<PlexHomeRow>()
        val deck = runCatching { plex.onDeck() }.getOrDefault(emptyList())
        if (deck.isNotEmpty()) rows += PlexHomeRow("Fortsätt titta", deck)
        val recent = coroutineScope {
            secs.map { s ->
                async { s to runCatching { plex.recentlyAdded(s.key) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }
        for ((section, items) in recent) {
            if (items.isNotEmpty()) rows += PlexHomeRow("Nytt i ${section.title}", items)
        }
        homeRows = rows
    }

    /** Filmer och avsnitt öppnar detaljsidan, serier och säsonger avsnittslistan. */
    fun select(item: PlexItem) {
        when {
            item.isPlayable -> open(Screen.Details(item))
            item.isContainer -> open(Screen.Episodes(item))
        }
    }

    fun play(item: PlexItem, fromStart: Boolean = false) {
        open(Screen.Player(PlayRequest.Vod(item, fromStart)))
    }

    fun setWatched(item: PlexItem, watched: Boolean, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (watched) plex.markWatched(item) else plex.markUnwatched(item)
            sectionCache.clear()
            runCatching { loadHome() }
            onDone()
        }
    }

    fun logout() {
        loginJob?.cancel()
        prefs.plexToken = null
        prefs.serverUri = null
        prefs.serverToken = null
        prefs.serverName = null
        plex.server = null
        serverName = null
        sections = emptyList()
        homeRows = emptyList()
        stack.clear()
        stack.add(Screen.Login)
        beginLogin()
    }

    /** Skickas från spelaren; körs i ViewModel så att anropet hinner klart även när skärmen stängs. */
    fun reportProgress(item: PlexItem, state: String, positionMs: Long, durationMs: Long, transcodeSession: String? = null) {
        viewModelScope.launch { plex.reportTimeline(item, state, positionMs, durationMs, transcodeSession) }
    }

    fun reportStopped(item: PlexItem, positionMs: Long, durationMs: Long, transcodeSession: String?, ended: Boolean) {
        viewModelScope.launch {
            plex.reportTimeline(item, "stopped", positionMs, durationMs, transcodeSession)
            if (ended) plex.markWatched(item)
            if (transcodeSession != null) plex.stopTranscode(transcodeSession)
            // Uppdatera "Fortsätt titta" och biblioteken i bakgrunden.
            sectionCache.clear()
            runCatching { loadHome() }
        }
    }

    /** Spelaren stängdes innan något hann spelas: rör inte positionen i Plex, stäng bara omkodningen. */
    fun abandonPlayback(transcodeSession: String?) {
        if (transcodeSession == null) return
        viewModelScope.launch { plex.stopTranscode(transcodeSession) }
    }

    // ---------- Bibliotek: cache så att Tillbaka inte laddar om hela biblioteket ----------

    private val sectionCache = HashMap<String, List<PlexItem>>()

    suspend fun sectionItems(sectionKey: String): List<PlexItem> =
        sectionCache[sectionKey] ?: plex.all(sectionKey).also { sectionCache[sectionKey] = it }

    // ---------- IPTV: spellista ----------

    fun loadPlaylist(url: String, quiet: Boolean = false) {
        val u = url.trim()
        if (u.isEmpty()) {
            iptvStatus = "Skriv in adressen till din M3U-lista först."
            return
        }
        prefs.m3uUrl = u
        viewModelScope.launch {
            iptvLoading = true
            if (!quiet) iptvStatus = "Laddar spellistan…"
            try {
                val p = iptv.download(u)
                playlist = p
                prefs.playlistLoadedAt = System.currentTimeMillis()
                iptvStatus = "${p.channels.size} kanaler i ${p.groups.size} grupper"
                refreshEpg(force = !quiet)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                iptvStatus = "Fel: ${e.message ?: "kunde inte ladda listan"}"
            } finally {
                iptvLoading = false
            }
        }
    }

    fun removePlaylist() {
        prefs.m3uUrl = null
        prefs.playlistLoadedAt = 0L
        iptv.clearCache()
        epgRepo.clearCache()
        playlist = null
        epg = null
        iptvStatus = "Spellistan är borttagen."
        epgStatus = null
    }

    // ---------- IPTV: guide ----------

    /** Guidens adress: den du skrivit in, annars gissad från spellistan. */
    fun effectiveEpgUrl(): String? =
        prefs.epgUrl?.takeIf { it.isNotBlank() } ?: IptvUrls.guessEpgUrl(prefs.m3uUrl, playlist)

    fun setEpgUrl(url: String) {
        prefs.epgUrl = url.trim().ifEmpty { null }
        refreshEpg(force = true)
    }

    fun refreshEpg(force: Boolean) {
        val pl = playlist ?: return
        val url = effectiveEpgUrl()
        if (url == null) {
            if (force) epgStatus = "Ingen guide-adress hittades. Skriv in XMLTV-adressen."
            return
        }
        val age = System.currentTimeMillis() - (epg?.loadedAt ?: 0L)
        if (!force && epg != null && age < 12 * HOUR) return
        if (epgLoading) return
        viewModelScope.launch {
            epgLoading = true
            epgStatus = "Hämtar TV-guiden…"
            try {
                val e = epgRepo.download(url, pl)
                epg = e
                epgStatus = "Guide för ${e.channelCount} kanaler"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                epgStatus = "Guiden kunde inte hämtas: ${e.message}"
            } finally {
                epgLoading = false
            }
        }
    }

    // ---------- IPTV: favoriter, senaste och grupper ----------

    fun isFavorite(channel: Channel): Boolean = channel.key in favorites

    fun toggleFavorite(channel: Channel) {
        val updated = if (channel.key in favorites) favorites - channel.key else favorites + channel.key
        favorites = updated
        prefs.favorites = updated
    }

    fun onChannelTuned(channel: Channel) {
        prefs.lastChannelUrl = channel.url
        val updated = (listOf(channel.key) + recents.filter { it != channel.key }).take(15)
        recents = updated
        prefs.recents = updated
    }

    val lastChannel: Channel?
        get() = recents.firstOrNull()?.let { playlist?.byKey?.get(it) }

    /** Grupper att visa: Favoriter och Senaste först (om de finns), sedan leverantörens grupper. */
    fun groupsForUi(): List<String> {
        val pl = playlist ?: return emptyList()
        return buildList {
            if (favorites.any { pl.byKey.containsKey(it) }) add(GROUP_FAVORITES)
            if (recents.any { pl.byKey.containsKey(it) }) add(GROUP_RECENTS)
            add(GROUP_SEARCH)
            addAll(pl.groups)
        }
    }

    fun channelsForGroup(group: String, query: String = ""): List<Channel> {
        val pl = playlist ?: return emptyList()
        return when (group) {
            GROUP_FAVORITES -> pl.channels.filter { it.key in favorites }
            GROUP_RECENTS -> recents.mapNotNull { pl.byKey[it] }
            GROUP_SEARCH -> {
                val q = query.trim().lowercase()
                if (q.isEmpty()) emptyList() else pl.channels.filter { it.name.lowercase().contains(q) }.take(300)
            }
            else -> pl.channels.filter { it.group == group }
        }
    }

    fun playChannel(channels: List<Channel>, index: Int) {
        open(Screen.Player(PlayRequest.Live(channels, index)))
    }

    /** Spela senaste kanalen, i sin grupp så att ▲▼ fungerar som vanligt. */
    fun playLastChannel() {
        val ch = lastChannel ?: return
        val inGroup = channelsForGroup(ch.group)
        val index = inGroup.indexOfFirst { it.key == ch.key }.coerceAtLeast(0)
        playChannel(inGroup.ifEmpty { listOf(ch) }, index)
    }

    companion object {
        const val HOUR = 60 * 60 * 1000L
        const val GROUP_FAVORITES = "★ Favoriter"
        const val GROUP_RECENTS = "Senaste"
        const val GROUP_SEARCH = "Sök kanal"
    }
}
