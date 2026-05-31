package com.sound2inat.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Streaming reader for mono 16-bit PCM WAV files. Validates the 44-byte header
 * once on [open] and then serves random-access window reads via [readNative].
 *
 * NOT thread-safe — each consumer (e.g. each parallel inference coroutine) must
 * have its own instance (and therefore its own file descriptor).
 *
 * Created in Task B1 to avoid holding the entire decoded WAV in memory.
 */
internal class WavWindowReader private constructor(
    private val raf: RandomAccessFile,
    val sampleRate: Int,
    val dataStartByte: Long,
    val totalSamples: Int,
) : AutoCloseable {

    /**
     * Reads [count] consecutive 16-bit samples starting at [startSample] (0-based
     * absolute index into the PCM data). Samples that lie past `totalSamples`
     * are returned as zeros — this matches the behaviour callers would have got
     * from `samples.copyOfRange(start, start + count)` with the legacy
     * full-buffer reader (out-of-range entries default to 0 in a freshly
     * allocated ShortArray).
     */
    fun readNative(startSample: Int, count: Int): ShortArray {
        require(count >= 0) { "count must be >= 0, was $count" }
        if (count == 0) return ShortArray(0)
        val out = ShortArray(count)
        val first = startSample.coerceAtLeast(0)
        val last = (startSample + count).coerceAtMost(totalSamples)
        val available = (last - first).coerceAtLeast(0)
        if (available == 0) return out
        val byteOffset = dataStartByte + first.toLong() * BYTES_PER_SAMPLE
        val buf = ByteArray(available * BYTES_PER_SAMPLE)
        raf.seek(byteOffset)
        raf.readFully(buf)
        val outOffset = (first - startSample) // leading zeros if startSample < 0
        for (i in 0 until available) {
            val lo = buf[2 * i].toInt() and 0xFF
            val hi = buf[2 * i + 1].toInt()
            out[outOffset + i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    override fun close() = raf.close()

    companion object {
        private const val BYTES_PER_SAMPLE = 2

        fun open(file: File): WavWindowReader {
            val parsed = WavPcmReader.readHeader(file)
            val raf = RandomAccessFile(file, "r")
            try {
                return WavWindowReader(
                    raf = raf,
                    sampleRate = parsed.sampleRateHz,
                    dataStartByte = WavPcmReader.HEADER_SIZE.toLong(),
                    totalSamples = parsed.totalSamples.toInt(),
                )
            } catch (t: Throwable) {
                runCatching { raf.close() }
                throw t
            }
        }
    }
}
