package com.sound2inat.inference

import com.sound2inat.audio.WavWindowReader
import kotlin.math.floor

/**
 * Per-window linear-interpolation resampler that reads native samples from a
 * [WavWindowReader] on demand. Behaviour is byte-exact identical to applying
 * [Resampler.resample] to the entire native buffer and slicing afterwards —
 * both use the mapping `srcPos = j * ratio` with `i1 = (i0+1).coerceAtMost(totalNative-1)`.
 *
 * Extracted to `internal` (not `private`) so the streaming path can be tested
 * directly against the legacy whole-file path for bit-exact equivalence.
 */
internal object StreamingResampler {
    fun readResampledWindow(
        reader: WavWindowReader,
        ratio: Double,
        resampledStart: Int,
        win: Int,
    ): FloatArray {
        val totalNative = reader.totalSamples
        if (ratio == 1.0) {
            val native = reader.readNative(resampledStart, win)
            return FloatArray(win) { i -> native[i] / Short.MAX_VALUE.toFloat() }
        }
        // Native range needed to cover resampled indices [resampledStart, resampledStart + win).
        val firstSrc = resampledStart * ratio
        val lastSrc = (resampledStart + win - 1) * ratio
        val nativeStart = floor(firstSrc).toInt()
        val nativeLast = floor(lastSrc).toInt() + 1
        val nativeCount = (nativeLast - nativeStart + 1).coerceAtLeast(1)
        val native = reader.readNative(nativeStart, nativeCount)
        val out = FloatArray(win)
        val maxNativeIndex = (totalNative - 1).coerceAtLeast(0)
        val scale = Short.MAX_VALUE.toFloat()
        for (j in 0 until win) {
            val srcPos = (resampledStart + j) * ratio
            val i0Global = floor(srcPos).toInt()
            val i1Global = (i0Global + 1).coerceAtMost(maxNativeIndex)
            val i0Local = i0Global - nativeStart
            val i1Local = i1Global - nativeStart
            val v0 = native[i0Local].toInt()
            val v1 = native[i1Local].toInt()
            val frac = srcPos - i0Global
            val interp = v0 + (v1 - v0) * frac
            val clamped = interp.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
            out[j] = clamped.toInt().toShort().toFloat() / scale
        }
        return out
    }
}
