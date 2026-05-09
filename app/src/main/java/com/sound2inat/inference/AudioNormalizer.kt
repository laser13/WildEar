package com.sound2inat.inference

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

object AudioNormalizer {

    /** Peak-normalize [samples] in memory. Silence (all zeros) is returned unchanged. */
    fun normalizeSamples(samples: ShortArray): ShortArray {
        val peak = samples.maxOfOrNull { abs(it.toInt()) } ?: 0
        if (peak == 0) return samples
        val scale = Short.MAX_VALUE.toDouble() / peak
        return ShortArray(samples.size) { i ->
            Math.round(samples[i] * scale)
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toShort()
        }
    }

    /**
     * Read [src] as mono 16-bit PCM WAV, peak-normalize, write to [dst].
     * Silence (peak == 0) is copied verbatim. [dst] may equal [src] only if
     * the caller guarantees no concurrent readers; for atomic in-place
     * replacement use a temp file + rename.
     */
    fun normalizeFile(src: File, dst: File) {
        val (samples, sampleRate) = WavReader.readMono16(src)
        writeWav(normalizeSamples(samples), sampleRate, dst)
    }
}

/**
 * Write [samples] as a mono 16-bit PCM WAV to [dst].
 * Used internally by [AudioNormalizer] and PostRecordingProcessor (to be created).
 */
internal fun writeWav(samples: ShortArray, sampleRateHz: Int, dst: File) {
    val dataSize = samples.size * 2
    RandomAccessFile(dst, "rw").use { raf ->
        raf.setLength(0)
        raf.write(buildWavHeader(sampleRateHz, dataSize))
        val buf = ByteArray(dataSize)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            buf[2 * i] = (s and 0xFF).toByte()
            buf[2 * i + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        raf.write(buf)
    }
}

private fun buildWavHeader(sampleRateHz: Int, dataSize: Int): ByteArray {
    val byteRate = sampleRateHz * 2 // mono 16-bit
    return ByteArray(44).also { h ->
        h[0] = 'R'.code.toByte(); h[1] = 'I'.code.toByte()
        h[2] = 'F'.code.toByte(); h[3] = 'F'.code.toByte()
        leInt(h, 4, dataSize + 36)
        h[8] = 'W'.code.toByte(); h[9] = 'A'.code.toByte()
        h[10] = 'V'.code.toByte(); h[11] = 'E'.code.toByte()
        h[12] = 'f'.code.toByte(); h[13] = 'm'.code.toByte()
        h[14] = 't'.code.toByte(); h[15] = ' '.code.toByte()
        leInt(h, 16, 16)             // fmt chunk size
        leShort(h, 20, 1)            // PCM
        leShort(h, 22, 1)            // mono
        leInt(h, 24, sampleRateHz)
        leInt(h, 28, byteRate)
        leShort(h, 32, 2)            // block align (1 ch * 2 bytes)
        leShort(h, 34, 16)           // bits per sample
        h[36] = 'd'.code.toByte(); h[37] = 'a'.code.toByte()
        h[38] = 't'.code.toByte(); h[39] = 'a'.code.toByte()
        leInt(h, 40, dataSize)
    }
}

private fun leInt(buf: ByteArray, off: Int, v: Int) {
    buf[off] = (v and 0xFF).toByte()
    buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
    buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
}

private fun leShort(buf: ByteArray, off: Int, v: Int) {
    buf[off] = (v and 0xFF).toByte()
    buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
}
