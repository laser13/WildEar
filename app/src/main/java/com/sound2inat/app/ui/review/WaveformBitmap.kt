package com.sound2inat.app.ui.review

/**
 * Pure-JVM peak-envelope extractor for the Review-screen waveform. For each
 * output column it produces a `(min, max)` pair from a contiguous slice of
 * the mono audio. The Compose layer renders the envelope as a vertical bar.
 *
 * Output layout: `FloatArray(targetWidth * 2)`, with `[2k]` = min and
 * `[2k+1]` = max for column `k`. A flat (interleaved) array keeps the
 * allocation small and the Compose draw loop branch-free.
 */
object WaveformBitmap {

    const val DEFAULT_TARGET_WIDTH = 1024

    /**
     * Compute peaks for [samples] (mono float, roughly `[-1, 1]`). Returns
     * an array of `targetWidth * 2` floats, or `FloatArray(0)` if [samples]
     * is empty. The resulting peaks are NOT clamped to `[-1, 1]`.
     */
    fun peaks(samples: FloatArray, targetWidth: Int = DEFAULT_TARGET_WIDTH): FloatArray {
        if (samples.isEmpty() || targetWidth <= 0) return FloatArray(0)
        val width = minOf(targetWidth, samples.size)
        val out = FloatArray(width * 2)
        for (x in 0 until width) {
            val start = (x.toLong() * samples.size / width).toInt()
            val end = ((x + 1).toLong() * samples.size / width).toInt().coerceAtMost(samples.size)
            var lo = samples[start]
            var hi = samples[start]
            for (i in start + 1 until end) {
                val s = samples[i]
                if (s < lo) lo = s
                if (s > hi) hi = s
            }
            out[2 * x] = lo
            out[2 * x + 1] = hi
        }
        return out
    }
}
