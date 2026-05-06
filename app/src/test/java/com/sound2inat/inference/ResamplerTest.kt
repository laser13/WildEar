package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ResamplerTest {

    @Test
    fun `same rate returns input unchanged`() {
        val input = shortArrayOf(1, 2, 3, 4, 5)
        val out = Resampler.resample(input, 48_000, 48_000)
        assertThat(out).isEqualTo(input)
    }

    @Test
    fun `empty or single sample input is returned unchanged`() {
        assertThat(Resampler.resample(ShortArray(0), 48_000, 32_000)).isEmpty()
        assertThat(Resampler.resample(shortArrayOf(7), 48_000, 32_000)).isEqualTo(shortArrayOf(7))
    }

    @Test
    fun `down-sample 48k to 32k yields expected length`() {
        // (3 - 1) / (48/32) + 1 = 2 / 1.5 + 1 = 1 + 1 = 2 (floor)
        val input = ShortArray(3 * 48_000) // 3 s of audio at 48 kHz.
        val out = Resampler.resample(input, 48_000, 32_000)
        assertThat(out.size).isEqualTo(3 * 32_000)
    }

    @Test
    fun `linear interpolation midpoint between two samples`() {
        // Midpoint of [0, 1000] resampled 2x→1x should be ~500.
        val input = shortArrayOf(0, 1000, 2000, 3000)
        val out = Resampler.resample(input, 4, 8) // upsample 2x.
        // First sample stays 0, then ~500, then 1000, etc.
        assertThat(out[0].toInt()).isEqualTo(0)
        assertThat(out[1].toInt()).isWithin(2).of(500)
        assertThat(out[2].toInt()).isWithin(2).of(1000)
        assertThat(out[3].toInt()).isWithin(2).of(1500)
    }

    @Test
    fun `sine wave preserves rough peak amplitude`() {
        // 100 Hz sine at 48 kHz → 32 kHz; amplitude should stay near 10000.
        val freq = 100.0
        val amp = 10_000
        val sr = 48_000
        val n = sr // 1 second.
        val sine = ShortArray(n) { i ->
            (amp * sin(2 * PI * freq * i / sr)).toInt().toShort()
        }
        val out = Resampler.resample(sine, 48_000, 32_000)
        val peak = out.maxOf { it.toInt() }
        assertThat(peak).isAtLeast(amp - 200)
        assertThat(peak).isAtMost(amp + 200)
    }
}
