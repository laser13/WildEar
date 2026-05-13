package com.sound2inat.app.ui.review

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.abs

object ReviewAudioProcessor {
    fun process(
        samples: ShortArray,
        sampleRateHz: Int,
        config: ReviewAudioProcessingConfig,
    ): ShortArray {
        if (samples.isEmpty() || !config.requiresProcessing) return samples.copyOf()
        var floats = FloatArray(samples.size) { i -> samples[i] / Short.MAX_VALUE.toFloat() }
        config.highPassHz?.let { cutoff ->
            floats = highPass(floats, sampleRateHz, cutoff)
        }
        if (config.gainDb != 0f) {
            val gain = exp(config.gainDb * ln(10.0) / 20.0).toFloat()
            for (i in floats.indices) floats[i] *= gain
        }
        if (config.normalizePeak) {
            val peak = floats.maxOf { abs(it) }
            if (peak > 1e-6f) {
                val scale = 0.98f / peak
                for (i in floats.indices) floats[i] *= scale
            }
        }
        return ShortArray(floats.size) { i ->
            (floats[i].coerceIn(-1f, 1f) * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun highPass(samples: FloatArray, sampleRateHz: Int, cutoffHz: Int): FloatArray {
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val dt = 1.0 / sampleRateHz
        val alpha = (rc / (rc + dt)).toFloat()
        val out = FloatArray(samples.size)
        var prevOut = 0f
        var prevIn = samples.firstOrNull() ?: 0f
        for (i in samples.indices) {
            val current = samples[i]
            val y = alpha * (prevOut + current - prevIn)
            out[i] = y
            prevOut = y
            prevIn = current
        }
        return out
    }
}
