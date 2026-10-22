package se.jptv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Dialogförstärkning och nattläge, för alla spelare i appen.
 *
 * Det fungerar bara på ljud som boxen själv avkodar. Dolby/DTS som skickas vidare orört
 * (bitstream) till förstärkaren eller TV:n kan appen inte ändra i. Därför avkodas Dolby
 * här när någon av funktionerna är på, om enheten har en avkodare för formatet.
 */
@OptIn(UnstableApi::class)
object AudioBoost {
    @Volatile var dialogue = false
    @Volatile var night = false

    val active: Boolean get() = dialogue || night

    /** Senast ljud faktiskt bearbetades (för att visa om det verkar på det som spelas). */
    @Volatile var lastProcessedAt = 0L
        internal set

    /** Om förbättringen verkar på ljudet just nu. */
    fun working(now: Long = System.currentTimeMillis()): Boolean = active && now - lastProcessedAt < 2_000

    private val ENCODED = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_AC4,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS_EXPRESS,
        MimeTypes.AUDIO_DTS_X,
        MimeTypes.AUDIO_TRUEHD,
    )

    /** Ljud som kan gå orört till förstärkaren (bitstream). */
    fun isEncoded(mime: String?): Boolean = mime != null && mime in ENCODED

    private val decoders = ConcurrentHashMap<String, Boolean>()

    /**
     * Om enheten har en avkodare som klarar just det här formatet (kanaler, samplingsfrekvens),
     * så att ljudet kan bearbetas här. Annars skulle spåret se ospelbart ut och Plex fick koda om.
     */
    fun canDecode(format: Format?): Boolean {
        val mime = format?.sampleMimeType ?: return false
        val key = "$mime/${format.channelCount}/${format.sampleRate}"
        return decoders.getOrPut(key) {
            val candidates = if (mime == MimeTypes.AUDIO_E_AC3_JOC) listOf(mime, MimeTypes.AUDIO_E_AC3) else listOf(mime)
            candidates.any { m ->
                try {
                    MediaCodecUtil.getDecoderInfos(m, false, false).any { info ->
                        try {
                            info.isFormatSupported(format.buildUpon().setSampleMimeType(m).build())
                        } catch (e: MediaCodecUtil.DecoderQueryException) {
                            false
                        }
                    }
                } catch (e: MediaCodecUtil.DecoderQueryException) {
                    false
                }
            }
        }
    }

    /** Om spelaren ska avkoda formatet själv i stället för att skicka det vidare. */
    fun decodeHere(format: Format?): Boolean = active && isEncoded(format?.sampleMimeType) && canDecode(format)

    /**
     * Om bytet av inställning kräver att filen laddas om: ljudet går orört vidare men borde nu
     * avkodas här (eller tvärtom).
     */
    fun needsReload(format: Format?, wasActive: Boolean): Boolean =
        wasActive != active && isEncoded(format?.sampleMimeType) && canDecode(format)
}

/** Ljudutgången: säger nej till bitstream för Dolby/DTS när ljudet ska bearbetas här. */
@OptIn(UnstableApi::class)
internal class BoostAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (AudioBoost.decodeHere(format)) return AudioSink.SINK_FORMAT_UNSUPPORTED
        return super.getFormatSupport(format)
    }
}

/** Kör [BoostDsp] på avkodat ljud (16 bitar eller flyttal). Annars går ljudet igenom orört. */
@OptIn(UnstableApi::class)
internal class BoostAudioProcessor : BaseAudioProcessor() {
    private var dsp: BoostDsp? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount <= 0 || inputAudioFormat.sampleRate <= 0) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        val format = inputAudioFormat
        dsp = if (format.channelCount > 0 && format.sampleRate > 0) BoostDsp(format.sampleRate, format.channelCount) else null
    }

    override fun onReset() {
        dsp = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val d = dsp
        if (d != null) {
            d.dialogue = AudioBoost.dialogue
            d.night = AudioBoost.night
        }
        if (d == null || !d.enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        // PCM ligger i minnet i enhetens egen byteordning.
        val src = inputBuffer.slice().order(ByteOrder.nativeOrder())
        val channels = inputAudioFormat.channelCount
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (float) 4 else 2
        val samples = size / bytesPerSample
        val frames = samples / channels
        val used = frames * channels
        if (scratch.size < used) scratch = FloatArray(used)
        if (float) {
            for (i in 0 until used) scratch[i] = src.getFloat(i * 4)
        } else {
            for (i in 0 until used) scratch[i] = src.getShort(i * 2) / 32768f
        }
        d.process(scratch, frames)
        if (float) {
            for (i in 0 until used) out.putFloat(scratch[i])
        } else {
            for (i in 0 until used) {
                val v = (scratch[i] * 32767f).toInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        }
        // En eventuell ofullständig bildruta i slutet går igenom orörd.
        for (i in used * bytesPerSample until size) out.put(src.get(i))
        inputBuffer.position(inputBuffer.limit())
        out.flip()
        AudioBoost.lastProcessedAt = System.currentTimeMillis()
    }
}
