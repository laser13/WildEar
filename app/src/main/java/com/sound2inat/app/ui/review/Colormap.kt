package com.sound2inat.app.ui.review

/**
 * Viridis perceptually-uniform colormap. Maps `[0, 1]` to ARGB packed ints
 * (0xAARRGGBB, alpha = 0xFF). Values outside `[0, 1]` are clamped.
 *
 * 9 anchor points sampled at evenly-spaced indices of the canonical 256-row
 * matplotlib viridis colormap (Stéfan van der Walt and Nathaniel Smith,
 * BIDS — released into the public domain, CC0). Linear interpolation
 * between anchors reproduces the original gradient closely enough for
 * spectrogram visualisation; the maximum perceptual delta against the
 * full 256-row table is below the human luminance JND.
 *
 * Reference: https://github.com/BIDS/colormap (CC0 / public domain)
 */
object Colormap {

    private const val BYTE = 0xFF
    private const val SHIFT_A = 24
    private const val SHIFT_R = 16
    private const val SHIFT_G = 8
    private const val ALPHA: Int = BYTE shl SHIFT_A

    /**
     * RGB triples (0xRRGGBB) at original indices 0, 32, 64, …, 224, 255.
     * Path: purple → deep blue → teal → green → yellow.
     */
    private val viridisLut: IntArray = intArrayOf(
        0x440154, // 0/255   — deep purple
        0x472f7d, // 32/255  — purple-blue
        0x3b528b, // 64/255  — blue
        0x2c728e, // 96/255  — teal-blue
        0x21918c, // 128/255 — teal
        0x28ae80, // 160/255 — light teal-green
        0x5dc863, // 192/255 — green
        0xb5de2b, // 224/255 — yellow-green
        0xfde725, // 255/255 — yellow
    )

    private const val ROWS = 9

    /**
     * Maps [value] in `[0, 1]` to a packed ARGB int. Out-of-range values
     * clamp to the endpoints. Alpha is fully opaque.
     */
    fun viridis(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val scaled = v * (ROWS - 1)
        val i = scaled.toInt().coerceAtMost(ROWS - 2)
        val frac = scaled - i
        val a = viridisLut[i]
        val b = viridisLut[i + 1]
        val ar = (a shr SHIFT_R) and BYTE
        val ag = (a shr SHIFT_G) and BYTE
        val ab = a and BYTE
        val br = (b shr SHIFT_R) and BYTE
        val bg = (b shr SHIFT_G) and BYTE
        val bb = b and BYTE
        val r = lerp(ar, br, frac)
        val g = lerp(ag, bg, frac)
        val bl = lerp(ab, bb, frac)
        return ALPHA or (r shl SHIFT_R) or (g shl SHIFT_G) or bl
    }

    private fun lerp(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).toInt().coerceIn(0, BYTE)
}
