package se.jptv.player

/** Räkning för pausa live, utan Android, så att den kan testas. */
object TimeshiftMath {
    /**
     * Plats i inspelningen för "så här långt efter live". TS-paket är 188 byte; platsen läggs på en
     * paketgräns. null om bithastigheten inte är känd än.
     */
    fun offsetFor(behindMs: Long, written: Long, bytesPerSecond: Double): Long? {
        if (bytesPerSecond <= 0.0) return null
        val raw = (written - behindMs / 1000.0 * bytesPerSecond).toLong().coerceIn(0L, written)
        return raw - raw % 188
    }

    /** Hur långt efter live en plats i filen är, i ms. */
    fun behindFor(offset: Long, written: Long, bytesPerSecond: Double): Long =
        if (bytesPerSecond <= 0.0) 0L else ((written - offset) / bytesPerSecond * 1000).toLong().coerceAtLeast(0L)
}
