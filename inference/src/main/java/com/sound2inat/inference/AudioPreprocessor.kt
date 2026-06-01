package com.sound2inat.inference

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2nd-order Butterworth high-pass biquad filter (Audio EQ Cookbook formula).
 * Cutoff 250 Hz applied to the full resampled signal before window slicing.
 * Removes wind rumble, traffic sub-bass, and other sub-250 Hz interference.
 */
fun highPassFilter(samples: FloatArray, sampleRateHz: Int, cutoffHz: Int = 250): FloatArray {
    val w0 = 2.0 * PI * cutoffHz / sampleRateHz
    val cosW0 = cos(w0)
    val alpha = sin(w0) * sqrt(0.5) // sin(w0)/(2*Q), Q=1/sqrt(2) for Butterworth
    val a0 = 1.0 + alpha
    val nb0 = ((1.0 + cosW0) / 2.0 / a0).toFloat()
    val nb1 = (-(1.0 + cosW0) / a0).toFloat()
    val nb2 = nb0
    val na1 = (-2.0 * cosW0 / a0).toFloat()
    val na2 = ((1.0 - alpha) / a0).toFloat()

    val out = FloatArray(samples.size)
    var x1 = 0f
    var x2 = 0f
    var y1 = 0f
    var y2 = 0f
    for (i in samples.indices) {
        val x0 = samples[i]
        val y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
        out[i] = y0
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0
    }
    return out
}

/**
 * Adaptive spectral subtraction noise reducer.
 *
 * Quiet windows (RMS < 0.01) update an EMA noise power profile (α=0.1).
 * Louder windows have the profile subtracted in the power domain with
 * over-subtraction factor β=1.5 and spectral floor γ=0.002.
 *
 * The profile snapshot is taken BEFORE the update so the first quiet window
 * (when snapshot == null) returns unchanged — avoids subtracting a frame
 * from itself.
 *
 * NOT thread-safe — create one instance per InferenceRunner run.
 */
open class SpectralSubtractor {

    private var noiseProfile: FloatArray? = null

    open fun process(window: FloatArray): FloatArray {
        val fftSize = nextPow2(window.size)
        val expectedProfileSize = fftSize / 2 + 1
        val existingProfile = noiseProfile
        require(existingProfile == null || existingProfile.size == expectedProfileSize) {
            "SpectralSubtractor window size changed mid-stream — create a new instance per stream"
        }

        val rms = sqrt(window.fold(0.0) { acc, s -> acc + s.toDouble() * s } / window.size).toFloat()
        val profileSnapshot = noiseProfile

        val spectrum = FloatArray(fftSize * 2)
        for (i in window.indices) spectrum[2 * i] = window[i] // zero-pad imag parts stay 0
        fftInPlace(spectrum, fftSize, inverse = false)

        if (rms < RMS_QUIET_THRESHOLD) {
            val power = FloatArray(fftSize / 2 + 1) { i ->
                val re = spectrum[2 * i]
                val im = spectrum[2 * i + 1]
                re * re + im * im
            }
            val existing = noiseProfile
            noiseProfile = if (existing == null) {
                power
            } else {
                FloatArray(power.size) { i -> ALPHA * power[i] + (1f - ALPHA) * existing[i] }
            }
        }

        val profile = profileSnapshot ?: return window

        val outSpec = FloatArray(spectrum.size)
        for (i in 0..fftSize / 2) {
            val re = spectrum[2 * i]
            val im = spectrum[2 * i + 1]
            val powerIn = re * re + im * im
            val powerOut = max(powerIn - BETA * profile[i], GAMMA * powerIn)
            val scale = if (powerIn > POWER_FLOOR) sqrt(powerOut / powerIn) else 0f
            outSpec[2 * i] = re * scale
            outSpec[2 * i + 1] = im * scale
        }
        // Conjugate symmetry so IFFT produces a real signal
        for (i in 1 until fftSize / 2) {
            outSpec[2 * (fftSize - i)] = outSpec[2 * i]
            outSpec[2 * (fftSize - i) + 1] = -outSpec[2 * i + 1]
        }
        fftInPlace(outSpec, fftSize, inverse = true)
        val norm = 1f / fftSize
        return FloatArray(window.size) { i -> outSpec[2 * i] * norm }
    }

    private fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    private fun fftInPlace(data: FloatArray, n: Int, inverse: Boolean) =
        com.sound2inat.audio.Fft.inPlace(data, n, inverse)

    companion object {
        private const val RMS_QUIET_THRESHOLD = 0.01f
        private const val ALPHA = 0.1f
        private const val BETA = 1.5f
        private const val GAMMA = 0.002f

        // Numerical guard against divide-by-zero in spectral domain
        private const val POWER_FLOOR = 1e-20f
    }
}
