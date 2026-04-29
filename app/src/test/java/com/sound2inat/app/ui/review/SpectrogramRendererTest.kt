package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.MelParams
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrogramRendererTest {
    private val params = MelParams()

    private fun sine(freqHz: Double, n: Int): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = (sin(2 * PI * freqHz * i / params.sampleRate) * 0.5).toFloat()
        }
        return out
    }

    @Test
    fun `render produces height equal to melBins and width capped to target`() {
        val renderer = SpectrogramRenderer(params, targetWidth = 256)
        val samples = sine(1_000.0, n = params.sampleRate * 3)
        val pixels = renderer.render(samples)
        assertThat(pixels.size).isEqualTo(params.melBins)
        assertThat(pixels[0].size).isAtMost(256)
        assertThat(pixels[0].size).isGreaterThan(0)
    }

    @Test
    fun `render returns empty when samples shorter than nFft`() {
        val renderer = SpectrogramRenderer(params)
        val pixels = renderer.render(FloatArray(params.nFft - 1))
        assertThat(pixels).isEmpty()
    }

    @Test
    fun `pixels are fully opaque ARGB ints`() {
        val renderer = SpectrogramRenderer(params, targetWidth = 64)
        val pixels = renderer.render(sine(1_000.0, n = params.sampleRate * 3))
        val alpha = (pixels[0][0] ushr 24) and 0xFF
        assertThat(alpha).isEqualTo(0xFF)
    }

    @Test
    fun `colormap viridis endpoints differ and are bounded`() {
        val low = Colormap.viridis(0f)
        val high = Colormap.viridis(1f)
        assertThat(low).isNotEqualTo(high)
        for (v in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val rgb = Colormap.viridis(v)
            val a = (rgb ushr 24) and 0xFF
            assertThat(a).isEqualTo(0xFF)
        }
    }

    @Test
    fun `colormap clamps out-of-range inputs`() {
        assertThat(Colormap.viridis(-1f)).isEqualTo(Colormap.viridis(0f))
        assertThat(Colormap.viridis(2f)).isEqualTo(Colormap.viridis(1f))
    }

    @Test
    fun `waveform peaks produce min and max per column`() {
        val samples = FloatArray(1_024) { i -> if (i % 2 == 0) -0.5f else 0.5f }
        val peaks = WaveformBitmap.peaks(samples, targetWidth = 32)
        // 32 columns * 2 (min, max).
        assertThat(peaks.size).isEqualTo(64)
        for (x in 0 until 32) {
            assertThat(peaks[2 * x]).isAtMost(peaks[2 * x + 1])
        }
    }

    @Test
    fun `waveform peaks empty on empty input`() {
        assertThat(WaveformBitmap.peaks(FloatArray(0), targetWidth = 32)).isEmpty()
    }
}
