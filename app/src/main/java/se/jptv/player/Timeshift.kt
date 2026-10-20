package se.jptv.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import se.jptv.iptv.Channel
import se.jptv.iptv.IptvRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Pausa live-TV. När man pausar stängs spelarens ström och kanalen spelas i stället in till en fil på
 * TV:n – fortfarande en enda anslutning hos leverantören. Spelaren läser sedan filen medan den växer,
 * så att man kan fortsätta där man pausade, spola ±30 s och hoppa tillbaka till live.
 */
object Timeshift {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Strömmar med HLS (.m3u8) kan ExoPlayer pausa själv inom kanalens fönster; bara de andra spelas in. */
    fun needsRecording(channel: Channel): Boolean = !channel.url.contains(".m3u8", ignoreCase = true)

    fun start(context: Context, channel: Channel): LiveRecorder {
        val file = File(context.cacheDir, "timeshift.ts")
        file.delete()
        // Lämna minst 800 MB ledigt på TV:n; högst 4 GB (ca en timme i HD).
        val free = context.cacheDir.usableSpace
        val max = (free - 800L * 1024 * 1024).coerceIn(50L * 1024 * 1024, 4L * 1024 * 1024 * 1024)
        return LiveRecorder(
            client = client,
            file = file,
            url = channel.url,
            userAgent = channel.userAgent ?: IptvRepository.USER_AGENT,
            referrer = channel.referrer,
            maxBytes = max,
        )
    }

    /** Uppspelning ur inspelningen från en viss plats i filen. */
    @OptIn(UnstableApi::class)
    fun source(recorder: LiveRecorder, startOffset: Long): MediaSource =
        ProgressiveMediaSource.Factory { GrowingFileDataSource(recorder, startOffset) }
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(recorder.file)))

    fun offsetFor(behindMs: Long, written: Long, bytesPerSecond: Double): Long? =
        TimeshiftMath.offsetFor(behindMs, written, bytesPerSecond)
}

/** Läser kanalen till en fil i en egen tråd tills den stoppas, tar slut eller når maxstorleken. */
class LiveRecorder(
    private val client: OkHttpClient,
    val file: File,
    private val url: String,
    private val userAgent: String,
    private val referrer: String?,
    private val maxBytes: Long,
) {
    @Volatile
    var written = 0L
        private set

    @Volatile
    var finished = false
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    private var stopped = false

    @Volatile
    private var call: Call? = null

    private val startedAt = SystemClock.elapsedRealtime()

    init {
        Thread({ record() }, "jptv-timeshift").apply {
            isDaemon = true
            start()
        }
    }

    /** Byte per sekund hittills, för att räkna om tid till plats i filen (0 de första sekunderna). */
    val bytesPerSecond: Double
        get() {
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            return if (seconds < 2.0) 0.0 else written / seconds
        }

    private fun record() {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .apply { referrer?.let { header("Referer", it) } }
                .build()
            val c = client.newCall(request)
            call = c
            if (stopped) return
            c.execute().use { response ->
                if (!response.isSuccessful) {
                    error = "HTTP ${response.code}"
                    return
                }
                val input = response.body?.byteStream() ?: return
                FileOutputStream(file).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (!stopped) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written >= maxBytes) break
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopped) error = e.message ?: e.javaClass.simpleName
        } finally {
            finished = true
        }
    }

    /** Stänger anslutningen och tar bort filen (läsaren kan ha den öppen; det går bra). */
    fun stop() {
        stopped = true
        call?.cancel()
        file.delete()
    }
}

/**
 * Läser inspelningsfilen medan den växer: finns inget nytt än väntar den, som en direktsänd ström.
 * Slut först när inspelningen är klar och allt är läst.
 */
@OptIn(UnstableApi::class)
class GrowingFileDataSource(
    private val recorder: LiveRecorder,
    private val startOffset: Long,
) : BaseDataSource(false) {
    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        var waited = 0
        while (!recorder.file.exists()) {
            if (recorder.finished || waited > 15_000) throw IOException("Inspelningen startade inte (${recorder.error ?: "okänt fel"})")
            sleep()
            waited += 20
        }
        val f = RandomAccessFile(recorder.file, "r")
        file = f
        position = startOffset + dataSpec.position
        f.seek(position)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val f = file ?: throw IOException("Inspelningen är inte öppen")
        while (true) {
            val available = recorder.written - position
            if (available > 0) {
                val n = f.read(buffer, offset, minOf(length.toLong(), available).toInt())
                if (n > 0) {
                    position += n
                    bytesTransferred(n)
                    return n
                }
            }
            if (recorder.finished && recorder.written <= position) return C.RESULT_END_OF_INPUT
            sleep()
        }
    }

    /** Spelaren avbryter läsningen genom att störa tråden; det blir ett vanligt avbrott här. */
    private fun sleep() {
        try {
            Thread.sleep(20)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
