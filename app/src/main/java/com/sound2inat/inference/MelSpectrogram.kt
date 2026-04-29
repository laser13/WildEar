package com.sound2inat.inference

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

/**
 * Pure-Kotlin mel-spectrogram preprocessor used by the Review screen to render
 * a visual spectrogram of a recorded window. The output is NOT fed to BirdNET
 * (the model consumes raw audio — see docs/private/MODEL_SPIKE.md).
 *
 * Steps per frame:
 *   1. Window the time-domain samples with a Hann window of length [MelParams.nFft].
 *   2. Run a real FFT via JTransforms `DoubleFFT_1D`.
 *   3. Compute the power spectrum.
 *   4. Project onto a Slaney-style mel filterbank (HTK formula
 *      `2595 * log10(1 + f / 700)`).
 *   5. Convert to dB: `10 * log10(max(EPS, melPower))`.
 *
 * Output shape: `[melBins][frames]` where
 *   `frames = 1 + (samples.size - nFft) / hop`.
 *
 * The implementation is deterministic — the window, filterbank, and FFT plan
 * are precomputed once per instance.
 */
class MelSpectrogram(private val p: MelParams) {

    private val window: DoubleArray = DoubleArray(p.nFft) { i ->
        0.5 * (1 - cos(2 * PI * i / (p.nFft - 1)))
    }
    private val fft = DoubleFFT_1D(p.nFft.toLong())
    private val mel: Array<DoubleArray> = buildMelFilterbank(p)

    fun compute(samples: FloatArray): Array<FloatArray> {
        require(samples.size >= p.nFft) {
            "samples.size (${samples.size}) must be >= nFft (${p.nFft})"
        }
        val frames = 1 + (samples.size - p.nFft) / p.hop
        val out = Array(p.melBins) { FloatArray(frames) }
        val buf = DoubleArray(p.nFft)
        val powBins = p.nFft / 2 + 1
        val pow = DoubleArray(powBins)
        for (f in 0 until frames) {
            val start = f * p.hop
            for (i in 0 until p.nFft) buf[i] = samples[start + i] * window[i]
            fft.realForward(buf)
            // realForward layout for even N:
            //   buf[0]   = re[0]
            //   buf[1]   = re[N/2]
            //   buf[2k]  = re[k], buf[2k+1] = im[k]   for 1 <= k <= N/2-1
            pow[0] = buf[0] * buf[0]
            pow[powBins - 1] = buf[1] * buf[1]
            for (i in 1 until powBins - 1) {
                val re = buf[2 * i]
                val im = buf[2 * i + 1]
                pow[i] = re * re + im * im
            }
            for (m in 0 until p.melBins) {
                var acc = 0.0
                val row = mel[m]
                for (i in 0 until powBins) acc += row[i] * pow[i]
                out[m][f] = (10.0 * log10(acc.coerceAtLeast(EPS))).toFloat()
            }
        }
        return out
    }

    companion object {
        const val EPS = 1e-10

        fun hzToMel(hz: Double) = 2595.0 * log10(1 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1)

        fun buildMelFilterbank(p: MelParams): Array<DoubleArray> {
            val powBins = p.nFft / 2 + 1
            val melMin = hzToMel(p.fMin.toDouble())
            val melMax = hzToMel(p.fMax.toDouble())
            val points = DoubleArray(p.melBins + 2) { i ->
                melMin + (melMax - melMin) * i / (p.melBins + 1)
            }
            val hzPoints = DoubleArray(p.melBins + 2) { i -> melToHz(points[i]) }
            val binPoints = DoubleArray(p.melBins + 2) { i ->
                (p.nFft + 1) * hzPoints[i] / p.sampleRate
            }
            return Array(p.melBins) { m ->
                val l = binPoints[m]
                val c = binPoints[m + 1]
                val r = binPoints[m + 2]
                DoubleArray(powBins) { i ->
                    when {
                        i < l || i > r -> 0.0
                        i <= c -> if (c == l) 0.0 else (i - l) / (c - l)
                        else -> if (r == c) 0.0 else (r - i) / (r - c)
                    }
                }
            }
        }
    }
}
