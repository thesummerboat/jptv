package se.jptv.player

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.jptv.plex.PlexClient
import java.io.Closeable
import java.io.File

/**
 * Förhandsbilder när man spolar, från bilderna Plex genererar ("video preview thumbnails").
 * Provar först att hämta en bild i taget från servern; fungerar inte det laddas hela
 * BIF-filen ner till cachen och bilderna läses ur den.
 */
class PreviewThumbnails(
    private val context: Context,
    private val plex: PlexClient,
    private val partId: Long,
) : Closeable {
    private enum class Mode { UNKNOWN, SINGLE, BIF, NONE }

    private val lock = Mutex()
    private var mode = Mode.UNKNOWN
    private var bif: BifFile? = null
    private var bifFile: File? = null
    private val cache = LinkedHashMap<Long, ImageBitmap>()

    /** Bilden närmast tiden [ms], eller null om det inte finns några bilder. */
    suspend fun frame(ms: Long): ImageBitmap? = lock.withLock {
        if (mode == Mode.UNKNOWN) detect(ms)
        val key = when (mode) {
            Mode.BIF -> bif?.frameStart(ms) ?: return@withLock null
            Mode.SINGLE -> ms / 5_000 * 5_000
            else -> return@withLock null
        }
        cache[key]?.let { return@withLock it }
        val bytes = when (mode) {
            Mode.BIF -> withContext(Dispatchers.IO) { bif?.frame(ms) }
            Mode.SINGLE -> plex.fetchBytes("/library/parts/$partId/indexes/sd/$key")
            else -> null
        } ?: return@withLock null
        val image = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } ?: return@withLock null
        cache[key] = image
        if (cache.size > 16) cache.remove(cache.keys.first())
        image
    }

    private suspend fun detect(ms: Long) {
        mode = Mode.NONE
        val single = runCatching { plex.fetchBytes("/library/parts/$partId/indexes/sd/${ms / 5_000 * 5_000}") }.getOrNull()
        if (single != null && single.size > 100 && isJpeg(single)) {
            mode = Mode.SINGLE
            return
        }
        val file = File(context.cacheDir, "preview_$partId.bif")
        val ok = runCatching { plex.downloadToFile("/library/parts/$partId/indexes/sd", file) }.getOrDefault(false)
        if (!ok) {
            file.delete()
            return
        }
        bif = withContext(Dispatchers.IO) { runCatching { BifFile(file) }.getOrNull() }
        if (bif != null) {
            bifFile = file
            mode = Mode.BIF
        } else {
            file.delete()
        }
    }

    private fun isJpeg(b: ByteArray) = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    override fun close() {
        runCatching { bif?.close() }
        bifFile?.delete()
        cache.clear()
    }
}
