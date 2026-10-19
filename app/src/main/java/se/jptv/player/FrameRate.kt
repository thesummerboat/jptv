package se.jptv.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** Ett skärmläge: upplösning och uppdateringsfrekvens. */
data class ModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Väljer skärmläge efter filmens bildfrekvens, så att bilden inte rycker:
 * 23,976 → 23,976 Hz, 25 → 50 Hz, 29,97 → 59,94 Hz, 50 → 50 Hz osv. Upplösningen behålls.
 */
object FrameRateMatcher {
    /** Antalet skärmuppdateringar per bildruta om läget passar (1, 2 …), annars null. */
    fun multiple(fps: Float, refreshRate: Float): Int? {
        val k = (refreshRate / fps).roundToInt()
        if (k < 1 || k > 4) return null
        val tolerance = 0.01f * k + fps * k * 0.0005f
        return if (abs(refreshRate - fps * k) <= tolerance) k else null
    }

    // 25 och 30 Hz flimrar och klaras inte av alla TV-apparater; 24 Hz är däremot det normala för film.
    private fun lowRefresh(fps: Float, m: ModeInfo) = fps >= 24.5f && m.refreshRate < 47f

    /** true om nuvarande läge redan passar bildfrekvensen. */
    fun fits(fps: Float, current: ModeInfo): Boolean =
        multiple(fps, current.refreshRate) != null && !lowRefresh(fps, current)

    /**
     * Läget som ska väljas, eller null om nuvarande läge redan passar (eller inget passar).
     * Samma upplösning i första hand; finns inget passande läge där tas den högsta upplösning
     * som har ett (t.ex. 1080p @ 60 Hz → 4K @ 23,976 Hz).
     */
    fun choose(fps: Float, current: ModeInfo, modes: List<ModeInfo>): ModeInfo? {
        if (fps < 10f || fps > 121f) return null
        if (fits(fps, current)) return null
        fun pick(list: List<ModeInfo>): ModeInfo? = list
            .mapNotNull { m -> multiple(fps, m.refreshRate)?.let { k -> m to k } }
            .sortedWith(compareBy({ if (lowRefresh(fps, it.first)) 1 else 0 }, { -it.first.width * it.first.height }, { it.second }))
            .firstOrNull()?.first
        val sameRes = pick(modes.filter { it.width == current.width && it.height == current.height })
        val best = sameRes ?: pick(modes.filter { it.width >= current.width && it.height >= current.height }) ?: return null
        return if (best.id == current.id) null else best
    }

    private val known = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f)

    /** Avrundar en uppmätt bildfrekvens till närmaste vanliga (23,976 / 25 / 50 …). */
    fun snap(fps: Float): Float {
        val nearest = known.minByOrNull { abs(it - fps) } ?: return fps
        return if (abs(nearest - fps) / nearest < 0.01f) nearest else fps
    }

    /** Bildfrekvens från tidsstämplar (mikrosekunder) för på varandra följande bildrutor. */
    fun fromTimestamps(timesUs: LongArray, count: Int): Float? {
        if (count < 8) return null
        val deltas = LongArray(count - 1) { timesUs[it + 1] - timesUs[it] }.filter { it > 0 }.sorted()
        if (deltas.isEmpty()) return null
        val median = deltas[deltas.size / 2]
        return snap(1_000_000f / median)
    }
}
