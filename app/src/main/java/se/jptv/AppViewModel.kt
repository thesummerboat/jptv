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
    data object Search : Screen
    data object Settings : Screen
    data class Player(val request: PlayRequest) : Screen
}

sealed interface PlayRequest {
    /** autoplayCount: hur många avsnitt i rad som startat av sig själva (för "Tittar du fortfarande?"). */
    data class Vod(val item: PlexItem, val fromStart: Boolean = false, val autoplayCount: Int = 0) : PlayRequest
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
    val imdb = se.jptv.imdb.ImdbRatings(app, http)

    // ---------- Minne för listor, så att fokus och scroll är kvar när man går tillbaka ----------

    /**
     * Scrollposition (index, förskjutning) per lista. Bara siffror – hela listtillståndet skulle
     * hålla kvar gamla skärmars layout och bilder i minnet.
     */
    val scrollPositions = HashMap<String, Pair<Int, Int>>()

    /** Senast fokuserade element per skärm, t.ex. "home" -> "1_12345". */
    val focusKeys = HashMap<String, String>()


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

    /** true medan sparad spellista läses in vid start (visa "Laddar…", inte "ingen spellista"). */
    var iptvRestoring by mutableStateOf(prefs.m3uUrl != null)
        private set

    var epg by mutableStateOf<Epg?>(null)
        private set
    var epgStatus by mutableStateOf<String?>(null)
        private set
    var epgLoading by mutableStateOf(false)
        private set

    var favorites by mutableStateOf(prefs.favorites)
        private set
    var hiddenGroups by mutableStateOf(prefs.hiddenGroups)
        private set
    var autoFrameRate by mutableStateOf(prefs.autoFrameRate)
        private set

    /** Ökas när IMDb-betygen har laddats, så att skärmarna ritar om med betyg. */
    var imdbVersion by mutableStateOf(0)
        private set
    var imdbStatus by mutableStateOf<String?>(null)
        private set
    private var imdbLoading = false

    var startInLastChannel by mutableStateOf(prefs.startInLastChannel)
        private set

    /** Insomningstimer: tidpunkt då uppspelningen ska stängas av, eller null. */
    var sleepAt by mutableStateOf<Long?>(null)
    /** Insomningstimer "när det här är slut": inget nästa avsnitt, spelaren stängs. */
    var sleepAtEnd by mutableStateOf(false)
    /** Bildformat i spelaren: 0 = anpassa, 1 = fyll (sträck), 2 = zooma. Gäller tills appen stängs. */
    var videoScale by mutableStateOf(0)
    /** Visa teknisk information (upplösning, codec, buffert) ovanpå bilden. */
    var showStreamInfo by mutableStateOf(false)

