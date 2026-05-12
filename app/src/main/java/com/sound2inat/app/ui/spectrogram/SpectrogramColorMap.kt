package com.sound2inat.app.ui.spectrogram

enum class SpectrogramPalette { INK, VIRIDIS }

/**
 * Shared spectrogram color utilities. Pure Kotlin — no Android framework dependency
 * so this code is usable in JVM unit tests without Android stubs.
 */
object SpectrogramColorMap {

    private const val LUT_SIZE = 256

    /**
     * Builds a Merlin-style ink LUT from [backgroundArgb] (silence = background)
     * to near-black (loud peak). Display gamma is applied before LUT mapping
     * so the LUT itself stays linear and low normalized values remain pale.
     *
     * Index 0 → background color (silence), index 255 → near-black (loud).
     */
    fun ink(backgroundArgb: Int, maxInkArgb: Int = DEFAULT_MAX_INK_ARGB): IntArray {
        val bgR = (backgroundArgb shr 16) and 0xFF
        val bgG = (backgroundArgb shr 8) and 0xFF
        val bgB = backgroundArgb and 0xFF
        val inkR = (maxInkArgb shr 16) and 0xFF
        val inkG = (maxInkArgb shr 8) and 0xFF
        val inkB = maxInkArgb and 0xFF
        return IntArray(LUT_SIZE) { i ->
            val t = i / (LUT_SIZE - 1f)
            val r = (bgR + (inkR - bgR) * t).toInt().coerceIn(0, 255)
            val g = (bgG + (inkG - bgG) * t).toInt().coerceIn(0, 255)
            val b = (bgB + (inkB - bgB) * t).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Maps [value] in [0,1] to the nearest entry in [lut]. Out-of-range values are clamped. */
    fun map(value: Float, lut: IntArray): Int =
        lut[((value.coerceIn(0f, 1f)) * (lut.size - 1)).toInt().coerceIn(0, lut.size - 1)]

    private const val DEFAULT_MAX_INK_ARGB = -14671840 // 0xFF202020
}
