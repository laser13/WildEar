package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.spectrogram.SpectrogramNoiseFloorMode
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
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

    private fun mixedSine(freq1Hz: Double, freq2Hz: Double, n: Int): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / params.sampleRate
            out[i] = (sin(2 * PI * freq1Hz * t) * 0.25 + sin(2 * PI * freq2Hz * t) * 0.25).toFloat()
        }
        return out
    }

    @Test
    fun `render produces shared live-style height and width capped to target`() {
        val renderer = SpectrogramRenderer(params, targetWidth = 256)
        val samples = sine(1_000.0, n = params.sampleRate * 3)
        val pixels = renderer.render(samples)
        assertThat(pixels.size).isEqualTo(256)
        assertThat(pixels[0].size).isAtMost(256)
        assertThat(pixels[0].size).isGreaterThan(0)
    }

    @Test
    fun `render returns empty when samples shorter than nFft`() {
        val renderer = SpectrogramRenderer(params)
        val pixels = renderer.render(FloatArray(2_047))
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

    // ── noise floor ───────────────────────────────────────────────────────────

    @Test
    fun `PER_FREQUENCY_MEDIAN and NONE produce different output`() {
        val samples = sine(1_000.0, n = params.sampleRate * 3)
        val withMedian = SpectrogramRenderer(
            params,
            targetWidth = 64,
            noiseFloorMode = SpectrogramNoiseFloorMode.PER_FREQUENCY_MEDIAN,
        ).render(samples)
        val noMedian = SpectrogramRenderer(
            params,
            targetWidth = 64,
            noiseFloorMode = SpectrogramNoiseFloorMode.NONE,
        ).render(samples)
        assertThat(withMedian).isNotEqualTo(noMedian)
    }

    @Test
    fun `default noise floor mode matches explicit PER_COLUMN_MEDIAN`() {
        val samples = sine(1_000.0, n = params.sampleRate * 3)
        val default = SpectrogramRenderer(params, targetWidth = 32).render(samples)
        val explicit = SpectrogramRenderer(
            params,
            targetWidth = 32,
            noiseFloorMode = SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN,
        ).render(samples)
        assertThat(default).isEqualTo(explicit)
    }

    // ── display range ─────────────────────────────────────────────────────────

    @Test
    fun `BIRD_FOCUSED and FULL display ranges produce different pixel output`() {
        // 100 Hz component is below BIRD_FOCUSED minimum (400 Hz) but inside FULL (0 Hz).
        val samples = mixedSine(100.0, 5_000.0, n = params.sampleRate * 3)
        val birdFocused = SpectrogramRenderer(
            params,
            targetWidth = 64,
            displayRange = SpectrogramDisplayRange.BIRD_FOCUSED,
        ).render(samples)
        val full = SpectrogramRenderer(
            params,
            targetWidth = 64,
            displayRange = SpectrogramDisplayRange.FULL,
        ).render(samples)
        assertThat(birdFocused).isNotEqualTo(full)
    }

    @Test
    fun `default display range matches explicit BIRD_FOCUSED`() {
        val samples = sine(1_000.0, n = params.sampleRate * 3)
        val default = SpectrogramRenderer(params, targetWidth = 32).render(samples)
        val explicit = SpectrogramRenderer(
            params,
            targetWidth = 32,
            displayRange = SpectrogramDisplayRange.BIRD_FOCUSED,
        ).render(samples)
        assertThat(default).isEqualTo(explicit)
    }

    @Test
    fun `review spectrogram config defaults to BirdNET bird view`() {
        val config = ReviewSpectrogramConfig.BirdDefault
        assertThat(config.displayRange).isEqualTo(SpectrogramDisplayRange.BIRDNET_BIRD)
        assertThat(config.gainDb).isEqualTo(0f)
        assertThat(config.lowPercentile).isEqualTo(5f)
        assertThat(config.highPercentile).isEqualTo(99f)
    }

    // ── palette ───────────────────────────────────────────────────────────────

    @Test
    fun `INK and VIRIDIS palettes produce different pixel output`() {
        val samples = sine(1_000.0, n = params.sampleRate * 3)
        val ink =
            SpectrogramRenderer(params, targetWidth = 64, palette = SpectrogramPalette.INK).render(samples)
        val viridis =
            SpectrogramRenderer(params, targetWidth = 64, palette = SpectrogramPalette.VIRIDIS).render(samples)
        assertThat(ink).isNotEqualTo(viridis)
    }

    @Test
    fun `default palette output matches explicit INK palette`() {
        val samples = sine(1_000.0, n = params.sampleRate * 3)
        val default = SpectrogramRenderer(params, targetWidth = 64).render(samples)
        val explicit =
            SpectrogramRenderer(params, targetWidth = 64, palette = SpectrogramPalette.INK).render(samples)
        assertThat(default).isEqualTo(explicit)
    }

    @Test
    fun `magma and gray palettes produce different output`() {
        val samples = sine(1_000.0, n = params.sampleRate * 3)
        val magma = SpectrogramRenderer(
            params,
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.MAGMA),
        ).render(samples)
        val gray = SpectrogramRenderer(
            params,
            targetWidth = 64,
            config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.GRAY),
        ).render(samples)
        assertThat(magma).isNotEqualTo(gray)
    }

    // ── top-dB clamp ──────────────────────────────────────────────────────────

    @Test
    fun `top-dB clamp preserves values within range and raises floor`() {
        // Create values spanning 200 dB: from -200 to 0.
        val values = FloatArray(201) { i -> (i - 200).toFloat() }
        val clamped = SpectrogramRenderer.applyTopDbClamp(values, topDb = 75f)
        val maxClamped = clamped.max()
        val minClamped = clamped.min()
        // max should be unchanged (0 dB).
        assertThat(maxClamped).isEqualTo(0f)
        // floor must be max - 75 = -75 dB; nothing below that.
        assertThat(minClamped).isEqualTo(-75f)
        // Dynamic range after clamp is exactly TOP_DB.
        assertThat(maxClamped - minClamped).isEqualTo(75f)
    }

    @Test
    fun `topDb clamp — quiet call remains visible after clamp normalization`() {
        // Scenario: extreme low-frequency rumble at 0 dB dominates and the
        // dynamic range extends to –500 dB (e.g. numerical noise floor).
        // Without a top-dB clamp the quiet bird call at –70 dB gets squashed
        // very close to 0 after normalization.  With a 75 dB clamp the floor
        // is raised to –75 dB and the –70 dB call maps to a clearly visible value.
        //
        // We use a large array so percentile indices are stable.
        val n = 400
        // 200 values at 0 dB (loud rumble), 190 values at –500 dB (extreme noise floor),
        // 10 values at –70 dB (quiet bird call).
        val values = FloatArray(n) { i ->
            when {
                i < 200 -> 0f // loud rumble
                i < 390 -> -500f // extreme noise — creates huge unclamped range
                else -> -70f // quiet bird call
            }
        }

        // Without clamp: p95 ≈ 0, p5 ≈ –500. Range ~500 dB.
        // –70 dB call normalises to (–70 – (–500)) / 500 ≈ 0.86 — OK.
        // But after top-dB clamp the –500 dB values are lifted, confirming
        // the clamp actually constrains the floor.
        val clamped = SpectrogramRenderer.applyTopDbClamp(values, topDb = 75f)

        // All clamped values must be >= (max – 75) = –75 dB.
        val floorExpected = clamped.max() - 75f
        assertThat(clamped.min()).isAtLeast(floorExpected)

        // The quiet bird call at –70 dB must survive (not be clipped to floor).
        val quietCallClamped = clamped[390]
        assertThat(quietCallClamped).isEqualTo(-70f)

        // After normalization the quiet call must be visible (non-zero, non-trivial).
        val norm = SpectrogramRenderer.percentileNormalize(clamped)
        val quietNorm = norm[390]
        assertThat(quietNorm).isGreaterThan(0f)
        assertThat(quietNorm).isLessThan(1f)
    }

    // ── percentile normalization ───────────────────────────────────────────────

    @Test
    fun `percentile normalization — p5-p95 range spans full 0-1 output`() {
        // 100 linearly-spaced values. p5 = value[5], p95 = value[95].
        val values = FloatArray(100) { it.toFloat() }
        val result = SpectrogramRenderer.percentileNormalize(values)

        // Majority of the output should span [0, 1] — specifically the 5th and
        // 95th elements of the sorted output should be near 0 and 1.
        val sorted = result.toMutableList().also { it.sort() }
        assertThat(sorted.first()).isEqualTo(0f) // clamped below p5
        assertThat(sorted.last()).isEqualTo(1f) // clamped above p95
        // The median should be somewhere in the middle (not 0 or 1).
        val median = sorted[50]
        assertThat(median).isGreaterThan(0f)
        assertThat(median).isLessThan(1f)
    }

    @Test
    fun `percentile normalization handles constant input`() {
        // All same value → range is 0. Should not crash; all values → 0.
        val values = FloatArray(50) { 42f }
        val result = SpectrogramRenderer.percentileNormalize(values)
        assertThat(result.all { it == 0f }).isTrue()
    }

    @Test
    fun `percentile normalization returns empty for empty input`() {
        val result = SpectrogramRenderer.percentileNormalize(FloatArray(0))
        assertThat(result).isEmpty()
    }
}
