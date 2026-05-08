package com.sound2inat.app.ui.review

import androidx.compose.ui.graphics.Color

/**
 * Categorical 10-color palette used to color-code species rectangles on the
 * Review screen's spectrogram overlay. Colors are chosen for high mutual
 * contrast and readability against the Viridis-coloured spectrogram.
 *
 * Palette is indexed by the species' position in the list ([indexHint]); the
 * caller is expected to sort the species list by descending max confidence
 * so the most-prominent species always picks the same first color.
 *
 * If [indexHint] is negative or `null`, a stable hash of the [taxon] string
 * is used as a fallback so colour stays consistent across recompositions.
 */
object SpeciesPalette {
    private val Colors: List<Color> = listOf(
        Color(0xFFE6194B), // red
        Color(0xFF3CB44B), // green
        Color(0xFFFFE119), // yellow
        Color(0xFF4363D8), // blue
        Color(0xFFF58231), // orange
        Color(0xFF911EB4), // purple
        Color(0xFF42D4F4), // cyan
        Color(0xFFF032E6), // magenta
        Color(0xFFBFEF45), // lime
        Color(0xFFFABEBE), // pink
    )

    fun colorFor(taxon: String, indexHint: Int): Color {
        val idx = if (indexHint >= 0) {
            indexHint % Colors.size
        } else {
            Math.floorMod(taxon.hashCode(), Colors.size)
        }
        return Colors[idx]
    }
}
