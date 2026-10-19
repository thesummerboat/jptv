package se.jptv.plex

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import se.jptv.BuildConfig
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeUnit

/**
 * Pratar med plex.tv (inloggning, hitta servern) och med Plex Media Server (bibliotek,
 * uppspelning, sett-status). Allt går som JSON genom att skicka Accept: application/json.
 * Dokumentation: https://developer.plex.tv
 */
class PlexClient(private val clientId: String, private val http: OkHttpClient) {

    /** Kort timeout för att testa vilken serveradress som svarar. */
    private val quick = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    @Volatile
    var server: PlexServer? = null

    private val deviceModel: String = ascii(Build.MODEL ?: "Android TV").ifBlank { "Android TV" }

    private fun Request.Builder.plexHeaders(token: String?): Request.Builder {
        header("Accept", "application/json")
        header("X-Plex-Product", PRODUCT)
        header("X-Plex-Version", BuildConfig.VERSION_NAME)
        header("X-Plex-Client-Identifier", clientId)
        header("X-Plex-Platform", "Android")
        header("X-Plex-Platform-Version", ascii(Build.VERSION.RELEASE ?: ""))
        header("X-Plex-Device", deviceModel)
        header("X-Plex-Device-Name", "JPTV ($deviceModel)")
        if (token != null) header("X-Plex-Token", token)
        return this
    }

    /** Headers som spelaren skickar när den hämtar video direkt från servern. */
    fun streamHeaders(): Map<String, String> {
        val s = server ?: return emptyMap()
        return mapOf(
            "X-Plex-Token" to s.token,
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Product" to PRODUCT,
        )
    }

