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
    val alpha = sin(w0) * sqrt(0.5)   // sin(w0)/(2*Q), Q=1/sqrt(2) for Butterworth
    val a0 = 1.0 + alpha
    val nb0 = ((1.0 + cosW0) / 2.0 / a0).toFloat()
    val nb1 = (-(1.0 + cosW0) / a0).toFloat()
    val nb2 = nb0
    val na1 = (-2.0 * cosW0 / a0).toFloat()
    val na2 = ((1.0 - alpha) / a0).toFloat()

    val out = FloatArray(samples.size)
    var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
    for (i in samples.indices) {
        val x0 = samples[i]
        val y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
        out[i] = y0
        x2 = x1; x1 = x0; y2 = y1; y1 = y0
    }
    return out
}

/**
 * Denoise a full mono signal in one shot: high-pass filter, then 1-second
 * non-overlapping chunks each fed through a single [SpectralSubtractor]
 * (so its EMA noise profile builds up across the recording).
 *
 * Output length equals input length. Used by the Review screen's denoise
 * preview — separate from [InferenceRunner] which slices on the model's
 * own (overlapping) window cadence.
 */
fun denoiseFull(samples: FloatArray, sampleRateHz: Int): FloatArray {
    if (samples.isEmpty()) return samples
    val filtered = highPassFilter(samples, sampleRateHz)
    val chunkSize = sampleRateHz  // 1-second chunks, regardless of model window length
    if (filtered.size <= chunkSize) return SpectralSubtractor().process(filtered)
    val sub = SpectralSubtractor()
    val out = FloatArray(filtered.size)
    var off = 0
    while (off < filtered.size) {
        val avail = filtered.size - off
        if (avail >= chunkSize) {
            val chunk = filtered.copyOfRange(off, off + chunkSize)
            sub.process(chunk).copyInto(out, off, 0, chunkSize)
            off += chunkSize
        } else {
            // Last partial chunk: pad to full chunkSize with zeros (the subtractor's
            // FFT-size invariant requires every call to use the same window length),
            // process, then copy back only the original [avail] samples.
            val padded = FloatArray(chunkSize)
            filtered.copyInto(padded, 0, off, off + avail)
            sub.process(padded).copyInto(out, off, 0, avail)
            off += avail
        }
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
class SpectralSubtractor {

    private var noiseProfile: FloatArray? = null

    fun process(window: FloatArray): FloatArray {
        val fftSize = nextPow2(window.size)
        val expectedProfileSize = fftSize / 2 + 1
        val existingProfile = noiseProfile
        require(existingProfile == null || existingProfile.size == expectedProfileSize) {
            "SpectralSubtractor window size changed mid-stream — create a new instance per stream"
        }

        val rms = sqrt(window.fold(0.0) { acc, s -> acc + s.toDouble() * s } / window.size).toFloat()
        val profileSnapshot = noiseProfile

        val spectrum = FloatArray(fftSize * 2)
        for (i in window.indices) spectrum[2 * i] = window[i]   // zero-pad imag parts stay 0
        fftInPlace(spectrum, fftSize, inverse = false)

        if (rms < RMS_QUIET_THRESHOLD) {
            val power = FloatArray(fftSize / 2 + 1) { i ->
                val re = spectrum[2 * i]; val im = spectrum[2 * i + 1]
                re * re + im * im
            }
            val existing = noiseProfile
            noiseProfile = if (existing == null) power
                           else FloatArray(power.size) { i -> ALPHA * power[i] + (1f - ALPHA) * existing[i] }
        }

        val profile = profileSnapshot ?: return window

        val outSpec = FloatArray(spectrum.size)
        for (i in 0..fftSize / 2) {
            val re = spectrum[2 * i]; val im = spectrum[2 * i + 1]
            val powerIn = re * re + im * im
            val powerOut = max(powerIn - BETA * profile[i], GAMMA * powerIn)
            val scale = if (powerIn > POWER_FLOOR) sqrt(powerOut / powerIn) else 0f
            outSpec[2 * i] = re * scale; outSpec[2 * i + 1] = im * scale
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
        var p = 1; while (p < n) p = p shl 1; return p
    }

    @Suppress("NestedBlockDepth", "LongMethod")
    private fun fftInPlace(data: FloatArray, n: Int, inverse: Boolean) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = data[2 * i]; data[2 * i] = data[2 * j]; data[2 * j] = tmp
                tmp = data[2 * i + 1]; data[2 * i + 1] = data[2 * j + 1]; data[2 * j + 1] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val wBaseRe = cos(ang); val wBaseIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0; var wIm = 0.0
                for (k in 0 until len / 2) {
                    val u = i + k; val v = i + k + len / 2
                    val uRe = data[2 * u]; val uIm = data[2 * u + 1]
                    val vRe = data[2 * v]; val vIm = data[2 * v + 1]
                    val tvRe = (vRe * wRe - vIm * wIm).toFloat()
                    val tvIm = (vRe * wIm + vIm * wRe).toFloat()
                    data[2 * u] = uRe + tvRe; data[2 * u + 1] = uIm + tvIm
                    data[2 * v] = uRe - tvRe; data[2 * v + 1] = uIm - tvIm
                    val nWRe = wRe * wBaseRe - wIm * wBaseIm
                    wIm = wRe * wBaseIm + wIm * wBaseRe; wRe = nWRe
                }
                i += len
            }
            len = len shl 1
        }
        // Caller is responsible for dividing by n for IFFT — done in process()
    }

    companion object {
        private const val RMS_QUIET_THRESHOLD = 0.01f
        private const val ALPHA = 0.1f
        private const val BETA = 1.5f
        private const val GAMMA = 0.002f
        // Numerical guard against divide-by-zero in spectral domain
        private const val POWER_FLOOR = 1e-20f
    }
}
