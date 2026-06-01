package com.sound2inat.audio
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

object SpectrogramVisualPipeline {

    fun whitenColumnInPlace(col: FloatArray, sortBuf: FloatArray) {
        System.arraycopy(col, 0, sortBuf, 0, col.size)
        sortBuf.sort()
        val median = sortBuf[sortBuf.size / 2]
        for (i in col.indices) col[i] = col[i] - median
    }

    fun logBinDown(
        src: FloatArray,
        outBins: Int,
        sampleRateHz: Int,
        minFrequencyHz: Int,
        maxFrequencyHz: Int,
    ): FloatArray {
        val out = FloatArray(outBins)
        val nyquistBins = src.size
        val minBin = frequencyToBin(minFrequencyHz, nyquistBins, sampleRateHz)
        val maxBin = frequencyToBin(maxFrequencyHz, nyquistBins, sampleRateHz)
            .coerceAtLeast(minBin + 1)
            .coerceAtMost(nyquistBins - 1)
        val logMin = if (minBin <= 0) 0.0 else ln(minBin.toDouble())
        val logMax = ln(maxBin.toDouble())
        val outScale = (outBins - 1).coerceAtLeast(1).toDouble()
        for (j in 0 until outBins) {
            val fracLo = ((j - 0.5).coerceAtLeast(0.0)) / outScale
            val fracHi = ((j + 0.5).coerceAtMost(outScale)) / outScale
            val lo = exp(logMin + fracLo * (logMax - logMin)).toInt().coerceIn(minBin, maxBin)
            val hi = exp(logMin + fracHi * (logMax - logMin)).toInt().coerceIn(lo, maxBin)
            var maxVal = src[lo]
            for (k in (lo + 1)..hi) {
                if (src[k] > maxVal) maxVal = src[k]
            }
            out[j] = maxVal
        }
        return out
    }

    fun displayValue(
        dbAboveFloor: Float,
        gateDb: Float,
        displayRangeDb: Float,
        gamma: Float,
    ): Float {
        val safeRange = displayRangeDb.coerceAtLeast(1e-6f)
        val safeGamma = gamma.coerceAtLeast(1e-6f)
        return ((dbAboveFloor - gateDb).coerceIn(0f, safeRange) / safeRange).pow(safeGamma)
    }

    fun smoothedRingValue(
        ring: SpectrogramRingBuffer,
        x: Int,
        y: Int,
        timeRadius: Int,
        frequencyRadius: Int,
    ): Float {
        if (ring.size == 0) return 0f
        val xStart = (x - timeRadius).coerceAtLeast(0)
        val xEnd = (x + timeRadius).coerceAtMost(ring.size - 1)
        val yStart = (y - frequencyRadius).coerceAtLeast(0)
        val yEnd = (y + frequencyRadius).coerceAtMost(ring.bins - 1)
        var sum = 0f
        var count = 0
        for (xx in xStart..xEnd) {
            val column = ring.column(xx)
            for (yy in yStart..yEnd) {
                sum += column[yy]
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun frequencyToBin(frequencyHz: Int, nyquistBins: Int, sampleRateHz: Int): Int =
        (frequencyHz.toLong() * (nyquistBins - 1) * 2 / sampleRateHz)
            .toInt()
            .coerceIn(0, nyquistBins - 1)
}
