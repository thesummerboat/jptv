package se.jptv.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.jptv.web.JptvWeb
import se.jptv.web.YtStoryboard

/**
 * Förhandsbilder när man spolar i en YouTube-video: rutor ur YouTubes storyboard-ark (jpg med
 * rader × kolumner små bilder), hämtade via JPTV Web. Några ark och bilder sparas i minnet.
 */
internal class YtPreview(private val web: JptvWeb, private val board: YtStoryboard) {
    private val lock = Mutex()
    private val sheets = LinkedHashMap<Int, Bitmap>()
    private val frames = LinkedHashMap<YtStoryboard.Spot, ImageBitmap>()

    /** Bilden för tiden [ms], eller null om den inte går att hämta. */
    suspend fun frame(ms: Long): ImageBitmap? = lock.withLock {
        val spot = board.spot(ms)
        frames[spot]?.let { return@withLock it }
        val sheet = sheets[spot.sheet] ?: run {
            val bytes = web.bytes(board.sheetPath(spot.sheet)) ?: return@withLock null
            val decoded = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                ?: return@withLock null
            sheets[spot.sheet] = decoded
            if (sheets.size > 3) sheets.remove(sheets.keys.first())
            decoded
        }
        // Rutans storlek räknas ur arket (sista arket kan ha färre rader).
        val w = sheet.width / board.columns
        val h = (w.toLong() * board.height / board.width).toInt()
        val x = spot.column * w
        val y = spot.row * h
        if (w <= 0 || h <= 0 || x + w > sheet.width || y + h > sheet.height) return@withLock null
        val image = withContext(Dispatchers.Default) { Bitmap.createBitmap(sheet, x, y, w, h).asImageBitmap() }
        frames[spot] = image
        if (frames.size > 40) frames.remove(frames.keys.first())
        image
    }
}
