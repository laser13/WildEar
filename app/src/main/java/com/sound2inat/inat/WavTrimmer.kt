package com.sound2inat.inat

import com.sound2inat.recorder.WavWriter
import java.io.File
import java.io.RandomAccessFile

/**
 * Pure-JVM WAV slicer. Reads a mono 16-bit PCM WAV (the format produced by
 * [com.sound2inat.recorder.WavWriter]) and writes a new WAV containing only
 * the samples in `[startMs, endMs)` after clamping into the file's range.
 *
 * Used by the iNaturalist submission flow: per-species crops are tighter
 * than the whole recording, so we don't waste upload bandwidth nor force
 * reviewers to scrub past silence to find the call.
 */
object WavTrimmer {

    /**
     * @param srcPath the source WAV (mono 16-bit PCM, 44-byte header).
     * @param dstFile destination file; will be overwritten if it exists.
     * @param startMs inclusive crop start in milliseconds; values < 0 clamp to 0.
     * @param endMs exclusive crop end in milliseconds; values past the file
     *   length clamp to the end.
     * @return the destination file. Throws [IllegalStateException] if the
     *   resulting clip would be empty.
     */
    @Suppress("ThrowsCount")
    fun trimMono16(srcPath: String, dstFile: File, startMs: Long, endMs: Long): File {
        require(endMs > startMs) { "endMs must be > startMs (got $startMs..$endMs)" }
        RandomAccessFile(srcPath, "r").use { raf ->
            val header = ByteArray(HEADER_SIZE).also { raf.readFully(it) }
            require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
                "Not a WAV file: $srcPath"
            }
            val channels = leU16(header, OFFSET_CHANNELS)
            val sampleRate = leU32(header, OFFSET_SAMPLE_RATE)
            val bits = leU16(header, OFFSET_BITS)
            require(channels == 1 && bits == BITS_PCM16) {
                "Mono 16-bit PCM only (got ch=$channels bits=$bits)"
            }
            val dataBytes = leU32(header, OFFSET_DATA_SIZE)
            val totalSamples = dataBytes / BYTES_PER_SAMPLE

            val startSample = msToSamples(startMs.coerceAtLeast(0L), sampleRate).coerceAtMost(totalSamples)
            val endSample = msToSamples(endMs, sampleRate).coerceAtMost(totalSamples)
            check(endSample > startSample) {
                "Trim range $startMs..$endMs ms collapses to empty (file is ${totalSamples * 1000L / sampleRate} ms)"
            }

            // Read only the samples we need. PCM is 2 bytes/sample so we can
            // seek straight past the header + the prefix we're discarding.
            raf.seek((HEADER_SIZE + startSample * BYTES_PER_SAMPLE).toLong())
            val sliceLen = endSample - startSample
            val raw = ByteArray(sliceLen * BYTES_PER_SAMPLE)
            raf.readFully(raw)
            val shorts = ShortArray(sliceLen)
            for (i in shorts.indices) {
                val lo = raw[2 * i].toInt() and 0xFF
                val hi = raw[2 * i + 1].toInt()
                shorts[i] = ((hi shl 8) or lo).toShort()
            }
            // Reuse the production WavWriter so the trimmed file gets a clean
            // 44-byte header that any downstream tool — including iNat — will
            // happily decode.
            val writer = WavWriter(dstFile, sampleRate, channels = 1, bitsPerSample = BITS_PCM16)
            writer.open()
            writer.writeShorts(shorts, 0, shorts.size)
            writer.close()
            return dstFile
        }
    }

    private fun msToSamples(ms: Long, sampleRate: Int): Int {
        // 32-bit safe: a 10-minute clip at 48 kHz is ~28.8M samples — fits.
        val s = (ms * sampleRate) / MS_PER_SECOND
        return s.toInt().coerceAtLeast(0)
    }

    private fun leU16(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)

    private fun leU32(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or
            ((buf[o + 1].toInt() and 0xFF) shl 8) or
            ((buf[o + 2].toInt() and 0xFF) shl 16) or
            ((buf[o + 3].toInt() and 0xFF) shl 24)

    private const val HEADER_SIZE = 44
    private const val OFFSET_CHANNELS = 22
    private const val OFFSET_SAMPLE_RATE = 24
    private const val OFFSET_BITS = 34
    private const val OFFSET_DATA_SIZE = 40
    private const val BITS_PCM16 = 16
    private const val BYTES_PER_SAMPLE = 2
    private const val MS_PER_SECOND = 1000L
}
