package se.jptv.player

/** Flyttar alla tider i en SRT-undertext. Positiv förskjutning = undertexten visas senare. */
object SubtitleSync {
    private val timeRegex = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""")

    fun shiftSrt(srt: String, offsetMs: Long): String {
        if (offsetMs == 0L) return srt
        return srt.lineSequence().joinToString("\n") { line ->
            if (!line.contains("-->")) {
                line
            } else {
                timeRegex.replace(line) { m ->
                    val (h, min, s, ms) = m.destructured
                    val total = h.toLong() * 3_600_000 + min.toLong() * 60_000 + s.toLong() * 1_000 + ms.toLong()
                    format((total + offsetMs).coerceAtLeast(0))
                }
            }
        }
    }

    private fun format(totalMs: Long): String {
        val h = totalMs / 3_600_000
        val m = (totalMs % 3_600_000) / 60_000
        val s = (totalMs % 60_000) / 1_000
        val ms = totalMs % 1_000
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    /** "+0,5 s" / "−1,2 s" för visning. */
    fun label(offsetMs: Long): String {
        val sign = if (offsetMs > 0) "+" else if (offsetMs < 0) "−" else ""
        val abs = kotlin.math.abs(offsetMs)
        return "$sign${abs / 1000},${(abs % 1000) / 100} s"
    }
}
