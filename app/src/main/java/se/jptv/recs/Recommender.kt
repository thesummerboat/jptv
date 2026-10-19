package se.jptv.recs

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * En titel som rekommendationerna räknar på.
 *
 * engagement: hur mycket du gillat den, utifrån tittarhistoriken. 1 = sett klart, mer vid omtittning
 * eller många avsnitt, negativt om du gav upp tidigt eller valt "Inte intresserad", 0 = inte sett.
 */
data class RecItem(
    val id: String,
    val type: String,
    val tags: List<String>,
    val engagement: Float,
    val lastViewedAt: Long,
    val unseen: Boolean,
    val rating: Float?,
    val votes: Int,
    val addedAt: Long,
    val excluded: Boolean = false,
)

/**
 * Innehållsbaserade rekommendationer, helt lokalt på TV:n:
 *  1. Varje titel blir en vektor av egenskaper (genre, regissör, manus, skådespelare, land, studio,
 *     årtionde). Ovanliga egenskaper väger mer (TF-IDF), så "Drama" inte tar över allt.
 *  2. Din smakprofil = summan av vektorerna för det du sett, viktad efter hur mycket du gillat
 *     titeln och hur nyligen du såg den (halveringstid ett halvår).
 *  3. Osedda titlar får poäng efter likhet med profilen, IMDb-kvalitet och lite för nyhet.
 *  4. Listan blandas för variation (MMR), så att inte tjugo nästan likadana filmer hamnar i rad.
 */
object Recommender {
    private val typeWeight = mapOf('g' to 1.0f, 'd' to 1.6f, 'w' to 1.1f, 'a' to 0.8f, 'c' to 0.4f, 's' to 0.4f, 'y' to 0.35f)
    private const val DAY = 24 * 60 * 60 * 1000L

    class Model internal constructor(
        val items: Map<String, RecItem>,
        val vectors: Map<String, Map<String, Float>>,
        val profiles: Map<String, Map<String, Float>>,
    )

    fun build(items: List<RecItem>, now: Long): Model {
        val byId = items.associateBy { it.id }
        val df = HashMap<String, Int>()
        for (item in items) for (t in item.tags.toSet()) df[t] = (df[t] ?: 0) + 1
        val n = items.size.toFloat().coerceAtLeast(1f)
        val vectors = items.associate { item ->
            val v = HashMap<String, Float>()
            for (t in item.tags.toSet()) {
                val count = df[t] ?: continue
                // Egenskaper som bara en titel har säger inget om likhet med andra.
                if (count < 2) continue
                val w = (typeWeight[t.firstOrNull() ?: ' '] ?: 0.5f) * ln(1f + n / count)
                v[t] = w
            }
            item.id to normalize(v)
        }
        // En profil per typ (film/serie) och en gemensam, så att seriesmak också påverkar filmer lite.
        val profiles = HashMap<String, Map<String, Float>>()
        for (type in items.map { it.type }.distinct() + "all") {
            val p = HashMap<String, Float>()
            for (item in items) {
                if (item.engagement == 0f) continue
                if (type != "all" && item.type != type) continue
                val vec = vectors[item.id] ?: continue
                val w = item.engagement * recency(item.lastViewedAt, now)
                for ((t, x) in vec) p[t] = (p[t] ?: 0f) + w * x
            }
            profiles[type] = normalize(p.filterValues { it > 0f })
        }
        return Model(byId, vectors, profiles)
    }

    /** Halveringstid ett halvår, men gamla favoriter räknas alltid lite. */
    fun recency(lastViewedAt: Long, now: Long): Float {
        if (lastViewedAt <= 0) return 0.3f
        val days = ((now - lastViewedAt).coerceAtLeast(0) / DAY).toFloat()
        return max(0.15f, 0.5f.pow(days / 180f))
    }

    /** Kvalitet utifrån IMDb: betyg över 5,5 och många röster ger plus. */
    fun quality(rating: Float?, votes: Int): Float {
        if (rating == null) return 0f
        val r = ((rating - 5.5f) / 3f).coerceIn(-0.5f, 1f)
        val confidence = min(1f, log10(votes + 1f) / 5f)
        return r * (if (votes > 0) confidence else 0.6f)
    }

