package com.sound2inat.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrogramTest {
    @Test
    fun `first column requires a full fftSize buffer`() {
        val s = Spectrogram(fftSize = 1024, hopSize = 256, sampleRateHz = 48_000)
        val cols = s.process(FloatArray(1023))
        assertThat(cols).isEmpty()
    }

    @Test
    fun `pure tone produces peak at expected frequency bin`() {
        val s = Spectrogram(fftSize = 1024, hopSize = 1024, sampleRateHz = 48_000)
        val freqHz = 4000.0
        val signal = FloatArray(1024) { i ->
            sin(2.0 * PI * freqHz * i / 48_000).toFloat()
        }
        val cols = s.process(signal)
        assertThat(cols).hasSize(1)
        val peak = cols[0].indices.maxByOrNull { cols[0][it] }!!
        val expected = (freqHz * 1024 / 48_000).toInt()
        assertThat(peak).isAnyOf(expected - 1, expected, expected + 1)
    }

    @Test
    fun `silence produces dB floor across all bins`() {
        val s = Spectrogram(fftSize = 1024, hopSize = 1024, sampleRateHz = 48_000)
        val cols = s.process(FloatArray(1024))
        assertThat(cols).hasSize(1)
        for (v in cols[0]) assertThat(v).isAtMost(Spectrogram.DB_FLOOR + 0.5f)
    }

    @Test
    fun `subsequent columns appear every hopSize samples after first`() {
        val s = Spectrogram(fftSize = 1024, hopSize = 256, sampleRateHz = 48_000)
        // First fill: 1024 samples → 1 column
        val first = s.process(FloatArray(1024))
        assertThat(first).hasSize(1)
        // Next 256 samples → 1 more column (hopSize boundary reached)
        val second = s.process(FloatArray(256))
        assertThat(second).hasSize(1)
        // 255 more samples → no new column
        val third = s.process(FloatArray(255))
        assertThat(third).isEmpty()
        // 1 more sample (256 total since last column) → 1 more column
        val fourth = s.process(FloatArray(1))
        assertThat(fourth).hasSize(1)
    }
}
