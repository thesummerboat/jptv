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
) {
    val isShow: Boolean get() = type == "show"

    /** Kan önskas: finns inte i Plex och är inte redan önskad (eller fick nej). */
    val canWish: Boolean get() = libraryKey == null && (status == null || status == "declined")

    val statusText: String?
        get() = when (status) {
            "pending" -> "Önskad · väntar på OK"
            "approved" -> "Godkänd · på väg"
            "available" -> "Finns nu i Plex"
            "declined" -> "Fick nej"
            else -> null
        }

    fun toJson(by: String): JSONObject = JSONObject()
        .put("guid", guid).put("key", key).put("type", type).put("title", title)
        .put("year", year ?: JSONObject.NULL).put("summary", summary ?: JSONObject.NULL)
        .put("thumb", thumb ?: JSONObject.NULL).put("art", art ?: JSONObject.NULL)
        .put("by", by)

    companion object {
        fun parse(o: JSONObject): WishItem = WishItem(
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

    /** Skickar önskemålet. Returnerar titeln med ny status. */
    suspend fun wish(item: WishItem, by: String): WishItem = withContext(Dispatchers.IO) {
        val base = baseUrl ?: throw IOException("JPTV Web hittades inte")
        val body = item.toJson(by).toString().toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$base/api/requests").post(body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 409) return@use item.copy(libraryKey = JSONObject(text).optString("libraryKey").ifBlank { item.key })
            if (!resp.isSuccessful) throw IOException(errorOf(text) ?: "JPTV Web svarade HTTP ${resp.code}")
            val status = JSONObject(text).optJSONObject("request")?.optString("status") ?: "pending"
            item.copy(status = status)
        }
    }

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
