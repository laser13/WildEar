package com.sound2inat.app.ui.spectrogram

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpectrogramColorMapTest {

    @Test
    fun `ink LUT has 256 entries`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE)
        assertThat(lut.size).isEqualTo(256)
    }

    @Test
    fun `ink LUT first entry matches background color`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE)
        assertThat(lut[0]).isEqualTo(WHITE)
    }

    @Test
    fun `ink LUT last entry is darker than first`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE)
        assertThat(luminance(lut.last())).isLessThan(luminance(lut.first()))
    }

    @Test
    fun `ink LUT last entry uses configurable near-black instead of pure black`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE, maxInkArgb = NEAR_BLACK)
        assertThat(lut.last()).isEqualTo(NEAR_BLACK)
        assertThat(lut.last()).isNotEqualTo(BLACK)
    }

    @Test
    fun `map(0f) returns first LUT entry`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE)
        assertThat(SpectrogramColorMap.map(0f, lut)).isEqualTo(lut[0])
    }

    @Test
    fun `map(1f) returns last LUT entry`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE)
        assertThat(SpectrogramColorMap.map(1f, lut)).isEqualTo(lut[255])
    }

    @Test
    fun `map clamps values below 0`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE)
        assertThat(SpectrogramColorMap.map(-1f, lut)).isEqualTo(SpectrogramColorMap.map(0f, lut))
    }

    @Test
    fun `map clamps values above 1`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE)
        assertThat(SpectrogramColorMap.map(2f, lut)).isEqualTo(SpectrogramColorMap.map(1f, lut))
    }

    @Test
    fun `all ink LUT entries are fully opaque`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE)
        lut.forEach { color ->
            assertThat((color ushr 24) and 0xFF).isEqualTo(0xFF)
        }
    }

    @Test
    fun `ink LUT is monotonically decreasing in luminance`() {
        val lut = SpectrogramColorMap.ink(backgroundArgb = WHITE)
        for (i in 1 until lut.size) {
            assertThat(luminance(lut[i])).isAtMost(luminance(lut[i - 1]))
        }
    }

    @Test
    fun `viridis magma and gray LUTs have 256 fully opaque entries`() {
        listOf(
            SpectrogramColorMap.viridis(),
            SpectrogramColorMap.magma(),
            SpectrogramColorMap.gray(),
        ).forEach { lut ->
            assertThat(lut.size).isEqualTo(256)
            lut.forEach { color ->
                assertThat((color ushr 24) and 0xFF).isEqualTo(0xFF)
            }
        }
    }

    @Test
    fun `gray LUT runs from black to white`() {
        val lut = SpectrogramColorMap.gray()
        assertThat(lut.first()).isEqualTo(BLACK)
        assertThat(lut.last()).isEqualTo(WHITE)
    }

    @Test
    fun `viridis and magma endpoints differ`() {
        val viridis = SpectrogramColorMap.viridis()
        val magma = SpectrogramColorMap.magma()
        assertThat(viridis.first()).isNotEqualTo(viridis.last())
        assertThat(magma.first()).isNotEqualTo(magma.last())
        assertThat(viridis.first()).isNotEqualTo(magma.first())
    }

    private fun luminance(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    companion object {
        private const val WHITE = -1 // 0xFFFFFFFF
        private const val BLACK = -16777216 // 0xFF000000
        private const val NEAR_BLACK = -14671840 // 0xFF202020
    }
}
