package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AudioPreprocessorTest {

    private val sampleRate = 8_000

    private fun powerAtFreq(samples: FloatArray, freqHz: Double): Double {
        var re = 0.0; var im = 0.0
        val n = samples.size
        for (k in 0 until n) {
            val angle = 2 * PI * freqHz * k / sampleRate
            re += samples[k] * cos(angle)
            im += samples[k] * sin(angle)
        }
        return (re * re + im * im) / (n.toLong() * n)
    }

    @Test
    fun `highPassFilter attenuates 100 Hz by more than 13 dB at 250 Hz cutoff`() {
        val n = sampleRate  // 1 second of audio
        val signal = FloatArray(n) { k ->
            val t = k.toDouble() / sampleRate
            (sin(2 * PI * 100 * t) * 0.5 + sin(2 * PI * 1000 * t) * 0.5).toFloat()
        }
        val filtered = highPassFilter(signal, sampleRate, cutoffHz = 250)

        val origPow100 = powerAtFreq(signal, 100.0)
        val origPow1000 = powerAtFreq(signal, 1000.0)
        val filtPow100 = powerAtFreq(filtered, 100.0)
        val filtPow1000 = powerAtFreq(filtered, 1000.0)

        // 100 Hz is 2.5x below 250 Hz cutoff — 2nd-order Butterworth gives ~16 dB attenuation
        assertThat(filtPow100 / origPow100).isLessThan(0.05)
        // 1000 Hz is 4x above cutoff — should pass with less than ~1.5 dB loss
        assertThat(filtPow1000 / origPow1000).isGreaterThan(0.7)
    }

    @Test
    fun `SpectralSubtractor returns window unchanged when no noise profile`() {
        val sub = SpectralSubtractor()
        // RMS = 0.3 > 0.01 quiet threshold — noisy window, no profile update, profile stays null
        val window = FloatArray(1024) { 0.3f }
        val result = sub.process(window)
        assertThat(result.toList()).isEqualTo(window.toList())
    }

    @Test
    fun `SpectralSubtractor first quiet window returns unchanged (profile snapshot was null)`() {
        val sub = SpectralSubtractor()
        // RMS = 0.008 < 0.01 — quiet; profile snapshot is null before update, so window returned as-is
        val quiet = FloatArray(1024) { 0.008f }
        val result = sub.process(quiet)
        assertThat(result.toList()).isEqualTo(quiet.toList())
    }

    @Test
    fun `denoiseFull preserves length and applies HPF on a multi-second signal`() {
        val n = sampleRate * 3  // 3 seconds — exercises the multi-chunk path
        val signal = FloatArray(n) { k ->
            val t = k.toDouble() / sampleRate
            (sin(2 * PI * 100 * t) * 0.5 + sin(2 * PI * 1000 * t) * 0.5).toFloat()
        }
        val out = denoiseFull(signal, sampleRate)
        assertThat(out).hasLength(signal.size)
        // 100 Hz must be attenuated relative to the original (HPF is unconditional).
        val origPow100 = powerAtFreq(signal, 100.0)
        val outPow100 = powerAtFreq(out, 100.0)
        assertThat(outPow100 / origPow100).isLessThan(0.1)
    }

    @Test
    fun `denoiseFull handles signal shorter than one second`() {
        val short = FloatArray(sampleRate / 2) { 0.1f }  // 0.5 s
        val out = denoiseFull(short, sampleRate)
        assertThat(out).hasLength(short.size)
    }

    @Test
    fun `denoiseFull handles partial last chunk without size mismatch`() {
        // 2.5 seconds — first two chunks full, last is partial. Must not crash
        // due to SpectralSubtractor's window-size invariant.
        val n = (sampleRate * 2.5).toInt()
        val signal = FloatArray(n) { 0.05f }
        val out = denoiseFull(signal, sampleRate)
        assertThat(out).hasLength(signal.size)
    }

    @Test
    fun `SpectralSubtractor reduces RMS after noise profile is established`() {
        val sub = SpectralSubtractor()
        sub.process(FloatArray(1024) { 0.008f })  // first quiet window — establishes profile
        val loud = FloatArray(1024) { 0.015f }    // RMS 0.015 > 0.01 — not quiet, gets subtracted
        val result = sub.process(loud)

        val rmsIn = sqrt(loud.fold(0.0) { a, s -> a + s.toDouble() * s } / loud.size)
        val rmsOut = sqrt(result.fold(0.0) { a, s -> a + s.toDouble() * s } / result.size)
        assertThat(rmsOut).isGreaterThan(0.0)
        assertThat(rmsOut).isLessThan(rmsIn)
    }
}
