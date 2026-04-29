package com.sound2inat.app.ui.review

/**
 * Viridis perceptually-uniform colormap. Maps `[0, 1]` to ARGB packed ints
 * (0xAARRGGBB, alpha = 0xFF). Values outside `[0, 1]` are clamped.
 *
 * The 32-row LUT below is a uniformly-subsampled snapshot of the canonical
 * 256-row viridis colormap originally defined by Stéfan van der Walt and
 * Nathaniel Smith (BIDS) and released into the public domain (CC0).
 *
 * Reference: https://github.com/BIDS/colormap (CC0 / public domain)
 *
 * Linear interpolation between adjacent rows yields a smooth gradient at
 * arbitrary input resolution.
 */
object Colormap {

    private const val BYTE = 0xFF
    private const val SHIFT_A = 24
    private const val SHIFT_R = 16
    private const val SHIFT_G = 8
    private const val ROWS = 20
    private const val ALPHA: Int = BYTE shl SHIFT_A

    /** RGB triples (0xRRGGBB), 32 rows uniformly sampled from value 0.0 → 1.0. */
    private val viridis: IntArray = intArrayOf(
        0x440154, 0x481467, 0x482576, 0x463480,
        0x414487, 0x3b528b, 0x355f8d, 0x2f6c8e,
        0x2a788e, 0x25848e, 0x21918c, 0x1f9d8a,
        0x22a884, 0x2db27d, 0x3fbc73, 0x5ac864,
        0x7ad151, 0x9fda3a, 0xc7e020, 0xfde725,
        0xfde725, 0xfde725, 0xfde725, 0xfde725,
        0xfde725, 0xfde725, 0xfde725, 0xfde725,
        0xfde725, 0xfde725, 0xfde725, 0xfde725,
    )

    /**
     * Maps [value] in `[0, 1]` to a packed ARGB int. Out-of-range values
     * clamp to the endpoints. Alpha is fully opaque.
     */
    fun viridis(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val scaled = v * (ROWS - 1)
        val i = scaled.toInt().coerceAtMost(ROWS - 2)
        val frac = scaled - i
        val a = viridis[i]
        val b = viridis[i + 1]
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
