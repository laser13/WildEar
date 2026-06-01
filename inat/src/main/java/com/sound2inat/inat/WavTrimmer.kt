package com.sound2inat.inat

import com.sound2inat.audio.WavPcmReader
import com.sound2inat.audio.WavWriter
import java.io.File
import java.io.RandomAccessFile

/**
 * Pure-JVM WAV slicer. Reads a mono 16-bit PCM WAV (the format produced by
 * [com.sound2inat.audio.WavWriter]) and writes a new WAV containing only
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
        val parsed = WavPcmReader.readHeader(File(srcPath))
        val sampleRate = parsed.sampleRateHz.toLong()
        val totalSamples = parsed.totalSamples
        RandomAccessFile(srcPath, "r").use { raf ->
            val startSample = msToSamples(startMs.coerceAtLeast(0L), sampleRate).coerceAtMost(totalSamples)
            val endSample = msToSamples(endMs, sampleRate).coerceAtMost(totalSamples)
            check(endSample > startSample) {
                "Trim range $startMs..$endMs ms collapses to empty (file is ${totalSamples * 1000L / sampleRate} ms)"
            }

            // Read only the samples we need. PCM is 2 bytes/sample so we can
            // seek straight past the header + the prefix we're discarding.
            raf.seek(WavPcmReader.HEADER_SIZE + startSample * BYTES_PER_SAMPLE)
            val sliceLen = endSample - startSample
            // sliceLen fits in Int: clips trimmed for iNat upload are well under 2 GiB in memory
            val raw = ByteArray(sliceLen.toInt() * BYTES_PER_SAMPLE)
            raf.readFully(raw)
            val shorts = ShortArray(sliceLen.toInt())
            for (i in shorts.indices) {
                val lo = raw[2 * i].toInt() and 0xFF
                val hi = raw[2 * i + 1].toInt()
                shorts[i] = ((hi shl 8) or lo).toShort()
            }
            // Reuse the production WavWriter so the trimmed file gets a clean
            // 44-byte header that any downstream tool — including iNat — will
            // happily decode.
            val writer = WavWriter(
                dstFile,
                sampleRate.toInt(),
                channels = 1,
                bitsPerSample = WavPcmReader.BITS_PER_SAMPLE
            )
            writer.open()
            writer.writeShorts(shorts, 0, shorts.size)
            writer.close()
            return dstFile
        }
    }

    private fun msToSamples(ms: Long, sampleRate: Long): Long =
        (ms * sampleRate) / MS_PER_SECOND

    private const val BYTES_PER_SAMPLE = 2
    private const val MS_PER_SECOND = 1000L
}
