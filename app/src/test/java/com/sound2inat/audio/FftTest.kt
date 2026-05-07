package com.sound2inat.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

class FftTest {
    @Test
    fun `pure tone produces peak at expected bin`() {
        val n = 1024
        val sampleRate = 48_000
        val freqHz = 1000.0
        val signal = FloatArray(n * 2) // interleaved real/imag, imag = 0
        for (i in 0 until n) {
            signal[2 * i] = cos(2.0 * PI * freqHz * i / sampleRate).toFloat()
        }
        Fft.inPlace(signal, n, inverse = false)
        val mag = FloatArray(n / 2 + 1) { i ->
            val re = signal[2 * i]
            val im = signal[2 * i + 1]
            sqrt(re * re + im * im)
        }
        val peakBin = mag.indices.maxByOrNull { mag[it] }!!
        val expectedBin = (freqHz * n / sampleRate).toInt()
        assertThat(peakBin).isAnyOf(expectedBin - 1, expectedBin, expectedBin + 1)
    }
}