    private fun freshness(addedAt: Long, now: Long): Float {
        if (addedAt <= 0) return 0f
        val days = (now - addedAt) / DAY
        return when {
            days < 30 -> 1f
            days < 120 -> 0.4f
            else -> 0f
        }
    }

    fun cosine(a: Map<String, Float>, b: Map<String, Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var dot = 0f
        for ((t, x) in small) dot += x * (large[t] ?: 0f)
        return dot
    }

    private fun normalize(v: Map<String, Float>): Map<String, Float> {
        val norm = sqrt(v.values.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm == 0f) emptyMap() else v.mapValues { it.value / norm }
    }

    /** Hur mycket smak profilen bygger på (antal titlar du gillat av typen). */
    fun signal(model: Model, type: String): Int = model.items.values.count { it.type == type && it.engagement > 0f }

    /** "Rekommenderat för dig": osedda titlar av en typ. */
    fun forYou(model: Model, type: String, now: Long, limit: Int = 20): List<String> {
        val own = model.profiles[type].orEmpty()
        val all = model.profiles["all"].orEmpty()
        if (own.isEmpty() && all.isEmpty()) return emptyList()
        val scored = model.items.values
            .filter { it.type == type && it.unseen && !it.excluded }
            .mapNotNull { c ->
                val vec = model.vectors[c.id] ?: return@mapNotNull null
                val like = 0.8f * cosine(own, vec) + 0.2f * cosine(all, vec)
                if (like <= 0.02f) return@mapNotNull null
                c.id to (0.7f * like + 0.22f * quality(c.rating, c.votes) + 0.08f * freshness(c.addedAt, now))
            }
        return diversify(model, scored, limit)
    }

    /** "Eftersom du såg X": de osedda titlar som liknar X mest. */
    fun similarTo(model: Model, seedId: String, limit: Int = 15): List<String> {
        val seed = model.items[seedId] ?: return emptyList()
        val seedVec = model.vectors[seedId] ?: return emptyList()
        val scored = model.items.values
            .filter { it.type == seed.type && it.unseen && !it.excluded && it.id != seedId }
            .mapNotNull { c ->
                val vec = model.vectors[c.id] ?: return@mapNotNull null
                val sim = cosine(seedVec, vec)
                if (sim <= 0.05f) null else c.id to (0.8f * sim + 0.2f * quality(c.rating, c.votes))
            }
        return diversify(model, scored, limit, lambda = 0.85f)
    }

    /** Titlar att bygga "Eftersom du såg"-rader på: nyligen sedda som du gillat, olika typer i första hand. */
    fun seeds(model: Model, now: Long, count: Int = 2): List<String> {
        val liked = model.items.values
            .filter { it.engagement >= 0.8f && it.lastViewedAt > 0 && now - it.lastViewedAt < 90 * DAY }
            .sortedByDescending { it.lastViewedAt }
        val picked = mutableListOf<RecItem>()
        for (item in liked) {
            if (picked.size >= count) break
            if (picked.none { it.type == item.type } || liked.none { l -> picked.none { it.type == l.type } && l !in picked }) {
                if (similarTo(model, item.id, 5).size >= 5) picked += item
            }
        }
        return picked.map { it.id }
    }

    /**
     * MMR: välj i tur och ordning det som har hög poäng men inte liknar det som redan valts för mycket.
     * lambda = 1 betyder bara poäng, lägre ger mer variation.
     */
    private fun diversify(model: Model, scored: List<Pair<String, Float>>, limit: Int, lambda: Float = 0.75f): List<String> {
        val pool = scored.sortedByDescending { it.second }.take(limit * 4).toMutableList()
        val chosen = mutableListOf<String>()
        while (chosen.size < limit && pool.isNotEmpty()) {
            var bestIndex = 0
            var best = Float.NEGATIVE_INFINITY
            for ((i, candidate) in pool.withIndex()) {
                val vec = model.vectors[candidate.first].orEmpty()
                val maxSim = chosen.maxOfOrNull { cosine(vec, model.vectors[it].orEmpty()) } ?: 0f
                val value = lambda * candidate.second - (1 - lambda) * maxSim
                if (value > best) {
                    best = value
                    bestIndex = i
                }
            }
            chosen += pool.removeAt(bestIndex).first
        }
        return chosen
    }
}