    fun setSleepTimer(minutes: Int?) {
        sleepAtEnd = false
        sleepAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }
    }

    fun setSleepAtEnd() {
        sleepAt = null
        sleepAtEnd = true
    }

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
        se.jptv.player.DisplayModes.enabled = prefs.autoFrameRate
    }
    private var homeLoadedAt = 0L
    private var epgRefreshAgain = false

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
            epg = try {
                epgRepo.cached()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            iptvRestoring = false
            runCatching { imdb.load() }
            imdbVersion++
            if (startInLastChannel && current == Screen.Home && lastChannel != null) playLastChannel()
            refreshIfStale()
        }
        viewModelScope.launch {
            delay(3_000)
            checkForUpdate(manual = false)
        }
    }

    /**
     * Körs vid start och varje gång appen kommer tillbaka till skärmen (TV:n håller appen vid liv i
     * dagar): spellistan en gång per dygn, guiden var tolfte timme, hemskärmen efter en halvtimme.
     */
    fun refreshIfStale() {
        val now = System.currentTimeMillis()
        val url = prefs.m3uUrl
        if (url != null && !iptvRestoring && !iptvLoading) {
            if (now - prefs.playlistLoadedAt > 24 * HOUR) loadPlaylist(url, quiet = true) else refreshEpg(force = false)
        }
        if (plex.server != null && !homeLoading && now - homeLoadedAt > 30 * 60_000L) {
            viewModelScope.launch { runCatching { loadHome() } }
        }
        checkForUpdate(manual = false)
        refreshImdb(force = false)
    }

    // ---------- IMDb-betyg ----------

    /** Hämtar IMDb:s betygsfil om den saknas eller är äldre än en vecka. */
    fun refreshImdb(force: Boolean) {
        if (imdbLoading) return
        val age = System.currentTimeMillis() - imdb.updatedAt
        if (!force && imdb.count > 0 && age < 7 * 24 * HOUR) return
        imdbLoading = true
        viewModelScope.launch {
            // Vid start: låt Plex och kanalerna laddas först.
            if (!force) delay(20_000)
            imdbStatus = "Hämtar IMDb-betyg…"
            try {
                val n = imdb.refresh()
                imdbVersion++
                imdbStatus = "IMDb-betyg för ${"%,d".format(n).replace(',', ' ')} titlar"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                imdbStatus = "IMDb-betygen kunde inte hämtas: ${e.message}"
            } catch (e: OutOfMemoryError) {
                imdbStatus = "IMDb-betygen fick inte plats i TV:ns minne."
            } finally {
                imdbLoading = false
            }
        }
    }

    /** "IMDb 8,6" för en film, serie eller ett avsnitt, eller null om betyg saknas. */
    fun imdbText(item: PlexItem): String? {
        @Suppress("UNUSED_VARIABLE")
        val version = imdbVersion // läses så att skärmen ritas om när betygen kommit
        item.imdbId?.let { id ->
            imdb.lookup(id)?.let { (rating, _) -> return "IMDb ${String.format(java.util.Locale("sv", "SE"), "%.1f", rating)}" }
        }
        return item.ratings.firstOrNull { it.source == "IMDb" }?.text
    }

    /** Raden under affischen: år/avsnitt plus IMDb-betyg. */
    fun posterSubtitle(item: PlexItem): String? =
        listOfNotNull(item.subtitle, imdbText(item)).joinToString(" · ").ifEmpty { null }

    /** Alla betyg för detaljsidan: IMDb först, sedan Rotten Tomatoes och TMDB från Plex. */
    fun ratingBadges(item: PlexItem): List<String> =
        listOfNotNull(imdbText(item)) + item.ratings.filter { it.source != "IMDb" }.map { it.text }

    // ---------- Logotyper ----------

    private val logoCache = HashMap<String, String?>()

    /** Seriens logotyp för ett avsnitt eller en säsong (hämtas en gång per serie). */
    suspend fun showLogo(item: PlexItem): String? {
        item.logo?.let { return it }
        val key = item.showKey ?: return null
        if (logoCache.containsKey(key)) return logoCache[key]
        val logo = try {
            plex.metadata(key)?.logo
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        logoCache[key] = logo
        return logo
    }

    fun changeAutoFrameRate(enabled: Boolean) {
        autoFrameRate = enabled
        prefs.autoFrameRate = enabled
        se.jptv.player.DisplayModes.enabled = enabled
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
        homeLoadedAt = System.currentTimeMillis()
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

    /** Sortering och filter per bibliotek, så att valet finns kvar när man kommer tillbaka. */
    val sectionSort = HashMap<String, se.jptv.plex.LibrarySort>()
    val sectionUnwatched = HashMap<String, Boolean>()

    suspend fun sectionItems(
        sectionKey: String,
        sort: se.jptv.plex.LibrarySort = se.jptv.plex.LibrarySort.TITLE,
        unwatchedOnly: Boolean = false,
    ): List<PlexItem> {
        val cacheKey = "$sectionKey|${sort.name}|$unwatchedOnly"
        return sectionCache[cacheKey] ?: plex.all(sectionKey, sort, unwatchedOnly).also { sectionCache[cacheKey] = it }
    }

    // ---------- Skanna bibliotek ----------

    var scanning by mutableStateOf(false)
        private set
    var scanStatus by mutableStateOf<String?>(null)
        private set
    /** Ökas när en skanning är klar, så att öppna bibliotek laddar om sig. */
    var libraryVersion by mutableStateOf(0)
        private set

    /** Ber Plex leta efter nya filer (ett bibliotek, eller alla när section är null) och laddar om när den är klar. */
    fun scanLibrary(section: PlexSection?) {
        if (scanning || plex.server == null) return
        scanning = true
        scanStatus = "Plex letar efter nya filer${section?.let { " i ${it.title}" } ?: ""}…"
        viewModelScope.launch {
            try {
                val targets = if (section != null) listOf(section.key) else plex.sections().map { it.key }
                for (key in targets) plex.scanLibrary(key)
                // Vänta tills servern är klar (högst fem minuter), sedan hämtas allt på nytt.
                delay(3_000)
                val deadline = System.currentTimeMillis() + 5 * 60_000L
                while (System.currentTimeMillis() < deadline) {
                    val busy = runCatching { plex.refreshingSections() }.getOrDefault(emptySet())
                    if (targets.none { it in busy }) break
                    delay(2_000)
                }
                sectionCache.clear()
                runCatching { loadHome() }
                libraryVersion++
                scanStatus = "Klart – biblioteket är uppdaterat."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanStatus = "Kunde inte skanna: ${e.message}"
            } finally {
                scanning = false
            }
            delay(10_000)
            if (!scanning) scanStatus = null
        }
    }

    fun removeFromContinueWatching(item: PlexItem) {
        viewModelScope.launch {
            runCatching { plex.removeFromContinueWatching(item) }
            runCatching { loadHome() }
        }
    }

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
                // Den sparade listan finns kvar; guiden ska ändå hållas färsk.
                if (quiet) refreshEpg(force = false)
            } catch (e: OutOfMemoryError) {
                iptvStatus = "Spellistan är för stor för TV:ns minne."
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
        if (epgLoading) {
            // Ny adress medan en hämtning pågår: kör igen när den är klar (två samtidigt tar för mycket minne).
            if (force) epgRefreshAgain = true
            return
        }
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
            } catch (e: OutOfMemoryError) {
                epgStatus = "Guiden är för stor för TV:ns minne. Prova en guide med färre kanaler."
            } finally {
                epgLoading = false
                if (epgRefreshAgain) {
                    epgRefreshAgain = false
                    refreshEpg(force = true)
                }
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
            // Leverantörsgrupper med samma namn som våra egna skulle krocka i listan.
            addAll(pl.groups.filter { it !in hiddenGroups && it != GROUP_FAVORITES && it != GROUP_RECENTS && it != GROUP_SEARCH })
        }
    }

    fun hideGroup(group: String) {
        if (group == GROUP_FAVORITES || group == GROUP_RECENTS || group == GROUP_SEARCH) return
        val updated = hiddenGroups + group
        hiddenGroups = updated
        prefs.hiddenGroups = updated
    }

    fun showAllGroups() {
        hiddenGroups = emptySet()
        prefs.hiddenGroups = emptySet()
    }

    fun changeStartInLastChannel(enabled: Boolean) {
        startInLastChannel = enabled
        prefs.startInLastChannel = enabled
    }

    /** Kanaler för "Live nu" på hemskärmen: favoriter, annars senast tittade. */
    fun liveNowChannels(): List<Channel> {
        val pl = playlist ?: return emptyList()
        val favs = pl.channels.filter { it.key in favorites }
        return (favs.ifEmpty { recents.mapNotNull { pl.byKey[it] } }).distinctBy { it.key }.take(20)
    }

    // ---------- Sök över allt ----------

    data class SearchResults(
        val plex: List<se.jptv.plex.PlexSearchHub> = emptyList(),
        val channels: List<Channel> = emptyList(),
        val programmes: List<Pair<Channel, se.jptv.iptv.Programme>> = emptyList(),
        val error: String? = null,
    )

    suspend fun searchEverything(query: String): SearchResults {
        val q = query.trim()
        if (q.length < 2) return SearchResults()
        val lower = q.lowercase()
        val pl = playlist
        val channels = pl?.channels?.filter { it.name.lowercase().contains(lower) }?.take(40) ?: emptyList()
        val now = System.currentTimeMillis()
        val programmes = mutableListOf<Pair<Channel, se.jptv.iptv.Programme>>()
        val e = epg
        if (pl != null && e != null) {
            for (ch in pl.channels) {
                for (p in e.programmesFor(ch)) {
                    if (p.stop > now && p.title.lowercase().contains(lower)) programmes += ch to p
                }
                if (programmes.size > 200) break
            }
        }
        programmes.sortBy { it.second.start }
        var error: String? = null
        val hubs = if (plex.server != null) {
            try {
                plex.search(q)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                error = ex.message
                emptyList()
            }
        } else emptyList()
        return SearchResults(hubs, channels, programmes.take(60), error)
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

    /** Kanaler i TV-guiden. Från "Sök kanal" visas alla synliga kanaler. */
    fun channelsForGuide(group: String): List<Channel> {
        val pl = playlist ?: return emptyList()
        return if (group == GROUP_SEARCH) pl.channels.filter { it.group !in hiddenGroups } else channelsForGroup(group)
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
