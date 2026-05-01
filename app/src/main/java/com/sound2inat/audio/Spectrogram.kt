package com.sound2inat.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Streaming STFT. Buffers incoming samples; emits one Hann-windowed dB column
 * per [hopSize] samples consumed. Each column has [fftSize] / 2 + 1 non-redundant
 * bins, in dB clamped to [[DB_FLOOR], 0].
 *
 * NOT thread-safe — one instance per audio stream.
 */
class Spectrogram(
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val sampleRateHz: Int = 48_000,
) {
    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) { "fftSize must be power of 2" }
        require(hopSize in 1..fftSize)
    }

    private val window = FloatArray(fftSize) { i ->
        // Hann window
        (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }
    private val buffer = FloatArray(fftSize)
    private var filled = 0

    private val scratch = FloatArray(fftSize * 2)

    fun process(block: FloatArray): List<FloatArray> {
        if (block.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(2)
        var idx = 0
        while (idx < block.size) {
            val take = (fftSize - filled).coerceAtMost(block.size - idx)
            System.arraycopy(block, idx, buffer, filled, take)
            filled += take
            idx += take
            if (filled == fftSize) {
                out += computeColumn()
                // Slide left by hopSize so next column overlaps by fftSize - hopSize.
                System.arraycopy(buffer, hopSize, buffer, 0, fftSize - hopSize)
                filled = fftSize - hopSize
            }
        }
        return out
    }

    private fun computeColumn(): FloatArray {
        scratch.fill(0f)
        for (i in 0 until fftSize) scratch[2 * i] = buffer[i] * window[i]
        Fft.inPlace(scratch, fftSize, inverse = false)
        val out = FloatArray(fftSize / 2 + 1)
        // Normalise so a unit-amplitude sinusoid yields ~ -6 dB peak (after the
        // Hann window halves energy). Without this every non-trivial signal
        // saturates at the 0 dB ceiling and `maxByOrNull` returns the first of
        // many equal bins instead of the spectral peak.
        val norm = 1f / fftSize
        for (i in out.indices) {
            val re = scratch[2 * i]; val im = scratch[2 * i + 1]
            val mag = sqrt((re * re + im * im).toDouble()).toFloat() * norm
            val db = 20f * (ln(mag + 1e-10f) / LN10).toFloat()
            out[i] = db.coerceIn(DB_FLOOR, 0f)
        }
        return out
    }

    companion object {
        const val DB_FLOOR = -80f
        private const val LN10 = 2.302585092994046f
    }
}
