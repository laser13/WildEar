package com.sound2inat.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative Cooley-Tukey radix-2 FFT, in-place on interleaved real/imag float array.
 * Length `data.size` must be `2 * n` and `n` must be a power of two.
 *
 * For inverse, caller divides each output by `n` (not done here so callers can
 * skip the scaling when only relative magnitudes matter).
 */
object Fft {
    @Suppress("NestedBlockDepth", "LongMethod")
    fun inPlace(data: FloatArray, n: Int, inverse: Boolean) {
        require(data.size == 2 * n) { "data.size must be 2*n (got ${data.size}, n=$n)" }
        require(n > 0 && (n and (n - 1)) == 0) { "n must be a power of two (got $n)" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = data[2 * i]
                data[2 * i] = data[2 * j]
                data[2 * j] = tmp
                tmp = data[2 * i + 1]
                data[2 * i + 1] = data[2 * j + 1]
                data[2 * j + 1] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val wBaseRe = cos(ang)
            val wBaseIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until len / 2) {
                    val u = i + k
                    val v = i + k + len / 2
                    val uRe = data[2 * u]
                    val uIm = data[2 * u + 1]
                    val vRe = data[2 * v]
                    val vIm = data[2 * v + 1]
                    val tvRe = (vRe * wRe - vIm * wIm).toFloat()
                    val tvIm = (vRe * wIm + vIm * wRe).toFloat()
                    data[2 * u] = uRe + tvRe
                    data[2 * u + 1] = uIm + tvIm
                    data[2 * v] = uRe - tvRe
                    data[2 * v + 1] = uIm - tvIm
                    val nWRe = wRe * wBaseRe - wIm * wBaseIm
                    wIm = wRe * wBaseIm + wIm * wBaseRe
                    wRe = nWRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
