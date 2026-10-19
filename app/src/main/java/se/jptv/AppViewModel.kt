package se.jptv

import android.app.Application
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
import se.jptv.iptv.IptvRepository
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
    data object LiveTv : Screen
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
    private val updater = Updater(app, http)

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
            playlist = runCatching { iptv.cached() }.getOrNull()
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

    fun select(item: PlexItem) {
        when {
            item.isPlayable -> open(Screen.Player(PlayRequest.Vod(item)))
            item.isContainer -> open(Screen.Episodes(item))
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
    fun reportProgress(item: PlexItem, state: String, positionMs: Long, durationMs: Long) {
        viewModelScope.launch { plex.reportTimeline(item, state, positionMs, durationMs) }
    }

    fun reportStopped(item: PlexItem, positionMs: Long, durationMs: Long, transcodeSession: String?, ended: Boolean) {
        viewModelScope.launch {
            plex.reportTimeline(item, "stopped", positionMs, durationMs)
            if (ended) plex.markWatched(item)
            if (transcodeSession != null) plex.stopTranscode(transcodeSession)
            // Uppdatera "Fortsätt titta" i bakgrunden.
            runCatching { loadHome() }
        }
    }

    // ---------- IPTV ----------

    fun loadPlaylist(url: String) {
        val u = url.trim()
        if (u.isEmpty()) {
            iptvStatus = "Skriv in adressen till din M3U-lista först."
            return
        }
        prefs.m3uUrl = u
        viewModelScope.launch {
            iptvLoading = true
            iptvStatus = "Laddar spellistan…"
            try {
                val p = iptv.download(u)
                playlist = p
                iptvStatus = "${p.channels.size} kanaler i ${p.groups.size} grupper"
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
        iptv.clearCache()
        playlist = null
        iptvStatus = "Spellistan är borttagen."
    }

    fun playChannel(channels: List<Channel>, index: Int) {
        open(Screen.Player(PlayRequest.Live(channels, index)))
    }
}
