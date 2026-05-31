package com.sound2inat.audio

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

    fun viridis(): IntArray = gradient(
        intArrayOf(
            0x440154,
            0x472d7b,
            0x3b528b,
            0x2c728e,
            0x21918c,
            0x5ec962,
            0xb5de2b,
            0xfde725,
        ),
    )

    fun magma(): IntArray = gradient(
        intArrayOf(
            0x000004,
            0x1b0c41,
            0x4f0a6d,
            0x781c6d,
            0xa52c60,
            0xcf4446,
            0xed6925,
            0xfcba2f,
            0xfcfdbf,
        ),
    )

    fun gray(): IntArray = IntArray(LUT_SIZE) { i ->
        val t = i / (LUT_SIZE - 1f)
        val v = (255 - (t * 160f).toInt()).coerceIn(95, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** Maps [value] in [0,1] to the nearest entry in [lut]. Out-of-range values are clamped. */
    fun map(value: Float, lut: IntArray): Int =
        lut[((value.coerceIn(0f, 1f)) * (lut.size - 1)).toInt().coerceIn(0, lut.size - 1)]

    private fun gradient(anchorRgb: IntArray): IntArray {
        require(anchorRgb.size >= 2) { "gradient requires at least two anchors" }
        val out = IntArray(LUT_SIZE)
        for (i in 0 until LUT_SIZE) {
            val t = i / (LUT_SIZE - 1f)
            val scaled = t * (anchorRgb.size - 1)
            val idx = scaled.toInt().coerceIn(0, anchorRgb.size - 2)
            val frac = scaled - idx
            val a = anchorRgb[idx]
            val b = anchorRgb[idx + 1]
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val r = (ar + (br - ar) * frac).toInt().coerceIn(0, 255)
            val g = (ag + (bg - ag) * frac).toInt().coerceIn(0, 255)
            val bl = (ab + (bb - ab) * frac).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return out
    }

    private const val DEFAULT_MAX_INK_ARGB = -14671840 // 0xFF202020
}
