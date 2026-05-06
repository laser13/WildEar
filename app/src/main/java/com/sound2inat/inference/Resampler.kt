package com.sound2inat.inference

/**
 * Linear-interpolation resampler for mono 16-bit PCM.
 *
 * MVP-grade: linear (not sinc) interpolation is enough for bioacoustic models
 * that already operate on log-mel-equivalent features and have been trained
 * on data from messy field recorders. Quality loss from linear interpolation
 * vs. polyphase is far below the variance from microphone, distance and wind.
 *
 * Output length = `floor((input.size - 1) * outRate / inRate) + 1`.
 * For an empty or single-sample input the result is returned unchanged so
 * callers don't have to special-case it.
 */
object Resampler {

    fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        require(inRate > 0 && outRate > 0) { "Sample rates must be positive" }
        if (inRate == outRate || input.size < 2) return input
        val ratio = inRate.toDouble() / outRate.toDouble()
        val outLen = ((input.size - 1).toDouble() / ratio).toInt() + 1
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = srcPos - i0
            val v0 = input[i0].toDouble()
            val v1 = input[i1].toDouble()
            val v = v0 + (v1 - v0) * frac
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}
