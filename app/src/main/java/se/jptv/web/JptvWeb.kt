package se.jptv.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** En film eller serie ur Plex katalog, med om den redan finns i Plex eller är önskad. */
data class WishItem(
    val guid: String,
    val key: String,
    val type: String,
    val title: String,
    val year: Int?,
    val summary: String?,
    val thumb: String?,
    val art: String?,
    val libraryKey: String?,
    val status: String?,
    /** JPTV Web har Radarr 4K: filmer kan också önskas i 4K (ett eget önskemål bredvid det vanliga). */
    val uhdEnabled: Boolean = false,
    /** Finns utanför 4K-biblioteket (utan 4K: samma som libraryKey). */
    val hdKey: String? = libraryKey,
    /** Finns i 4K-biblioteket. */
    val uhdKey: String? = null,
    /** 4K-önskemålets status. */
    val uhdStatus: String? = null,
) {
    val isShow: Boolean get() = type == "show"

    /** Kan önskas: finns inte i Plex och är inte redan önskad (eller fick nej). */
    val canWish: Boolean get() = hdKey == null && isOpen(status)

    /** Kan önskas i 4K: filmen saknas i 4K-biblioteket och är inte redan önskad i 4K. */
    val canWishUhd: Boolean get() = uhdEnabled && !isShow && uhdKey == null && isOpen(uhdStatus)

    val statusText: String?
        get() = listOfNotNull(describe(status), describe(uhdStatus)?.let { "4K: $it" })
            .joinToString(" · ").ifBlank { null }

    fun toJson(by: String, uhd: Boolean = false): JSONObject = JSONObject()
        .put("guid", guid).put("key", key).put("type", type).put("title", title)
        .put("year", year ?: JSONObject.NULL).put("summary", summary ?: JSONObject.NULL)
        .put("thumb", thumb ?: JSONObject.NULL).put("art", art ?: JSONObject.NULL)
        .put("by", by).put("uhd", uhd)

    companion object {
        private fun isOpen(status: String?) = status == null || status == "declined"

        private fun describe(status: String?): String? = when (status) {
            "pending" -> "Önskad · väntar på OK"
            "approved" -> "Godkänd · på väg"
            "available" -> "Finns nu i Plex"
            "declined" -> "Fick nej"
            else -> null
        }

        private fun JSONObject.str(name: String): String? = optString(name).takeIf { it.isNotBlank() && it != "null" }

        fun parse(o: JSONObject): WishItem = parseBase(o).let { w ->
            // Fälten finns bara när JPTV Web har Radarr 4K (och titeln är en film).
            if (!o.has("uhdKey")) return@let w
            w.copy(
                uhdEnabled = true,
                hdKey = if (o.has("hdKey")) o.str("hdKey") else w.libraryKey,
                uhdKey = o.str("uhdKey"),
                uhdStatus = o.optJSONObject("requestUhd")?.optString("status")?.takeIf { it.isNotBlank() },
            )
        }

        private fun parseBase(o: JSONObject): WishItem = WishItem(
            guid = o.optString("guid"),
            key = o.optString("key"),
            type = o.optString("type", "movie"),
            title = o.optString("title"),
            year = if (o.has("year") && !o.isNull("year")) o.optInt("year") else null,
            summary = o.optString("summary").takeIf { it.isNotBlank() && it != "null" },
            thumb = o.optString("thumb").takeIf { it.isNotBlank() && it != "null" },
            art = o.optString("art").takeIf { it.isNotBlank() && it != "null" },
            libraryKey = o.optString("libraryKey").takeIf { it.isNotBlank() && it != "null" },
            status = o.optJSONObject("request")?.optString("status")?.takeIf { it.isNotBlank() },
        )
    }
}

/** Orsaker att anmäla en fil (samma id:n som JPTV Web). */
val REPORT_REASONS = listOf(
    "language" to "Fel språk",
    "quality" to "Dålig bild eller ljud",
    "wrong" to "Fel film eller avsnitt",
    "broken" to "Går inte att spela",
    "other" to "Annat",
)

/**
 * JPTV Web på Unraid: önskemål (filmer och serier som saknas i Plex) går dit,
 * och godkänns i webbappen. TV:n och mobilen hittar den på samma adress som Plex, port 8099.
 */
class JptvWeb(http: OkHttpClient) {
    private val client = http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val quick = http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()

    @Volatile
    var baseUrl: String? = null

