package se.jptv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.util.WeakHashMap

/**
 * Buffert för filmer och avsnitt.
 *
 * ExoPlayers standard slutar ladda vid ungefär 140 MB, vilket bara räcker 10–15 sekunder för en
 * 4K-remux (60–100 Mbit/s). Ett kort avbrott i wifi:t ger då hack. Här får bufferten växa till
 * ungefär halva minnet appen får använda (som mest 400 MB) och upp till tre minuter.
 * Live-TV använder standarden: den kan ändå inte buffra före sändningen.
 */
@OptIn(UnstableApi::class)
internal object PlayerBuffer {
    const val MIN_BUFFER_MS = 60_000
    const val MAX_BUFFER_MS = 180_000
    const val PLAYBACK_MS = 2_500
    /** Efter ett hack: vänta in lite mer innan uppspelningen fortsätter, så att det inte hackar igen direkt. */
    const val AFTER_REBUFFER_MS = 6_000

    private const val MB = 1024L * 1024L
    private const val FLOOR = 64 * MB
    private const val CEILING = 400 * MB

    /** Hur många byte bufferten får ta, utifrån hur mycket minne appen får använda. */
    fun targetBytes(maxHeapBytes: Long): Int = (maxHeapBytes / 2).coerceIn(FLOOR, CEILING).toInt()

    class Setup(val loadControl: DefaultLoadControl, val allocator: DefaultAllocator, val targetBytes: Int)

    fun create(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Setup {
        val target = targetBytes(maxHeapBytes)
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val control = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_MS, AFTER_REBUFFER_MS)
            .setTargetBufferBytes(target)
            // Byte-taket gäller alltid, så att minnet aldrig tar slut.
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return Setup(control, allocator, target)
    }

    private val setups = WeakHashMap<Player, Setup>()

    fun register(player: Player, setup: Setup) {
        synchronized(setups) { setups[player] = setup }
    }

    /** Använt och tillåtet minne (byte) för spelarens buffert, om den har en egen. */
    fun usage(player: Player): Pair<Long, Long>? {
        val setup = synchronized(setups) { setups[player] } ?: return null
        return setup.allocator.totalBytesAllocated.toLong() to setup.targetBytes.toLong()
    }
}
