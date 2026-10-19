package se.jptv.plex

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Hemskärmen sparad på disk, så att den kan visas direkt när appen startar medan färsk
 * data hämtas från Plex i bakgrunden. Bara det som behövs för att visa och öppna titlar.
 */
object HomeCache {
    data class Snapshot(
        val sections: List<PlexSection>,
        val rows: List<PlexHomeRow>,
        val leadRows: Int,
        val genres: List<Pair<PlexSection, List<PlexFilterOption>>>,
    )

    fun write(file: File, snapshot: Snapshot) {
        val o = JSONObject()
        o.put("sections", JSONArray(snapshot.sections.map { section(it) }))
        o.put("lead", snapshot.leadRows)
        o.put("rows", JSONArray(snapshot.rows.map { row ->
            JSONObject()
                .put("title", row.title)
                .put("labels", JSONObject(row.labels))
                .put("items", JSONArray(row.items.map { item(it) }))
        }))
        o.put("genres", JSONArray(snapshot.genres.map { (s, g) ->
            JSONObject().put("section", section(s)).put("genres", JSONArray(g.map { JSONObject().put("id", it.id).put("title", it.title) }))
        }))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun read(file: File): Snapshot? {
        if (!file.exists()) return null
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val sections = o.optJSONArray("sections").objects().map { parseSection(it) }
        val rows = o.optJSONArray("rows").objects().map { r ->
            val labels = HashMap<String, String>()
            r.optJSONObject("labels")?.let { l -> l.keys().forEach { k -> labels[k] = l.optString(k) } }
            PlexHomeRow(r.optString("title"), r.optJSONArray("items").objects().map { parseItem(it) }, labels)
        }
        val genres = o.optJSONArray("genres").objects().map { g ->
            parseSection(g.getJSONObject("section")) to
                g.optJSONArray("genres").objects().map { PlexFilterOption(it.optString("id"), it.optString("title")) }
        }
        return Snapshot(sections, rows, o.optInt("lead"), genres)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun section(s: PlexSection) = JSONObject().put("key", s.key).put("title", s.title).put("type", s.type)

    private fun parseSection(o: JSONObject) = PlexSection(o.optString("key"), o.optString("title"), o.optString("type"))

    private fun item(i: PlexItem): JSONObject = JSONObject().apply {
        put("ratingKey", i.ratingKey)
        put("type", i.type)
        put("title", i.title)
        i.showTitle?.let { put("showTitle", it) }
        i.season?.let { put("season", it) }
        i.episode?.let { put("episode", it) }
        i.year?.let { put("year", it) }
        i.summary?.let { put("summary", it) }
        i.thumb?.let { put("thumb", it) }
        i.art?.let { put("art", it) }
        put("durationMs", i.durationMs)
        put("viewOffsetMs", i.viewOffsetMs)
        put("watched", i.watched)
        i.resolution?.let { put("resolution", it) }
        i.videoCodec?.let { put("videoCodec", it) }
        i.audioCodec?.let { put("audioCodec", it) }
        i.showKey?.let { put("showKey", it) }
        i.logo?.let { put("logo", it) }
        i.imdbId?.let { put("imdbId", it) }
        i.sectionKey?.let { put("sectionKey", it) }
        i.childCount?.let { put("childCount", it) }
        i.viewedLeafCount?.let { put("viewedLeafCount", it) }
        i.audioChannels?.let { put("audioChannels", it) }
        put("genres", JSONArray(i.genres))
        put("ratings", JSONArray(i.ratings.map { JSONObject().put("s", it.source).put("t", it.text).put("v", it.value) }))
    }

    private fun parseItem(o: JSONObject): PlexItem {
        fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
        fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
        return PlexItem(
            ratingKey = o.optString("ratingKey"),
            type = o.optString("type"),
            title = o.optString("title"),
            showTitle = str("showTitle"),
            season = int("season"),
            episode = int("episode"),
            year = int("year"),
            summary = str("summary"),
            thumb = str("thumb"),
            art = str("art"),
            durationMs = o.optLong("durationMs"),
            viewOffsetMs = o.optLong("viewOffsetMs"),
            watched = o.optBoolean("watched"),
            partKey = null,
            videoCodec = str("videoCodec"),
            audioCodec = str("audioCodec"),
            resolution = str("resolution"),
            showKey = str("showKey"),
            logo = str("logo"),
            imdbId = str("imdbId"),
            sectionKey = str("sectionKey"),
            childCount = int("childCount"),
            viewedLeafCount = int("viewedLeafCount"),
            audioChannels = int("audioChannels"),
            genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            ratings = o.optJSONArray("ratings").objects().map { PlexRating(it.optString("s"), it.optString("t"), it.optDouble("v")) },
        )
    }
}
