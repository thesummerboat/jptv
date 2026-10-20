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
import kotlinx.coroutines.withContext
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
    data class Section(val section: PlexSection, val genre: se.jptv.plex.PlexFilterOption? = null) : Screen
    data class Collection(val collection: PlexItem) : Screen
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
    /** trailer = extramaterial: påverkar inte position, sett-status eller Fortsätt titta. */
    data class Vod(
        val item: PlexItem,
        val fromStart: Boolean = false,
        val autoplayCount: Int = 0,
        val trailer: Boolean = false,
        /** Slumpvis: nästa avsnitt väljs slumpvis i serien. */
        val shuffle: Boolean = false,
    ) : PlayRequest
    data class Live(val channels: List<Channel>, val index: Int) : PlayRequest
}

/** Påminnelse från guiden: ett program som ska börja på en kanal. */
data class Reminder(val channelKey: String, val start: Long, val title: String, val channelName: String) {
    fun encode(): String = listOf(channelKey, start.toString(), title, channelName)
        .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }

    companion object {
        fun decode(line: String): Reminder? {
            val p = line.split('\t')
            if (p.size < 4) return null
            return Reminder(p[0], p[1].toLongOrNull() ?: return null, p[2], p[3])
        }
    }
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

    /** true när senaste navigeringen var Tillbaka (t.ex. från spelaren), för att inte spela ledmotivet igen. */
    var cameBack = false
        private set

    fun open(screen: Screen) {
        // Plex-uppspelning tar över skärmen och ljudet: stäng bild-i-bild först.
        if (screen is Screen.Player && screen.request is PlayRequest.Vod) closePip()
        cameBack = false
        stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        cameBack = true
        // Ut ur spelaren: svart direkt, innan menyn ritas, medan TV:n byter tillbaka frekvens.
        if (stack.last() is Screen.Player) se.jptv.player.DisplayModes.beginBlackout()
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Telefonens flikar: börja om från en skärm. */
    fun resetTo(screen: Screen) {
        cameBack = false
        stack.clear()
        stack.add(screen)
    }

    // ---------- Telefonen: kvalitet hemifrån ----------

    var remoteQuality by mutableStateOf(prefs.remoteQuality)
        private set

    fun changeRemoteQuality(value: Int) {
        remoteQuality = value
        prefs.remoteQuality = value
    }

    /** Bithastighet (kbit/s) och upplösning för omkodning hemifrån; null = originalfilen. */
    fun remoteStream(): Pair<Int, String>? = REMOTE_QUALITIES.getOrNull(remoteQuality)?.second

    /** Telefonen bytte nät (t.ex. hemifrån till hotellet): leta upp servern igen om den inte svarar. */
    fun reconnectIfNeeded() {
        val s = plex.server ?: return
        viewModelScope.launch {
            if (!plex.isReachable(s.uri, s.token)) connectAndLoad(rediscover = true)
        }
    }

    /** Byter ut översta skärmen, t.ex. när nästa avsnitt tar över efter det förra. */
    fun replaceTop(screen: Screen) {
        if (screen is Screen.Player && screen.request is PlayRequest.Vod) closePip()
        cameBack = false
        if (stack.isEmpty()) stack.add(screen) else stack[stack.lastIndex] = screen
    }

    // ---------- Bild-i-bild: live-kanalen spelar vidare i ett hörn ----------

    /**
     * Kanalen som spelar i hörnet. Det är samma spelare och ström som i helskärm, så det blir aldrig
     * mer än en ström hos IPTV-leverantören.
     */
    var pip by mutableStateOf<se.jptv.player.LiveSession?>(null)
        private set

    /** Var skärmen har plats för bilden (i pixlar), annars ett hörn längst ner till höger. */
    var pipSlot by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)

    var pipEnabled by mutableStateOf(prefs.pip)
        private set

    fun startPip(session: se.jptv.player.LiveSession) {
        val old = pip
        if (old != null && old.exo !== session.exo) old.exo.release()
        pip = session
    }

    /** Helskärmsspelaren tar över spelaren och strömmen; inget släpps. */
    fun handOverPip(session: se.jptv.player.LiveSession) {
        if (pip === session) pip = null
    }

    fun closePip() {
        val session = pip ?: return
        pip = null
        session.exo.release()
    }

    /** Tillbaka till helskärm med kanalen som spelar. */
    fun resumePip() {
        val session = pip ?: return
        open(Screen.Player(PlayRequest.Live(session.channels, session.index)))
    }

    fun changePipEnabled(enabled: Boolean) {
        pipEnabled = enabled
        prefs.pip = enabled
        if (!enabled) closePip()
    }

    override fun onCleared() {
        closePip()
        super.onCleared()
    }

    // ---------- Trailer i bakgrunden och ledmotiv ----------

    var heroTrailers by mutableStateOf(prefs.heroTrailers)
        private set

    /** Vad trailern i bakgrunden gjorde senast, för Inställningar (felsökning). */
    var trailerStatus by mutableStateOf<String?>(null)

    fun changeHeroTrailers(enabled: Boolean) {
        heroTrailers = enabled
        prefs.heroTrailers = enabled
    }

    /** Ledmotivets volym: 0 = av, 1 = lågt, 2 = mellan. */
    var themeLevel by mutableStateOf(prefs.themeLevel)
        private set

    val themeMusic: Boolean get() = themeLevel > 0

    fun cycleThemeLevel() {
        val next = (themeLevel + 1) % 3
        themeLevel = next
        prefs.themeLevel = next
        se.jptv.ui.ThemePlayer.level = next
        if (next == 0) se.jptv.ui.ThemePlayer.stop(fade = true)
    }

    /** Trailer per film/serie (null = har ingen), så att samma titel inte frågas om igen. */
    private val trailerCache = HashMap<String, PlexItem?>()

    /** Trailern för en film eller serie; för avsnitt och säsonger seriens trailer. */
    suspend fun trailerFor(item: PlexItem): PlexItem? {
        val key = when (item.type) {
            "movie", "show" -> item.ratingKey
            "episode", "season" -> item.showKey ?: return null
            else -> return null
        }
        if (trailerCache.containsKey(key)) return trailerCache[key]
        val trailer = try {
            val target = if (key == item.ratingKey) item else plex.metadata(key) ?: return null
            plex.trailers(target).firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        trailerCache[key] = trailer
        return trailer
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
    /** Bibliotek som visas: favoriter först, dolda borttagna. */
    val visibleSections: List<PlexSection>
        get() = sections.filter { it.key !in hiddenSections }.sortedBy { if (it.key in favoriteSections) 0 else 1 }
    var hiddenSections by mutableStateOf(prefs.hiddenSections)
        private set
    var favoriteSections by mutableStateOf(prefs.favoriteSections)
        private set
    /** Genrer att bläddra i från hemskärmen: ett bibliotek med film och ett med serier. */
    var genreShortcuts by mutableStateOf<List<Pair<PlexSection, List<se.jptv.plex.PlexFilterOption>>>>(emptyList())
        private set
    /** Antal rader överst (Fortsätt titta + Nytt) innan genrerna visas på hemskärmen. */
    var homeLeadRows by mutableStateOf(0)
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
        se.jptv.ui.NavSounds.level = prefs.navSound
        se.jptv.ui.ThemePlayer.level = prefs.themeLevel
    }

    var navSound by mutableStateOf(prefs.navSound)
        private set

    var tunnelingMode by mutableStateOf(prefs.tunneling)
        private set

    /** Om spelaren ska använda tunnlad uppspelning just nu. */
    fun useTunneling(): Boolean = when (tunnelingMode) {
        1 -> true
        2 -> false
        else -> se.jptv.player.DisplayModes.singleMode()
    }

    fun cycleTunneling() {
        val next = (tunnelingMode + 1) % 3
        tunnelingMode = next
        prefs.tunneling = next
    }

    fun cycleNavSound() {
        val next = (navSound + 1) % 3
        navSound = next
        prefs.navSound = next
        se.jptv.ui.NavSounds.level = next
        se.jptv.ui.NavSounds.select()
    }
    private var homeLoadedAt = 0L
    private var epgRefreshAgain = false

    init {
        if (prefs.plexToken == null) {
            stack.add(Screen.Login)
            beginLogin()
        } else {
            stack.add(Screen.Home)
            // Visa sparad hemskärm direkt; färsk data hämtas strax efter.
            val savedUri = prefs.serverUri
            val savedToken = prefs.serverToken
            if (savedUri != null && savedToken != null) {
                plex.server = PlexServer(prefs.serverName ?: "Plex", savedUri, savedToken)
                serverName = prefs.serverName
                viewModelScope.launch { restoreHomeCache() }
            }
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
                runCatching { refreshLibraryIndex() }
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
        if (!enabled) se.jptv.player.DisplayModes.resetNow()
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

    private var leadRows: List<PlexHomeRow> = emptyList()
    private var hubRows: List<PlexHomeRow> = emptyList()
    private var imdbTopRows: List<PlexHomeRow> = emptyList()
    private val genreCache = HashMap<String, List<se.jptv.plex.PlexFilterOption>>()

    private fun visible(items: List<PlexItem>) = items.filter { it.sectionKey == null || it.sectionKey !in hiddenSections }

    /**
     * Hemskärmen: Fortsätt titta, Nytt per bibliotek, IMDb-topplistor och Plex egna rader
     * (högst betyg, nyligen släppta, återupptäck …). Favoritbibliotek först och med flest rader.
     */
    private suspend fun loadHome() {
        val secs = plex.sections()
        sections = secs
        val shown = visibleSections
        val lead = mutableListOf<PlexHomeRow>()
        val deck = visible(runCatching { plex.onDeck() }.getOrDefault(emptyList()))
        if (deck.isNotEmpty()) lead += PlexHomeRow("Fortsätt titta", deck)
        val hubSections = shown.filter { favoriteSections.isEmpty() || it.key in favoriteSections }
        val (recent, hubs) = coroutineScope {
            val r = shown.map { s -> async { s to runCatching { plex.recentlyAdded(s.key) }.getOrDefault(emptyList()) } }
            val h = hubSections.map { s -> async { runCatching { plex.sectionHubs(s.key) }.getOrDefault(emptyList()).take(4) } }
            r.awaitAll() to h.awaitAll()
        }
        for ((section, items) in recent) {
            if (items.isEmpty()) continue
            lead += if (section.type == "show") groupNewEpisodes("Nytt i ${section.title}", items)
            else PlexHomeRow("Nytt i ${section.title}", items)
        }
        leadRows = lead
        hubRows = hubs.flatten().map { it.copy(items = visible(it.items)) }.filter { it.items.size >= 3 }
        rebuildHome()
        homeLoadedAt = System.currentTimeMillis()
        // Genrerna visas nu som kategorier i varje bibliotek, inte på hemskärmen.
        genreShortcuts = emptyList()
        saveHomeCache()
        refreshLibraryIndex()
    }

    private val homeCacheFile get() = java.io.File(getApplication<Application>().filesDir, "home_cache.json")

    private suspend fun restoreHomeCache() {
        val snap = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { se.jptv.plex.HomeCache.read(homeCacheFile) }.getOrNull() } ?: return
        // Färsk data hann före: rör inget.
        if (homeRows.isNotEmpty()) return
        if (sections.isEmpty()) sections = snap.sections
        homeLeadRows = snap.leadRows.coerceAtMost(snap.rows.size)
        homeRows = snap.rows
        if (genreShortcuts.isEmpty()) genreShortcuts = snap.genres
    }

    private suspend fun saveHomeCache() {
        val snap = se.jptv.plex.HomeCache.Snapshot(sections, homeRows, homeLeadRows, genreShortcuts)
        withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { se.jptv.plex.HomeCache.write(homeCacheFile, snap) } }
    }

    /**
     * Nytt i serier: ett kort per serie ("3 nya avsnitt") i stället för ett per avsnitt.
     * Ett enda nytt avsnitt visas som det är (S2E5 · Titel) och öppnar avsnittet direkt.
     */
    private fun groupNewEpisodes(title: String, items: List<PlexItem>): PlexHomeRow {
        val groups = LinkedHashMap<String, MutableList<PlexItem>>()
        for (item in items) groups.getOrPut(item.showKey ?: item.ratingKey) { mutableListOf() } += item
        val out = mutableListOf<PlexItem>()
        val labels = HashMap<String, String>()
        for (group in groups.values) {
            val newest = group.first()
            if (group.size == 1 && newest.type != "season") {
                out += newest
                continue
            }
            val show = newest.showContainer()
            if (show == null) {
                out += group
                continue
            }
            labels[show.ratingKey] = when {
                group.any { it.type == "season" } && group.size == 1 -> "Nya avsnitt i säsong ${newest.episode ?: ""}".trim()
                group.any { it.type == "season" } -> "Nya avsnitt"
                else -> "${group.size} nya avsnitt"
            }
            out += show.copy(
                imdbId = null,
                ratings = emptyList(),
                logo = null,
                watched = false,
                summary = newest.summary?.let { s -> "Senaste: ${newest.subtitle ?: newest.title}. $s" },
            )
        }
        return PlexHomeRow(title, out, labels)
    }

    // ---------- Slumpvis ----------

    private val episodeCache = HashMap<String, List<PlexItem>>()
    private val shuffleSeen = HashMap<String, MutableSet<String>>()

    /** Ett slumpat avsnitt ur serien, helst ett som inte redan spelats i den här slumpomgången. */
    suspend fun randomEpisode(show: PlexItem, exclude: String?): PlexItem? {
        val key = show.ratingKey
        val all = episodeCache[key] ?: runCatching { plex.episodes(show.copy(type = "show")) }.getOrNull()
            ?.filter { it.type == "episode" }?.also { episodeCache[key] = it } ?: return null
        if (all.isEmpty()) return null
        val seen = shuffleSeen.getOrPut(key) { HashSet() }
        exclude?.let { seen += it }
        var pool = all.filter { it.ratingKey !in seen }
        if (pool.isEmpty()) {
            seen.clear()
            exclude?.let { seen += it }
            pool = all.filter { it.ratingKey != exclude }.ifEmpty { all }
        }
        return pool.random().also { seen += it.ratingKey }
    }

    fun playShuffle(show: PlexItem) {
        viewModelScope.launch {
            shuffleSeen.remove(show.ratingKey)
            val episode = randomEpisode(show, null) ?: return@launch
            open(Screen.Player(PlayRequest.Vod(episode, fromStart = true, shuffle = true)))
        }
    }

    private fun rebuildHome() {
        homeLeadRows = leadRows.size
        // Radnamn måste vara unika på hemskärmen.
        homeRows = (leadRows + recRows + imdbTopRows + hubRows).distinctBy { it.title }
    }

    suspend fun genres(sectionKey: String): List<se.jptv.plex.PlexFilterOption> =
        genreCache[sectionKey] ?: plex.genres(sectionKey).also { genreCache[sectionKey] = it }


    // ---------- Hela biblioteket: IMDb-topplistor och rekommendationer ----------

    private var recRows: List<PlexHomeRow> = emptyList()
    private var indexStamp: List<Any> = emptyList()
    private var indexBuiltAt = 0L
    var notInterested by mutableStateOf(prefs.notInterested)
        private set

    /** Nyckel för "Inte intresserad": serien för avsnitt, annars IMDb-id eller Plex-id. */
    private fun recKey(item: PlexItem): String =
        if ((item.type == "episode" || item.type == "season") && item.showKey != null) "rk:${item.showKey}"
        else item.imdbId ?: "rk:${item.ratingKey}"

    fun markNotInterested(item: PlexItem) {
        val updated = notInterested + recKey(item)
        notInterested = updated
        prefs.notInterested = updated
        indexStamp = emptyList()
        viewModelScope.launch { runCatching { refreshLibraryIndex() } }
    }

    fun clearNotInterested() {
        notInterested = emptySet()
        prefs.notInterested = emptySet()
        indexStamp = emptyList()
        viewModelScope.launch { runCatching { refreshLibraryIndex() } }
    }

    /**
     * Läser in alla filmer och serier (synliga bibliotek) och din tittarhistorik, och räknar fram
     * IMDb-topplistor och rekommendationer. Görs högst varannan timme eller när något ändrats.
     */
    private suspend fun refreshLibraryIndex() {
        val stamp = listOf(imdbVersion, libraryVersion, hiddenSections, favoriteSections, notInterested)
        val now = System.currentTimeMillis()
        if (stamp == indexStamp && now - indexBuiltAt < 2 * HOUR) return
        indexStamp = stamp
        indexBuiltAt = now
        val shown = visibleSections.filter { it.type == "movie" || it.type == "show" }
        val bySection = coroutineScope {
            shown.map { s -> async { s to (runCatching { plex.all(s.key) }.getOrNull() ?: emptyList()) } }.awaitAll()
        }
        val history = runCatching { plex.watchHistory() }.getOrDefault(emptyList())
        libraryItems = bySection.associate { (s, all) -> s.key to all }
        sectionRowsCache.clear()

        // IMDb-topplistor för favoritbiblioteken (eller alla om inga favoriter).
        val topRows = mutableListOf<PlexHomeRow>()
        for ((s, all) in bySection) {
            if (favoriteSections.isNotEmpty() && s.key !in favoriteSections) continue
            val top = imdbTop(s, all)
            if (top.size >= 5) topRows += PlexHomeRow("Topplista IMDb · ${s.title}", top)
        }
        imdbTopRows = topRows

        recRows = withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildRecommendations(bySection.flatMap { it.second }, history, now)
        }
        rebuildHome()
        saveHomeCache()
    }

    /** Bibliotekets bästa enligt IMDb (med tillräckligt många röster), högst 20. */
    private suspend fun imdbTop(s: PlexSection, all: List<PlexItem>): List<PlexItem> {
        val minVotes = if (s.type == "movie") 25_000 else 10_000
        return withContext(kotlinx.coroutines.Dispatchers.Default) {
            all.mapNotNull { item ->
                val fromFile = item.imdbId?.let { imdb.lookup(it) }
                when {
                    fromFile != null && fromFile.second >= minVotes -> item to fromFile.first
                    fromFile == null -> item.ratings.firstOrNull { it.source == "IMDb" }?.let { item to it.value.toFloat() }
                    else -> null
                }
            }.sortedByDescending { it.second }.take(20).map { it.first }
        }
    }

    // ---------- Biblioteken: rader att bläddra i (som hemskärmen, men för ett bibliotek) ----------

    /** Alla titlar per bibliotek från senaste genomgången (för rader per genre). */
    private var libraryItems: Map<String, List<PlexItem>> = emptyMap()
    private val sectionRowsCache = HashMap<String, Pair<Long, List<PlexHomeRow>>>()

    /** Senast byggda rader för biblioteket, så att sidan visas direkt när man kommer tillbaka. */
    fun cachedSectionRows(sectionKey: String): List<PlexHomeRow>? = sectionRowsCache[sectionKey]?.second

    /**
     * Rader för ett bibliotek: Fortsätt titta, Nytt, Rekommenderat för dig, Eftersom du såg …,
     * Topplista IMDb, Plex egna rader och de största genrerna (nyast först).
     */
    suspend fun sectionRows(section: PlexSection): List<PlexHomeRow> {
        val now = System.currentTimeMillis()
        sectionRowsCache[section.key]?.let { (at, rows) -> if (now - at < 30 * 60_000L) return rows }
        val key = section.key
        val all = libraryItems[key] ?: runCatching { plex.all(key) }.getOrDefault(emptyList())
        // Bara det som ligger i just det här biblioteket. Titlar från bibliotekslistan saknar ofta
        // bibliotekets nummer, så de känns igen på sitt id; annars på typen (film eller serie).
        val ids = all.mapTo(HashSet()) { it.ratingKey }
        val types = if (section.type == "show") setOf("show", "season", "episode") else setOf(section.type)
        fun inSection(item: PlexItem): Boolean = when {
            item.sectionKey != null -> item.sectionKey == key
            ids.isNotEmpty() -> item.ratingKey in ids || (item.type in setOf("season", "episode") && item.showKey in ids)
            else -> item.type in types
        }
        val rows = mutableListOf<PlexHomeRow>()
        homeRows.firstOrNull { it.title == "Fortsätt titta" }?.let { r ->
            val items = r.items.filter { it.sectionKey == key }
            if (items.isNotEmpty()) rows += r.copy(items = items)
        }
        val newRow = homeRows.firstOrNull { it.title == "Nytt i ${section.title}" } ?: run {
            val items = runCatching { plex.recentlyAdded(key) }.getOrDefault(emptyList())
            when {
                items.isEmpty() -> null
                section.type == "show" -> groupNewEpisodes("Nytt", items)
                else -> PlexHomeRow("Nytt", items)
            }
        }
        newRow?.let { rows += it.copy(title = "Nytt") }
        for (r in recRows) {
            val items = r.items.filter { inSection(it) }
            if (items.size < 4) continue
            val title = if (r.title.startsWith("Rekommenderat för dig")) "Rekommenderat för dig" else r.title
            rows += r.copy(title = title, items = items)
        }
        val top = imdbTop(section, all)
        if (top.size >= 5) rows += PlexHomeRow("Topplista IMDb", top)
        val hubs = runCatching { plex.sectionHubs(key) }.getOrDefault(emptyList())
            .map { it.copy(items = visible(it.items).filter { item -> inSection(item) }) }
            .filter { it.items.size >= 3 }
        rows += hubs
        val byGenre = withContext(kotlinx.coroutines.Dispatchers.Default) {
            all.flatMap { item -> item.genres.map { g -> g to item } }
                .groupBy({ it.first }, { it.second })
                .entries.sortedByDescending { it.value.size }
                .take(8)
                .map { (g, items) -> PlexHomeRow(g, items.sortedByDescending { it.addedAt }.take(25)) }
        }
        rows += byGenre.filter { it.items.size >= 4 }
        val result = rows.distinctBy { it.title }
        sectionRowsCache[key] = now to result
        return result
    }

    private fun buildRecommendations(
        library: List<PlexItem>,
        history: List<se.jptv.plex.PlexHistoryEntry>,
        now: Long,
    ): List<PlexHomeRow> {
        val day = 24 * HOUR
        // Samma film i flera bibliotek (t.ex. 1080p och 4K) räknas en gång.
        val items = library.distinctBy { it.imdbId ?: "${it.type}|${it.title}|${it.year}" }
        val plays = HashMap<String, Int>()
        val lastSeen = HashMap<String, Long>()
        val episodesSeen = HashMap<String, MutableSet<String>>()
        for (h in history) {
            val key = if (h.type == "episode") h.showKey ?: continue else h.ratingKey
            if (h.type == "episode") episodesSeen.getOrPut(key) { HashSet() } += h.ratingKey
            else plays[key] = (plays[key] ?: 0) + 1
            lastSeen[key] = maxOf(lastSeen[key] ?: 0L, h.viewedAt)
        }
        val recItems = items.map { item ->
            val excluded = recKey(item) in notInterested || "rk:${item.ratingKey}" in notInterested
            val last = maxOf(item.lastViewedAt, lastSeen[item.ratingKey] ?: 0L)
            val engagement: Float
            val unseen: Boolean
            if (item.type == "show") {
                val n = maxOf(episodesSeen[item.ratingKey]?.size ?: 0, item.viewedLeafCount ?: 0)
                unseen = n == 0
                engagement = when {
                    n >= 3 -> 1f + minOf(0.6f, n / 30f)
                    n >= 1 -> if (now - last > 30 * day) -0.3f else 0.3f
                    else -> 0f
                }
            } else {
                val p = maxOf(plays[item.ratingKey] ?: 0, item.viewCount)
                unseen = p == 0 && item.viewOffsetMs == 0L
                engagement = when {
                    p > 0 -> 1f + 0.2f * (p - 1).coerceIn(0, 3)
                    item.viewOffsetMs > 0 -> {
                        val fraction = if (item.durationMs > 0) item.viewOffsetMs.toFloat() / item.durationMs else 0f
                        when {
                            fraction >= 0.2f -> 0.6f
                            now - last > 14 * day -> -0.4f
                            else -> 0.2f
                        }
                    }
                    else -> 0f
                }
            }
            val fromFile = item.imdbId?.let { imdb.lookup(it) }
            se.jptv.recs.RecItem(
                id = item.ratingKey,
                type = item.type,
                tags = item.tags,
                engagement = if (excluded) -1f else engagement,
                lastViewedAt = last,
                unseen = unseen,
                rating = fromFile?.first ?: item.ratings.firstOrNull { it.source == "IMDb" }?.value?.toFloat(),
                votes = fromFile?.second ?: 0,
                addedAt = item.addedAt,
                excluded = excluded,
            )
        }
        val byId = items.associateBy { it.ratingKey }
        val model = se.jptv.recs.Recommender.build(recItems, now)
        val rows = mutableListOf<PlexHomeRow>()
        fun row(title: String, ids: List<String>) {
            val list = ids.mapNotNull { byId[it] }
            if (list.size >= 5) rows += PlexHomeRow(title, list)
        }
        // Nya förslag varje dag: de bästa 40 dras om viktat mot toppen.
        val today = now / day
        if (se.jptv.recs.Recommender.signal(model, "movie") >= 3) {
            val ranked = se.jptv.recs.Recommender.forYou(model, "movie", now, limit = 40)
            row("Rekommenderat för dig · Filmer", se.jptv.recs.Recommender.dailyMix(ranked, today))
        }
        if (se.jptv.recs.Recommender.signal(model, "show") >= 2) {
            val ranked = se.jptv.recs.Recommender.forYou(model, "show", now, limit = 40)
            row("Rekommenderat för dig · Serier", se.jptv.recs.Recommender.dailyMix(ranked, today))
        }
        for (seed in se.jptv.recs.Recommender.seeds(model, now)) {
            val title = byId[seed]?.title ?: continue
            row("Eftersom du såg $title", se.jptv.recs.Recommender.similarTo(model, seed))
        }
        return rows
    }

    fun toggleSectionHidden(section: PlexSection) {
        val updated = if (section.key in hiddenSections) hiddenSections - section.key else hiddenSections + section.key
        hiddenSections = updated
        prefs.hiddenSections = updated
        refreshHome()
    }

    fun toggleSectionFavorite(section: PlexSection) {
        val updated = if (section.key in favoriteSections) favoriteSections - section.key else favoriteSections + section.key
        favoriteSections = updated
        prefs.favoriteSections = updated
        refreshHome()
    }

    fun playTrailer(trailer: PlexItem) {
        open(Screen.Player(PlayRequest.Vod(trailer, fromStart = true, trailer = true)))
    }

    /** Filmer och avsnitt öppnar detaljsidan, serier och säsonger avsnittslistan. */
    fun select(item: PlexItem) {
        when {
            item.isPlayable -> open(Screen.Details(item))
            item.isContainer -> open(Screen.Episodes(item))
            item.isCollection -> open(Screen.Collection(item))
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

    val sectionGenre = HashMap<String, se.jptv.plex.PlexFilterOption?>()

    suspend fun sectionItems(
        sectionKey: String,
        sort: se.jptv.plex.LibrarySort = se.jptv.plex.LibrarySort.TITLE,
        unwatchedOnly: Boolean = false,
        genreId: String? = null,
    ): List<PlexItem> {
        val cacheKey = "$sectionKey|${sort.name}|$unwatchedOnly|${genreId.orEmpty()}"
        sectionCache[cacheKey]?.let { return it }
        var list = plex.all(sectionKey, sort, unwatchedOnly, genreId)
        if (sort == se.jptv.plex.LibrarySort.IMDB) {
            list = withContext(kotlinx.coroutines.Dispatchers.Default) { list.sortedByDescending { imdbValue(it) ?: -1f } }
        }
        sectionCache[cacheKey] = list
        return list
    }

    /** IMDb-betyget som tal (för sortering). */
    fun imdbValue(item: PlexItem): Float? =
        item.imdbId?.let { imdb.lookup(it)?.first } ?: item.ratings.firstOrNull { it.source == "IMDb" }?.value?.toFloat()

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
                plex.search(q).map { it.copy(items = visible(it.items)) }.filter { it.items.isNotEmpty() }
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

    // ---------- Påminnelser från guiden ----------

    var reminders by mutableStateOf(prefs.reminders.mapNotNull { Reminder.decode(it) })
        private set

    /** Påminnelsen som visas just nu (programmet har börjat). */
    var dueReminder by mutableStateOf<Reminder?>(null)
        private set

    fun hasReminder(channel: Channel, start: Long): Boolean =
        reminders.any { it.channelKey == channel.key && it.start == start }

    /** Sätter eller tar bort en påminnelse; true om den nu är satt. */
    fun toggleReminder(channel: Channel, programme: se.jptv.iptv.Programme): Boolean {
        val exists = hasReminder(channel, programme.start)
        reminders = if (exists) {
            reminders.filterNot { it.channelKey == channel.key && it.start == programme.start }
        } else {
            reminders + Reminder(channel.key, programme.start, programme.title, "${channel.number} ${channel.name}")
        }
        prefs.reminders = reminders.map { it.encode() }
        return !exists
    }

    /** Körs var 15:e sekund: visar det som har börjat (upp till tio minuter sent), glömmer äldre. */
    private fun checkReminders() {
        val now = System.currentTimeMillis()
        val (started, waiting) = reminders.partition { it.start <= now + 15_000 }
        if (started.isEmpty()) return
        reminders = waiting
        prefs.reminders = waiting.map { it.encode() }
        if (dueReminder == null) dueReminder = started.lastOrNull { now < it.start + 10 * 60_000 }
    }

    fun dismissReminder() {
        dueReminder = null
    }

    /** "Byt kanal" i påminnelsen: kanalen i helskärm, i sin grupp så att ▲▼ fungerar. */
    fun playReminder(reminder: Reminder) {
        dueReminder = null
        val ch = playlist?.byKey?.get(reminder.channelKey) ?: return
        val group = channelsForGroup(ch.group)
        val index = group.indexOfFirst { it.key == ch.key }
        val request = if (index >= 0) PlayRequest.Live(group, index) else PlayRequest.Live(listOf(ch), 0)
        closePip()
        if (current is Screen.Player) replaceTop(Screen.Player(request)) else open(Screen.Player(request))
    }

    init {
        viewModelScope.launch {
            while (true) {
                checkReminders()
                delay(15_000)
            }
        }
    }

    fun playChannel(channels: List<Channel>, index: Int) {
        val session = pip
        val target = channels.getOrNull(index)
        if (session != null && target != null && current !is Screen.Player) {
            if (target.key == session.channel.key) {
                // Andra OK på kanalen som redan spelar i rutan: helskärm, med listan man valde i.
                pip = se.jptv.player.LiveSession(session.exo, channels, index, session.previousKey)
                resumePip()
            } else {
                // Första OK: byt kanal i rutan och stanna kvar i listan eller guiden.
                pip = session.retune(channels, index)
            }
            return
        }
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

        /** Kvalitetsval för telefonen och casting hemifrån. */
        val REMOTE_QUALITIES: List<Pair<String, Pair<Int, String>?>> = listOf(
            "Original" to null,
            "8 Mbit 1080p" to (8000 to "1920x1080"),
            "4 Mbit 720p" to (4000 to "1280x720"),
            "2 Mbit 720p" to (2000 to "1280x720"),
            "1 Mbit 480p" to (1000 to "854x480"),
        )
    }
}
