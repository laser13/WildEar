package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MelSpectrogramTest {
    private val params = MelParams()
    private val win = params.sampleRate * 3 // 3-second window

    private fun sine(freqHz: Double, n: Int = win): FloatArray {
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = (sin(2 * PI * freqHz * i / params.sampleRate) * 0.5).toFloat()
        }
        return out
    }

    @Test
    fun `output shape is melBins by frames`() {
        val mel = MelSpectrogram(params)
        val out = mel.compute(sine(1000.0))
        val expectedFrames = 1 + (win - params.nFft) / params.hop
        assertThat(out.size).isEqualTo(params.melBins)
        assertThat(out[0].size).isEqualTo(expectedFrames)
    }

    @Test
    fun `1 kHz sine peaks in the corresponding mel bin`() {
        val mel = MelSpectrogram(params)
        val out = mel.compute(sine(1000.0))
        // average across time
        val avg = FloatArray(params.melBins) { i -> out[i].average().toFloat() }
        val peakBin = avg.indices.maxBy { avg[it] }
        val melMax = 2595.0 * Math.log10(1 + 24_000.0 / 700)
        val melTarget = 2595.0 * Math.log10(1 + 1000.0 / 700)
        val expectedBin = (melTarget / melMax * params.melBins).toInt()
        assertThat(peakBin).isIn((expectedBin - 4)..(expectedBin + 4))
    }

    @Test
    fun `repeated computation is deterministic`() {
        val mel = MelSpectrogram(params)
        val a = mel.compute(sine(1000.0))
        val b = mel.compute(sine(1000.0))
        for (i in 0 until params.melBins) {
            assertThat(a[i]).isEqualTo(b[i])
        }
    }
}