    /**
     * Skickar anropet och väntar utan att låsa en tråd, så att det går att avbryta – t.ex. när
     * spelaren ger upp efter några sekunder eller skärmen stängs.
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private suspend fun getJson(url: String, token: String?): JSONObject {
        val req = Request.Builder().url(url).plexHeaders(token).get().build()
        val resp = http.newCall(req).await()
        try {
            return withContext(Dispatchers.IO) {
                if (!resp.isSuccessful) throw PlexException("Plex svarade HTTP ${resp.code}")
                JSONObject(resp.body?.string().orEmpty())
            }
        } finally {
            resp.close()
        }
    }

    private suspend fun fire(url: String, token: String, extraHeaders: Map<String, String> = emptyMap()) {
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url).plexHeaders(token).get()
                for ((k, v) in extraHeaders) builder.header(k, v)
                http.newCall(builder.build()).execute().close()
            }
        }
    }

    /** Anrop utan våra vanliga X-Plex-headers, så att parametrarna i adressen gäller fullt ut. */
    private suspend fun fireBare(url: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
                http.newCall(req).execute().close()
            }
        }
    }

    // ---------- Inloggning med kod (plex.tv/link) ----------

    suspend fun createPin(): PlexPin = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://plex.tv/api/v2/pins")
            .plexHeaders(null)
            .post("".toRequestBody())
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw PlexException("Kunde inte hämta inloggningskod (HTTP ${resp.code})")
            val o = JSONObject(resp.body?.string().orEmpty())
            PlexPin(o.getLong("id"), o.getString("code"))
        }
    }

    /** Returnerar kontots token när koden har godkänts på plex.tv/link, annars null. */
    suspend fun checkPin(pin: PlexPin): String? {
        val o = getJson("https://plex.tv/api/v2/pins/${pin.id}", null)
        return o.optStringOrNull("authToken")
    }

    // ---------- Hitta servern ----------

    /** Hämtar kontots servrar och väljer den första adress som svarar, lokal adress först. */
    suspend fun findServer(accountToken: String): PlexServer = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1")
            .plexHeaders(accountToken)
            .get()
            .build()
        val arr = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw PlexException("Kunde inte hämta servrar (HTTP ${resp.code})")
            JSONArray(resp.body?.string().orEmpty())
        }
        val servers = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.optString("provides").split(",").contains("server") }
            .sortedByDescending { it.optBoolean("owned") }
        if (servers.isEmpty()) throw PlexException("Hittade ingen Plex-server på kontot")

        for (s in servers) {
            val token = s.optStringOrNull("accessToken") ?: accountToken
            val name = s.optStringOrNull("name") ?: "Plex"
            val candidates = candidateUris(s.optJSONArray("connections"))
            val results = coroutineScope {
                candidates.map { uri -> async { uri to isReachable(uri, token) } }.awaitAll()
            }
            val ok = results.firstOrNull { it.second }?.first
            if (ok != null) return@withContext PlexServer(name, ok, token)
        }
        throw PlexException("Plex-servern svarar inte. Är TV:n på samma nät som servern?")
    }

    private fun candidateUris(conns: JSONArray?): List<String> {
        if (conns == null) return emptyList()
        val ranked = mutableListOf<Pair<Int, String>>()
        for (i in 0 until conns.length()) {
            val c = conns.getJSONObject(i)
            val local = c.optBoolean("local")
            val relay = c.optBoolean("relay")
            val base = when {
                local && !relay -> 0
                !relay -> 10
                else -> 20
            }
            c.optStringOrNull("uri")?.let { ranked += base to it.trimEnd('/') }
            // Reserv om routern blockerar plex.direct-adresser: vanlig http på lokal IP.
            val address = c.optStringOrNull("address")
            if (local && !relay && address != null && !address.contains(':')) {
                ranked += (base + 1) to "http://$address:${c.optInt("port", 32400)}"
            }
        }
        return ranked.sortedBy { it.first }.map { it.second }.distinct()
    }

    suspend fun isReachable(uri: String, token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$uri/identity").plexHeaders(token).get().build()
            quick.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Bibliotek ----------

    private fun srv(): PlexServer = server ?: throw PlexException("Ingen Plex-server vald")

    suspend fun sections(): List<PlexSection> {
        val s = srv()
        val mc = getJson("${s.uri}/library/sections", s.token).getJSONObject("MediaContainer")
        val dirs = mc.optJSONArray("Directory") ?: return emptyList()
        return (0 until dirs.length())
            .map { dirs.getJSONObject(it) }
            .filter { it.optString("type") == "movie" || it.optString("type") == "show" }
            .map { PlexSection(it.optString("key"), it.optString("title"), it.optString("type")) }
    }

    // ---------- Skanna bibliotek ----------

    /** Ber servern leta efter nya filer i ett bibliotek. Fungerar när du äger servern. */
    suspend fun scanLibrary(sectionKey: String) {
        val s = srv()
        val req = Request.Builder().url("${s.uri}/library/sections/$sectionKey/refresh").plexHeaders(s.token).get().build()
        http.newCall(req).await().use { resp ->
            when {
                resp.code == 401 || resp.code == 403 -> throw PlexException("bara serverns ägare kan skanna bibliotek")
                !resp.isSuccessful -> throw PlexException("Plex svarade HTTP ${resp.code}")
            }
        }
    }

    /** Nycklarna för de bibliotek som servern skannar just nu. */
    suspend fun refreshingSections(): Set<String> {
        val s = srv()
        val mc = getJson("${s.uri}/library/sections", s.token).getJSONObject("MediaContainer")
        val dirs = mc.optJSONArray("Directory") ?: return emptySet()
        return (0 until dirs.length())
            .map { dirs.getJSONObject(it) }
            .filter { it.optBoolean("refreshing", false) }
            .map { it.optString("key") }
            .toSet()
    }

    suspend fun onDeck(): List<PlexItem> = items("/library/onDeck")

    suspend fun recentlyAdded(sectionKey: String): List<PlexItem> =
        items("/library/sections/$sectionKey/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=30")

    suspend fun all(sectionKey: String, sort: LibrarySort = LibrarySort.TITLE, unwatchedOnly: Boolean = false): List<PlexItem> {
        val filter = if (unwatchedOnly) "&unwatched=1" else ""
        return items(
            "/library/sections/$sectionKey/all?sort=${sort.param}$filter" +
                "&X-Plex-Container-Start=0&X-Plex-Container-Size=2000",
        )
    }

    /** Säsongerna i en serie. */
    suspend fun seasons(showKey: String): List<PlexItem> =
        items("/library/metadata/$showKey/children").filter { it.type == "season" }

    /** Avsnitt i en serie (alla säsonger) eller i en säsong. */
    suspend fun episodes(container: PlexItem): List<PlexItem> =
        if (container.type == "show") items("/library/metadata/${container.ratingKey}/allLeaves")
        else items("/library/metadata/${container.ratingKey}/children")

    private suspend fun items(path: String): List<PlexItem> {
        val s = srv()
        // includeGuids: IMDb-id för varje titel, så att IMDb-betyget kan visas.
        val sep = if (path.contains('?')) '&' else '?'
        val mc = getJson("${s.uri}$path${sep}includeGuids=1", s.token).getJSONObject("MediaContainer")
        // Långa serier och stora bibliotek: tolka utanför huvudtråden.
        return withContext(Dispatchers.Default) { metadataArray(mc) }
    }

    private fun metadataArray(container: JSONObject): List<PlexItem> {
        val out = mutableListOf<PlexItem>()
        for (name in listOf("Metadata", "Directory", "Video")) {
            val arr = container.optJSONArray(name) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.has("ratingKey")) out += parseItem(o)
            }
        }
        return out
    }

    // ---------- Sök ----------

    /** Sök i hela Plex-biblioteket; svaret kommer grupperat (filmer, serier, avsnitt …). */
    suspend fun search(query: String): List<PlexSearchHub> {
        val s = srv()
        val q = URLEncoder.encode(query, "UTF-8")
        val mc = getJson("${s.uri}/hubs/search?query=$q&limit=20&includeCollections=0&includeGuids=1", s.token)
            .getJSONObject("MediaContainer")
        val hubs = mc.optJSONArray("Hub") ?: return emptyList()
        return (0 until hubs.length()).mapNotNull { i ->
            val hub = hubs.optJSONObject(i) ?: return@mapNotNull null
            val items = metadataArray(hub).filter { it.type in setOf("movie", "show", "episode", "season") }
            if (items.isEmpty()) null else PlexSearchHub(hub.optStringOrNull("title") ?: "Träffar", items)
        }
    }

    // ---------- Undertexter och spårval ----------

    /** Söker undertexter via Plex-serverns undertextagent. Språk som tvåbokstavskod (sv, en …). */
    suspend fun searchSubtitles(item: PlexItem, language: String): List<SubtitleResult> {
        val s = srv()
        val mc = getJson(
            "${s.uri}/library/metadata/${item.ratingKey}/subtitles?language=$language&hearingImpaired=0&forced=0",
            s.token,
        ).getJSONObject("MediaContainer")
        val arr = mc.optJSONArray("Stream") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optStringOrNull("key") ?: return@mapNotNull null
            SubtitleResult(
                key = key,
                title = o.optStringOrNull("title") ?: o.optStringOrNull("displayTitle") ?: "Undertext ${i + 1}",
                languageCode = o.optStringOrNull("languageCode") ?: o.optStringOrNull("language"),
                provider = o.optStringOrNull("providerTitle"),
                score = o.optStringOrNull("score"),
                hearingImpaired = o.optBoolean("hearingImpaired"),
                codec = o.optStringOrNull("codec"),
            )
        }
    }

    /** Ber servern ladda ner en undertext. Servern gör det i bakgrunden; den dyker upp som nytt spår. */
    suspend fun downloadSubtitle(item: PlexItem, result: SubtitleResult) {
        val s = srv()
        put("${s.uri}/library/metadata/${item.ratingKey}/subtitles?key=${URLEncoder.encode(result.key, "UTF-8")}", s.token)
    }

    /** Sparar valt ljud-/undertextspår i Plex. subtitleStreamId = 0 betyder undertexter av. */
    suspend fun setSelectedStreams(item: PlexItem, audioStreamId: Long? = null, subtitleStreamId: Long? = null) {
        val s = server ?: return
        val part = item.partId ?: return
        val params = buildList {
            add("allParts=1")
            audioStreamId?.let { add("audioStreamID=$it") }
            subtitleStreamId?.let { add("subtitleStreamID=$it") }
        }.joinToString("&")
        put("${s.uri}/library/parts/$part?$params", s.token)
    }

    suspend fun removeFromContinueWatching(item: PlexItem) {
        val s = server ?: return
        put("${s.uri}/actions/removeFromContinueWatching?ratingKey=${item.ratingKey}", s.token)
    }

    private suspend fun put(url: String, token: String) {
        val req = Request.Builder().url(url).plexHeaders(token).put("".toRequestBody()).build()
        http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw PlexException("Plex svarade HTTP ${resp.code}")
        }
    }

    private fun parseItem(o: JSONObject): PlexItem {
        val type = o.optString("type")
        val media = o.optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)
        val thumb = when (type) {
            // Avsnitt visas med seriens affisch, som i Plex egna TV-app.
            "episode" -> o.optStringOrNull("grandparentThumb") ?: o.optStringOrNull("parentThumb") ?: o.optStringOrNull("thumb")
            "season" -> o.optStringOrNull("thumb") ?: o.optStringOrNull("parentThumb")
            else -> o.optStringOrNull("thumb")
        }
        val streamArr = part?.optJSONArray("Stream")
        val streams = if (streamArr == null) emptyList() else (0 until streamArr.length()).mapNotNull { i ->
            val s = streamArr.optJSONObject(i) ?: return@mapNotNull null
            PlexStream(
                id = s.optLongOrNull("id") ?: 0L,
                type = s.optIntOrNull("streamType") ?: 0,
                codec = s.optStringOrNull("codec"),
                languageCode = s.optStringOrNull("languageCode") ?: s.optStringOrNull("languageTag"),
                title = s.optStringOrNull("extendedDisplayTitle") ?: s.optStringOrNull("displayTitle") ?: "Spår ${i + 1}",
                selected = s.optBoolean("selected"),
                key = s.optStringOrNull("key"),
                frameRate = s.optDoubleOrNull("frameRate")?.toFloat(),
                doviProfile = s.optIntOrNull("DOVIProfile"),
                doviPresent = s.optBoolean("DOVIPresent", false),
                colorTrc = s.optStringOrNull("colorTrc"),
                height = s.optIntOrNull("height"),
                channels = s.optIntOrNull("channels"),
            )
        }
        val markerArr = o.optJSONArray("Marker")
        val markers = if (markerArr == null) emptyList() else (0 until markerArr.length()).mapNotNull { i ->
            val m = markerArr.optJSONObject(i) ?: return@mapNotNull null
            val start = m.optLongOrNull("startTimeOffset") ?: return@mapNotNull null
            val end = m.optLongOrNull("endTimeOffset") ?: return@mapNotNull null
            PlexMarker(m.optString("type"), start, end, final = m.optBoolean("final", false))
        }
        val genreArr = o.optJSONArray("Genre")
        val genres = if (genreArr == null) emptyList() else (0 until genreArr.length()).mapNotNull { i ->
            genreArr.optJSONObject(i)?.optStringOrNull("tag")
        }
        val imageArr = o.optJSONArray("Image")
        val logo = if (imageArr == null) null else (0 until imageArr.length())
            .mapNotNull { imageArr.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "clearLogo" }
            ?.optStringOrNull("url")
        // IMDb-id: nya agenter lägger det i Guid-listan ("imdb://tt0111161"), gamla i guid-adressen.
        val guidArr = o.optJSONArray("Guid")
        val guids = buildList {
            if (guidArr != null) for (i in 0 until guidArr.length()) guidArr.optJSONObject(i)?.optStringOrNull("id")?.let { add(it) }
            o.optStringOrNull("guid")?.let { add(it) }
        }
        val imdbId = guids.firstNotNullOfOrNull { imdbRegex.find(it)?.value }
        val showKey = o.optStringOrNull("grandparentRatingKey")
            ?: if (type == "season") o.optStringOrNull("parentRatingKey") else null
        return PlexItem(
            ratingKey = o.optString("ratingKey"),
            type = type,
            title = o.optString("title"),
            showTitle = o.optStringOrNull("grandparentTitle") ?: o.optStringOrNull("parentTitle"),
            season = o.optIntOrNull("parentIndex"),
            episode = o.optIntOrNull("index"),
            year = o.optIntOrNull("year"),
            summary = o.optStringOrNull("summary"),
            thumb = thumb,
            art = o.optStringOrNull("art"),
            durationMs = o.optLongOrNull("duration") ?: media?.optLongOrNull("duration") ?: 0L,
            viewOffsetMs = o.optLongOrNull("viewOffset") ?: 0L,
            watched = (o.optIntOrNull("viewCount") ?: 0) > 0,
            partKey = part?.optStringOrNull("key"),
            partId = part?.optLongOrNull("id"),
            viewedLeafCount = o.optIntOrNull("viewedLeafCount"),
            videoCodec = media?.optStringOrNull("videoCodec"),
            audioCodec = media?.optStringOrNull("audioCodec"),
            resolution = media?.optStringOrNull("videoResolution"),
            showKey = showKey,
            streams = streams,
            markers = markers,
            contentRating = o.optStringOrNull("contentRating"),
            genres = genres,
            childCount = o.optIntOrNull("leafCount") ?: o.optIntOrNull("childCount"),
            logo = logo,
            ratings = parseRatings(o),
            audioChannels = media?.optIntOrNull("audioChannels"),
            hasPreviews = part?.optStringOrNull("indexes") == "sd",
            imdbId = imdbId,
        )
    }

    /** Betyg: Plex nyare Rating-lista, annars de äldre fälten rating/audienceRating. */
    private fun parseRatings(o: JSONObject): List<PlexRating> {
        val raw = mutableListOf<Pair<String, Double>>()
        val arr = o.optJSONArray("Rating")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val image = r.optStringOrNull("image") ?: continue
                val value = r.optDoubleOrNull("value") ?: continue
                raw += image to value
            }
        } else {
            val critic = o.optDoubleOrNull("rating")
            val criticImage = o.optStringOrNull("ratingImage")
            if (critic != null && criticImage != null) raw += criticImage to critic
            val audience = o.optDoubleOrNull("audienceRating")
            val audienceImage = o.optStringOrNull("audienceRatingImage")
            if (audience != null && audienceImage != null) raw += audienceImage to audience
        }
        return RatingFormat.format(raw)
    }

    /** Full information om en titel: spår, markörer för intro/eftertexter och aktuell position. */
    suspend fun metadata(ratingKey: String): PlexItem? =
        items("/library/metadata/$ratingKey?includeMarkers=1").firstOrNull()

    /** Nästa avsnitt i serien, eller null om det här är sista. */
    suspend fun nextEpisode(episode: PlexItem): PlexItem? {
        if (episode.type != "episode") return null
        val show = episode.showKey ?: return null
        val all = items("/library/metadata/$show/allLeaves")
        val index = all.indexOfFirst { it.ratingKey == episode.ratingKey }
        return if (index >= 0) all.getOrNull(index + 1) else null
    }

    /** URL till en extern undertext (t.ex. en .srt som ligger bredvid filen). */
    fun subtitleUrl(stream: PlexStream): String? {
        val s = server ?: return null
        val key = stream.key ?: return null
        // encoding=utf-8: servern gör om äldre svenska .srt-filer (Windows-1252) så att å, ä och ö blir rätt.
        return "${s.uri}$key?encoding=utf-8&X-Plex-Token=${s.token}"
    }

    /** Hämtar en textfil (undertext) och avkodar den som UTF-8, eller Windows-1252 om det inte går. */
    suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).plexHeaders(server?.token).get().build()
        val bytes = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw PlexException("Plex svarade HTTP ${resp.code}")
            resp.body?.bytes() ?: ByteArray(0)
        }
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("﻿")
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
    }

    // ---------- Bilder och uppspelning ----------

    /** Bilden som den är, utan omskalning (för logotyper med genomskinlig bakgrund). */
    fun rawImageUrl(path: String?): String? {
        val s = server ?: return null
        if (path.isNullOrBlank()) return null
        val sep = if (path.contains('?')) '&' else '?'
        return "${s.uri}$path${sep}X-Plex-Token=${s.token}"
    }

    /** Hämtar en fil från servern, t.ex. en förhandsbild. null om servern svarar med fel. */
    suspend fun fetchBytes(path: String): ByteArray? {
        val url = rawImageUrl(path) ?: return null
        val req = Request.Builder().url(url).plexHeaders(server?.token).get().build()
        val resp = http.newCall(req).await()
        try {
            return withContext(Dispatchers.IO) { if (resp.isSuccessful) resp.body?.bytes() else null }
        } finally {
            resp.close()
        }
    }

    /** Laddar ner en (stor) fil från servern direkt till disk. false om servern svarar med fel. */
    suspend fun downloadToFile(path: String, target: java.io.File): Boolean {
        val url = rawImageUrl(path) ?: return false
        val req = Request.Builder().url(url).plexHeaders(server?.token).get().build()
        val resp = http.newCall(req).await()
        try {
            return withContext(Dispatchers.IO) {
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                body.byteStream().use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
                true
            }
        } finally {
            resp.close()
        }
    }

    /** Låter servern skala ner affischen, så att svagare TV-apparater slipper stora bilder. */
    fun imageUrl(path: String?, width: Int = 300, height: Int = 450): String? {
        val s = server ?: return null
        if (path.isNullOrBlank()) return null
        val enc = URLEncoder.encode(path, "UTF-8")
        return "${s.uri}/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=$enc&X-Plex-Token=${s.token}"
    }

    /** Direct Play: filen spelas som den är, servern kodar inte om något. */
    fun directPlayUrl(item: PlexItem): String? {
        val s = server ?: return null
        val key = item.partKey ?: return null
        return "${s.uri}$key?X-Plex-Token=${s.token}"
    }

    private fun transcodeQuery(item: PlexItem, session: String): String {
        val s = srv()
        val params = linkedMapOf(
            "path" to "/library/metadata/${item.ratingKey}",
            "mediaIndex" to "0",
            "partIndex" to "0",
            "protocol" to "hls",
            "fastSeek" to "1",
            "directPlay" to "0",
            "directStream" to "1",
            "directStreamAudio" to "0",
            "videoQuality" to "100",
            "maxVideoBitrate" to "20000",
            "videoResolution" to "1920x1080",
            "offset" to "0",
            // Vald undertext (enligt Plex) bränns in i bilden vid omkodning.
            "subtitles" to "burn",
            "session" to session,
            "X-Plex-Session-Identifier" to session,
            "X-Plex-Token" to s.token,
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Product" to PRODUCT,
            // Webbläsarprofilen ger H.264 + AAC, som alla TV-apparater klarar.
            "X-Plex-Platform" to "Chrome",
            "X-Plex-Device" to deviceModel,
        )
        return params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }

    /**
     * Reserv när TV:n inte klarar filen (vanligt på TCL: DTS-ljud eller ovanliga format).
     * Servern kodar om till HLS med H.264 och AAC.
     */
    suspend fun prepareTranscode(item: PlexItem, session: String): String {
        val s = srv()
        val q = transcodeQuery(item, session)
        // Samma profil (Chrome) för beslut och start: skickas bara i adressen, inte som header.
        fireBare("${s.uri}/video/:/transcode/universal/decision?$q")
        return "${s.uri}/video/:/transcode/universal/start.m3u8?$q"
    }

    suspend fun stopTranscode(session: String) {
        val s = server ?: return
        fire("${s.uri}/video/:/transcode/universal/stop?session=${URLEncoder.encode(session, "UTF-8")}", s.token)
    }

    /** Talar om för Plex var vi är, så att "Fortsätt titta" fungerar även i mobilen. */
    suspend fun reportTimeline(
        item: PlexItem,
        state: String,
        positionMs: Long,
        durationMs: Long,
        transcodeSession: String? = null,
    ) {
        val s = server ?: return
        val key = URLEncoder.encode("/library/metadata/${item.ratingKey}", "UTF-8")
        // Med sessionens ID vet Plex att omkodningen fortfarande används, även under en lång paus.
        val extra = if (transcodeSession != null) mapOf("X-Plex-Session-Identifier" to transcodeSession) else emptyMap()
        fire(
            "${s.uri}/:/timeline?ratingKey=${item.ratingKey}&key=$key&state=$state" +
                "&time=$positionMs&duration=$durationMs&playbackTime=$positionMs",
            s.token,
            extra,
        )
    }

    suspend fun markWatched(item: PlexItem) {
        val s = server ?: return
        fire("${s.uri}/:/scrobble?key=${item.ratingKey}&identifier=com.plexapp.plugins.library", s.token)
    }

    suspend fun markUnwatched(item: PlexItem) {
        val s = server ?: return
        fire("${s.uri}/:/unscrobble?key=${item.ratingKey}&identifier=com.plexapp.plugins.library", s.token)
    }

    companion object {
        const val PRODUCT = "JPTV"
        private val imdbRegex = Regex("""tt\d{5,10}""")
    }
}

private fun ascii(value: String): String = value.filter { it.code in 32..126 }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
