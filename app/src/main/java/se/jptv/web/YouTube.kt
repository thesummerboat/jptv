package se.jptv.web

import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube via JPTV Web (som FreeTube): prenumerationer, flöden, sök och uppspelning sköts av
 * servern på Unraid; här finns bara det TV:n behöver visa. Tider från servern är i sekunder,
 * här i millisekunder.
 */
data class YtVideo(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val durationMs: Long?,
    val views: Long?,
    /** Publicerad, ms sedan 1970. */
    val published: Long?,
    val thumb: String?,
    val positionMs: Long,
    val watched: Boolean,
) {
    val progress: Float
        get() = if (durationMs != null && durationMs > 0 && positionMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

data class YtChannel(
    val id: String,
    val name: String,
    val avatar: String?,
    val subscribers: Long?,
    val subscribed: Boolean,
)

data class YtHome(val continueWatching: List<YtVideo>, val feed: List<YtVideo>, val subs: List<YtChannel>)

data class YtSearch(val videos: List<YtVideo>, val channels: List<YtChannel>)

data class YtChannelPage(val channel: YtChannel, val description: String, val videos: List<YtVideo>)

/** En rad "Eftersom du tittade på …": videon man såg och förslagen bredvid den. */
data class YtBecause(val seedId: String, val seedTitle: String, val videos: List<YtVideo>)

/** Startsidans förslag, räknade av JPTV Web utifrån det man har sett och kanalerna man följer. */
data class YtRecs(
    val recommended: List<YtVideo>,
    val because: List<YtBecause>,
    val channels: List<YtChannel>,
    /** Antal sedda videor förslagen bygger på (0: bara prenumerationerna). */
    val seeds: Int,
)

/** En kommentar (från JPTV Web, som hämtar dem från YouTube). */
data class YtComment(
    val id: String,
    val author: String,
    val text: String,
    val avatar: String?,
    val likes: Long,
    val replies: Int,
    /** ms sedan 1970 (ungefärligt, YouTube säger "för 3 dagar sedan"). */
    val published: Long?,
    val pinned: Boolean,
    val hearted: Boolean,
    val creator: Boolean,
    val repliesToken: String?,
)

data class YtComments(val count: Long?, val comments: List<YtComment>, val next: String?, val disabled: Boolean)

data class YtChapter(val title: String, val startMs: Long, val endMs: Long)

/** Del som SponsorBlock säger ska hoppas över (sponsor, egenreklam, prenumerera-tjat). */
data class YtSegment(val startMs: Long, val endMs: Long, val category: String)

/**
 * Förhandsbilder för spolning: jpg-ark (via JPTV Web) med rader × kolumner små bilder. Bild nr
 * floor(sekund × fps) ligger i ark nr / (rader × kolumner).
 */
data class YtStoryboard(
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val fps: Double,
    val frames: Int,
    val sheets: Int,
    /** Adress med {n} för arkets nummer, t.ex. "/yt/abc/sb/{n}.jpg". */
    val url: String,
) {
    data class Spot(val sheet: Int, val column: Int, val row: Int)

    fun spot(ms: Long): Spot {
        val perSheet = rows * columns
        val last = (if (frames > 0) frames else sheets * perSheet) - 1
        val i = kotlin.math.floor(ms / 1000.0 * fps).toInt().coerceIn(0, last.coerceAtLeast(0))
        val k = i % perSheet
        return Spot(i / perSheet, k % columns, k / columns)
    }

    fun sheetPath(n: Int): String = url.replace("{n}", n.toString())
}

data class YtVideoInfo(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val description: String,
    val durationMs: Long,
    val views: Long?,
    val published: Long?,
    val thumb: String?,
    val chapters: List<YtChapter>,
    val sponsor: List<YtSegment>,
    /** Adress på JPTV Web, t.ex. "/yt/abc/master.m3u8". */
    val hls: String,
    val positionMs: Long,
    val subscribed: Boolean,
    val storyboard: YtStoryboard? = null,
)

object YtJson {
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun JSONObject.strOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }?.toLong()

    private fun JSONObject.secondsMs(key: String): Long? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }?.let { (it * 1000).toLong() }

    private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) optJSONObject(i)?.let(f)?.let(out::add)
        return out
    }

    fun video(o: JSONObject): YtVideo? {
        val id = o.str("id").takeIf { it.length == 11 } ?: return null
        return YtVideo(
            id = id,
            title = o.str("title"),
            channel = o.str("channel"),
            channelId = o.str("channelId"),
            durationMs = o.secondsMs("duration"),
            views = o.longOrNull("views"),
            published = o.longOrNull("published"),
            thumb = o.strOrNull("thumb"),
            positionMs = o.secondsMs("position") ?: 0L,
            watched = o.optBoolean("watched", false),
        )
    }

    fun channel(o: JSONObject): YtChannel? {
        val id = o.str("id").takeIf { it.startsWith("UC") } ?: return null
        return YtChannel(
            id = id,
            name = o.str("name"),
            avatar = o.strOrNull("avatar"),
            subscribers = o.longOrNull("subscribers"),
            subscribed = o.optBoolean("subscribed", false),
        )
    }

    fun home(o: JSONObject): YtHome = YtHome(
        continueWatching = o.optJSONArray("continue").mapObjects(::video),
        feed = o.optJSONArray("feed").mapObjects(::video),
        // Prenumerationerna är per definition prenumererade.
        subs = o.optJSONArray("subs").mapObjects(::channel).map { it.copy(subscribed = true) },
    )

    fun search(o: JSONObject): YtSearch = YtSearch(
        videos = o.optJSONArray("videos").mapObjects(::video),
        channels = o.optJSONArray("channels").mapObjects(::channel),
    )

    fun comments(o: JSONObject): YtComments = YtComments(
        count = o.longOrNull("count"),
        comments = o.optJSONArray("comments").mapObjects { c ->
            val id = c.str("id").takeIf { it.isNotBlank() } ?: return@mapObjects null
            YtComment(
                id = id,
                author = c.str("author"),
                text = c.str("text"),
                avatar = c.strOrNull("avatar"),
                likes = c.longOrNull("likes") ?: 0L,
                replies = c.optInt("replies", 0),
                published = c.longOrNull("published"),
                pinned = c.optBoolean("pinned", false),
                hearted = c.optBoolean("hearted", false),
                creator = c.optBoolean("creator", false),
                repliesToken = c.strOrNull("repliesToken"),
            )
        },
        next = o.strOrNull("next"),
        disabled = o.optBoolean("disabled", false),
    )

    fun recs(o: JSONObject): YtRecs = YtRecs(
        recommended = o.optJSONArray("recommended").mapObjects(::video),
        because = o.optJSONArray("because").mapObjects { b ->
            val seed = b.optJSONObject("seed") ?: return@mapObjects null
            YtBecause(seed.str("id"), seed.str("title"), b.optJSONArray("videos").mapObjects(::video)).takeIf { it.videos.isNotEmpty() }
        },
        channels = o.optJSONArray("channels").mapObjects(::channel),
        seeds = o.optInt("seeds", 0),
    )

    fun channelPage(o: JSONObject): YtChannelPage? {
        val ch = channel(o) ?: return null
        return YtChannelPage(ch, o.str("description"), o.optJSONArray("videos").mapObjects(::video))
    }

    fun videoInfo(o: JSONObject): YtVideoInfo? {
        val id = o.str("id").takeIf { it.length == 11 } ?: return null
        return YtVideoInfo(
            id = id,
            title = o.str("title"),
            channel = o.str("channel"),
            channelId = o.str("channelId"),
            description = o.str("description"),
            durationMs = o.secondsMs("duration") ?: 0L,
            views = o.longOrNull("views"),
            published = o.longOrNull("published"),
            thumb = o.strOrNull("thumb"),
            chapters = o.optJSONArray("chapters").mapObjects { c ->
                YtChapter(c.str("title"), c.secondsMs("start") ?: 0L, c.secondsMs("end") ?: 0L)
            },
            sponsor = o.optJSONArray("sponsor").mapObjects { s ->
                val start = s.secondsMs("start") ?: return@mapObjects null
                val end = s.secondsMs("end") ?: return@mapObjects null
                YtSegment(start, end, s.str("category"))
            },
            hls = o.str("hls").ifBlank { "/yt/$id/master.m3u8" },
            positionMs = o.secondsMs("position") ?: 0L,
            subscribed = o.optBoolean("subscribed", false),
            storyboard = o.optJSONObject("storyboard")?.let { sb ->
                val fps = sb.optDouble("fps")
                YtStoryboard(
                    width = sb.optInt("width"), height = sb.optInt("height"), columns = sb.optInt("columns"),
                    rows = sb.optInt("rows"), fps = fps, frames = sb.optInt("frames"), sheets = sb.optInt("sheets"),
                    url = sb.str("url"),
                ).takeIf {
                    it.width > 0 && it.height > 0 && it.columns > 0 && it.rows > 0 && !fps.isNaN() && fps > 0 &&
                        it.sheets > 0 && "{n}" in it.url
                }
            },
        )
    }

    /** "1,2 mn visningar · för 3 dagar sedan" och liknande, som på YouTube. */
    fun views(n: Long?): String? = when {
        n == null -> null
        n >= 1_000_000 -> String.format(java.util.Locale("sv", "SE"), "%.1f mn visningar", n / 1_000_000.0)
        n >= 1_000 -> "${n / 1_000} tn visningar"
        n == 1L -> "1 visning"
        else -> "$n visningar"
    }

    fun ago(ms: Long?, now: Long = System.currentTimeMillis()): String? {
        if (ms == null || ms <= 0) return null
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        val units = listOf(
            Triple(31_536_000L, "år", "år"), Triple(2_592_000L, "månad", "månader"), Triple(604_800L, "vecka", "veckor"),
            Triple(86_400L, "dag", "dagar"), Triple(3_600L, "timme", "timmar"), Triple(60L, "minut", "minuter"),
        )
        for ((sec, one, many) in units) {
            val n = s / sec
            if (n >= 1) return "för $n ${if (n == 1L) one else many} sedan"
        }
        return "nyss"
    }
}