    /** true om JPTV Web svarar på adressen. */
    suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$url/api/status").header("Accept", "application/json").get().build()
            quick.newCall(req).execute().use { resp ->
                resp.isSuccessful && JSONObject(resp.body?.string().orEmpty()).has("version")
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun discover(query: String): List<WishItem> = withContext(Dispatchers.IO) {
        val base = baseUrl ?: throw IOException("JPTV Web hittades inte")
        val url = "$base/api/discover".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("q", query).build()
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(errorOf(text) ?: "JPTV Web svarade HTTP ${resp.code}")
            val arr = JSONObject(text).optJSONArray("results") ?: return@use emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(WishItem::parse) }
        }
    }

    /** Skickar önskemålet (uhd: önska i 4K). Returnerar titeln med ny status. */
    suspend fun wish(item: WishItem, by: String, uhd: Boolean = false): WishItem = withContext(Dispatchers.IO) {
        val base = baseUrl ?: throw IOException("JPTV Web hittades inte")
        val body = item.toJson(by, uhd).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$base/api/requests").post(body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 409) {
                val key = JSONObject(text).optString("libraryKey").ifBlank { item.key }
                return@use if (uhd) item.copy(uhdKey = key) else item.copy(libraryKey = key, hdKey = key)
            }
            if (!resp.isSuccessful) throw IOException(errorOf(text) ?: "JPTV Web svarade HTTP ${resp.code}")
            val status = JSONObject(text).optJSONObject("request")?.optString("status") ?: "pending"
            if (uhd) item.copy(uhdStatus = status) else item.copy(status = status)
        }
    }

    /**
     * Anmäl en film eller ett avsnitt i Plex (fel språk, trasig fil …): JPTV Web låter Radarr/Sonarr
     * svartlista släppet, ta bort filen och ladda ner en annan version. Svarar med vad som hände.
     */
    suspend fun report(key: String, reason: String, by: String): String =
        send("POST", "/api/report", JSONObject().put("key", key).put("reason", reason).put("by", by))
            .optString("ok").ifBlank { "Anmäld" }

    // ---------- YouTube

    private suspend fun getJson(path: String, timeoutSec: Long = 15): JSONObject = withContext(Dispatchers.IO) {
        val base = baseUrl ?: throw IOException("JPTV Web hittades inte")
        val c = if (timeoutSec > 15) client.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build() else client
        c.newCall(Request.Builder().url(base + path).header("Accept", "application/json").get().build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 404 && path.startsWith("/api/yt/")) throw IOException("JPTV Web på Unraid behöver uppdateras för YouTube")
            if (!resp.isSuccessful) throw IOException(errorOf(text) ?: "JPTV Web svarade HTTP ${resp.code}")
            JSONObject(text)
        }
    }

    private suspend fun send(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val base = baseUrl ?: throw IOException("JPTV Web hittades inte")
        val rb = (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(base + path).method(method, if (method == "DELETE" && body == null) null else rb).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(errorOf(text) ?: "JPTV Web svarade HTTP ${resp.code}")
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    suspend fun ytHome(): YtHome = YtJson.home(getJson("/api/yt/home", 30))

    /** Sök tar några sekunder (YouTube frågas via yt-dlp). */
    suspend fun ytSearch(query: String): YtSearch {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return YtJson.search(getJson("/api/yt/search?q=$q", 60))
    }

    suspend fun ytChannel(id: String): YtChannelPage =
        YtJson.channelPage(getJson("/api/yt/channel/$id", 60)) ?: throw IOException("Kanalen hittades inte")

    suspend fun ytVideo(id: String): YtVideoInfo =
        YtJson.videoInfo(getJson("/api/yt/video/$id", 90)) ?: throw IOException("Videon hittades inte")

    /** Förslagen tar några sekunder första gången (YouTube frågas om varje sedd video). */
    suspend fun ytRecommended(): YtRecs = YtJson.recs(getJson("/api/yt/recommended", 60))

    /** Kommentarer: första sidan, eller nästa sida / svaren på en kommentar (token). */
    suspend fun ytComments(videoId: String, token: String? = null): YtComments {
        val q = token?.let { "?token=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        return YtJson.comments(getJson("/api/yt/comments/$videoId$q", 30))
    }

    /** Be servern förbereda en video (man står på kortet), så att den startar direkt. */
    suspend fun ytPrefetch(videoId: String) {
        send("POST", "/api/yt/prefetch/$videoId", null)
    }

    /** "Inte intresserad": videon föreslås inte igen. */
    suspend fun ytDismiss(videoId: String) {
        send("POST", "/api/yt/dismiss", JSONObject().put("id", videoId))
    }

    /** Inga fler förslag från kanalen (ångras i webben under YouTube → Historik). */
    suspend fun ytBlock(channelId: String, name: String) {
        send("POST", "/api/yt/block", JSONObject().put("id", channelId).put("name", name))
    }

    suspend fun ytSubscribe(channel: YtChannel, on: Boolean) {
        if (on) {
            send("POST", "/api/yt/subs", JSONObject().put("id", channel.id).put("name", channel.name).put("avatar", channel.avatar ?: JSONObject.NULL))
        } else {
            send("DELETE", "/api/yt/subs/${channel.id}", null)
        }
    }

    suspend fun ytProgress(video: YtVideoInfo, positionMs: Long, durationMs: Long) {
        send(
            "POST", "/api/yt/progress",
            JSONObject().put("id", video.id).put("pos", positionMs / 1000.0).put("dur", durationMs / 1000.0)
                .put("title", video.title).put("channel", video.channel).put("channelId", video.channelId)
                .put("thumb", video.thumb ?: JSONObject.NULL),
        )
    }

    /** Rådata från JPTV Web (förhandsbilder), eller null om det inte gick. */
    suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val base = baseUrl ?: return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(base + path).get().build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    /** Full adress till en sökväg på JPTV Web (spellistor, bilder). */
    fun url(path: String): String? = baseUrl?.let { it + path }

    /** YouTubes bilder via JPTV Web, så att TV:n aldrig pratar med Google själv. */
    fun ytImage(url: String?): String? {
        val u = url?.takeIf { it.isNotBlank() } ?: return null
        val full = if (u.startsWith("//")) "https:$u" else u
        return baseUrl?.let { it + "/yt/img?u=" + java.net.URLEncoder.encode(full, "UTF-8") }
    }

    private fun errorOf(text: String): String? = try {
        JSONObject(text).optString("error").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        text.take(120).takeIf { it.isNotBlank() }
    }
}
