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

    private fun errorOf(text: String): String? = try {
        JSONObject(text).optString("error").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        text.take(120).takeIf { it.isNotBlank() }
    }
}
