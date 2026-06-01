package com.sound2inat.app.ui.review

import android.os.SystemClock
import android.util.Log
import com.sound2inat.audio.SpectrogramDisplayPlane
import com.sound2inat.audio.SpectrogramPalette
import com.sound2inat.audio.SpectrogramPngRenderer
import com.sound2inat.audio.SpectrogramPreview
import com.sound2inat.audio.WavPcmReader
import java.io.File

/**
 * Builds (or loads cached) waveform and spectrogram artifacts for a draft.
 * Decoupled from Android's `Bitmap` so the VM can be unit-tested on the JVM.
 *
 * Production wiring: [ProductionVisualsProvider] streams the WAV block-by-block
 * via [streamMono16] into [SpectrogramPngRenderer.renderStreaming], then computes
 * per-column peaks via a downsampled envelope. PNG generation is deferred until
 * submission/export.
 */
fun interface VisualsProvider {
    suspend fun build(
        audioPath: String,
        draftId: String,
        filesDir: File,
        config: ReviewSpectrogramConfig,
    ): Visuals
}

/**
 * Output of [VisualsProvider]. [displayPlane] is the reusable normalized and
 * smoothed spectrogram data; [spectrogramPreview] is the colorized preview
 * derived from that plane; [waveformPeaks] is the interleaved (min, max)
 * envelope used by the Compose waveform canvas.
 */
data class Visuals(
    val displayPlane: SpectrogramDisplayPlane = SpectrogramDisplayPlane(
        width = 0,
        height = 0,
        values = emptyArray(),
    ),
    val spectrogramPreview: SpectrogramPreview = SpectrogramPreview(
        width = 0,
        height = 0,
        argb = IntArray(0),
    ),
    val waveformPeaks: FloatArray = FloatArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Visuals) return false
        return displayPlane == other.displayPlane &&
            spectrogramPreview == other.spectrogramPreview &&
            waveformPeaks.contentEquals(other.waveformPeaks)
    }

    override fun hashCode(): Int =
        (((displayPlane.hashCode() * HASH_PRIME) + spectrogramPreview.hashCode()) * HASH_PRIME) +
            waveformPeaks.contentHashCode()

    companion object {
        private const val HASH_PRIME = 31
    }
}

/** Default sink used in tests; never builds anything. */
internal object NoopVisualsProvider : VisualsProvider {
    override suspend fun build(
        audioPath: String,
        draftId: String,
        filesDir: File,
        config: ReviewSpectrogramConfig,
    ): Visuals = error("NoopVisualsProvider should not be invoked; supply a real VisualsProvider")
}

/**
 * Production [VisualsProvider]. Streams the WAV off disk so the preview can be
 * built without loading the full file into memory, then returns an in-memory
 * preview plus cached waveform peaks.
 */
internal class ProductionVisualsProvider(
    private val waveformPeaksCache: ReviewWaveformPeaksCache = ReviewWaveformPeaksCache(),
) : VisualsProvider {

    override suspend fun build(
        audioPath: String,
        draftId: String,
        filesDir: File,
        config: ReviewSpectrogramConfig,
    ): Visuals {
        val startedAt = SystemClock.elapsedRealtime()
        val input = File(audioPath)
        Log.d("ReviewVisuals", "provider-start draft=$draftId file=${input.name}")
        val info = WavPcmReader.readHeader(input).let {
            Mono16Info(sampleRateHz = it.sampleRateHz, totalSamples = it.totalSamples)
        }
        val palette = config.palette ?: SpectrogramPalette.INK
        val contrastDb = config.gainDb ?: 0f
        val renderStart = SystemClock.elapsedRealtime()
        val rendered = SpectrogramPngRenderer.renderStreaming(
            sampleRateHz = info.sampleRateHz,
            palette = palette,
            contrastDb = contrastDb,
        ) { onBlock ->
            WavPcmReader.stream(input) { chunk, _ ->
                onBlock(FloatArray(chunk.size) { i -> chunk[i] / Short.MAX_VALUE.toFloat() })
            }
        }
        Log.d(
            "ReviewVisuals",
            "render-done draft=$draftId elapsed=${SystemClock.elapsedRealtime() - renderStart}ms preview=${rendered.preview.width}x${rendered.preview.height}",
        )
        val peaksStarted = SystemClock.elapsedRealtime()
        val peaks = waveformPeaksCache.getOrCreate(input, draftId, filesDir) {
            buildWaveformPeaks(input, info)
        }
        Log.d(
            "ReviewVisuals",
            "peaks-ready draft=$draftId elapsed=${SystemClock.elapsedRealtime() - peaksStarted}ms count=${peaks.size}"
        )
        Log.i("ReviewVisuals", "provider-done draft=$draftId total=${SystemClock.elapsedRealtime() - startedAt}ms")
        return Visuals(
            displayPlane = rendered.displayPlane,
            spectrogramPreview = rendered.preview,
            waveformPeaks = peaks,
        )
    }
}

internal data class Mono16Info(
    val sampleRateHz: Int,
    val totalSamples: Long,
)

internal fun buildWaveformPeaks(file: File, info: Mono16Info): FloatArray {
    if (info.totalSamples <= 0L) return FloatArray(0)
    val width =
        minOf(WaveformBitmap.DEFAULT_TARGET_WIDTH, info.totalSamples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    if (width <= 0) return FloatArray(0)
    val lows = FloatArray(width) { Float.POSITIVE_INFINITY }
    val highs = FloatArray(width) { Float.NEGATIVE_INFINITY }
    WavPcmReader.stream(file) { chunk, startSample ->
        for (i in chunk.indices) {
            val sampleIndex = startSample + i
            val bucket = ((sampleIndex * width) / info.totalSamples).toInt().coerceIn(0, width - 1)
            val value = chunk[i] / Short.MAX_VALUE.toFloat()
            if (value < lows[bucket]) lows[bucket] = value
            if (value > highs[bucket]) highs[bucket] = value
        }
    }
    return FloatArray(width * 2) { idx ->
        val bucket = idx / 2
        if (idx % 2 == 0) {
            lows[bucket].takeIf { it.isFinite() } ?: 0f
        } else {
            highs[bucket].takeIf { it.isFinite() } ?: 0f
        }
    }
}
