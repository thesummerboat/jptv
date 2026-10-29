package se.jptv.iptv

import java.util.Calendar
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone

/** En träff i TV-guiden: programmet och kanalen det går på. */
data class ProgrammeHit(val channel: Channel, val programme: Programme) {
    fun isLive(now: Long): Boolean = programme.start <= now && programme.stop > now

    /** Går nu eller börjar inom fem minuter: OK byter till kanalen i stället för att påminna. */
    fun isWatchable(now: Long): Boolean = programme.start - now <= SOON_MS && programme.stop > now

    companion object {
        const val SOON_MS = 5 * 60_000L
    }
}

/** Sök på programtitlar i guiden (Live-TV:s sök och den vanliga Sök). Ren logik, testbar. */
object ProgrammeSearch {
    /** Minst så många tecken innan guiden genomsöks. */
    const val MIN_LENGTH = 2

    /**
     * Program som inte har slutat och vars titel innehåller alla sökorden ("hockey frölunda"), i
     * tidsordning: det som går nu först, sedan det som kommer. Samma program på flera kanaler
     * (SVT1 HD, SVT1 FHD …) visas en gång, på den kanal som kommer först i [channels]; den som
     * anropar lägger favoriterna först så att det blir den man brukar titta på.
     */
    fun find(channels: List<Channel>, epg: Epg, query: String, now: Long, limit: Int = 80): List<ProgrammeHit> {
        val words = query.lowercase(Locale.ROOT).split(' ', '\t').filter { it.isNotBlank() }
        if (words.sumOf { it.length } < MIN_LENGTH) return emptyList()
        val hits = ArrayList<ProgrammeHit>()
        val seen = HashSet<String>()
        // Kanalvarianter delar samma lista i guiden: den gås bara igenom en gång.
        val scanned = IdentityHashMap<List<Programme>, Boolean>()
        for (ch in channels) {
            val list = epg.programmesFor(ch)
            if (list.isEmpty() || scanned.put(list, true) != null) continue
            for (p in list) {
                if (p.stop <= now) continue
                val title = p.title.lowercase(Locale.ROOT)
                if (words.any { it !in title }) continue
                if (seen.add("${p.start}|${p.stop}|$title")) hits += ProgrammeHit(ch, p)
            }
        }
        // Stabil sortering: vid samma starttid vinner kanalordningen (favoriterna).
        return hits.sortedBy { it.programme.start }.take(limit)
    }

    /**
     * När programmet går, för listan: "Nu · 20:00–22:00", "I dag 21:00–22:30",
     * "I morgon 06:00–07:00" eller "Lör 21:00–22:30".
     */
    fun whenText(p: Programme, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val time = "${hm(p.start, zone)}–${hm(p.stop, zone)}"
        if (p.start <= now && p.stop > now) return "Nu · $time"
        val days = dayNumber(p.start, zone) - dayNumber(now, zone)
        val day = when (days) {
            0L -> "I dag"
            1L -> "I morgon"
            else -> WEEKDAYS[Calendar.getInstance(zone).apply { timeInMillis = p.start }.get(Calendar.DAY_OF_WEEK) - 1]
        }
        return "$day $time"
    }

    private val WEEKDAYS = listOf("Sön", "Mån", "Tis", "Ons", "Tor", "Fre", "Lör")

    private fun hm(ms: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun dayNumber(ms: Long, zone: TimeZone): Long = Math.floorDiv(ms + zone.getOffset(ms), 86_400_000L)
}
