package com.sound2inat.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Canonical reader for mono 16-bit PCM WAV files produced by
 * [com.sound2inat.audio.WavWriter] (clean 44-byte header, fmt chunk size 16,
 * PCM `data` chunk immediately after at offset 36). NOT a general RIFF parser —
 * extra chunks (LIST/INFO) are unsupported.
 *
 * Single source of truth for WAV header parsing. Three access modes:
 *   - [readHeader]: parse the 44-byte header only (no PCM read).
 *   - [readMono16]: whole-file decode into a [ShortArray].
 *   - [stream]: block-wise streaming callback for bounded-memory passes.
 *
 * For random-access window reads (parallel inference), use [WavWindowReader].
 */
object WavPcmReader {

    const val HEADER_SIZE = 44
    const val BYTES_PER_SAMPLE = 2
    const val BITS_PER_SAMPLE = 16
    private const val DEFAULT_BLOCK_SAMPLES = 16_384

    /** Parsed WAV header. [totalSamples] is derived from the declared `data` chunk size. */
    data class Header(val sampleRateHz: Int, val totalSamples: Long)

    fun readLeUint16(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)

    fun readLeUint32(buf: ByteArray, o: Int): Long =
        (buf[o].toLong() and 0xFF) or
            ((buf[o + 1].toLong() and 0xFF) shl 8) or
            ((buf[o + 2].toLong() and 0xFF) shl 16) or
            ((buf[o + 3].toLong() and 0xFF) shl 24)

    /** Reads the 44-byte header, validates it, and returns rate + sample count. */
    fun readHeader(file: File): Header =
        RandomAccessFile(file, "r").use { raf -> parseHeader(raf) }

    /**
     * Reads and validates the header from an open [raf], leaving the file
     * pointer positioned at the start of the PCM data (offset [HEADER_SIZE]).
     * Clamps the declared `data` size to what the file actually contains so a
     * mismatched header never drives an over-read.
     */
    internal fun parseHeader(raf: RandomAccessFile): Header {
        val header = ByteArray(HEADER_SIZE).also { raf.readFully(it) }
        require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
            "Not a WAV file"
        }
        val channels = readLeUint16(header, 22)
        val sampleRate = readLeUint32(header, 24).toInt()
        val bits = readLeUint16(header, 34)
        require(channels == 1 && bits == BITS_PER_SAMPLE) {
            "Mono 16-bit PCM only (got ch=$channels bits=$bits)"
        }
        require(String(header, 36, 4) == "data") {
            "WAV 'data' chunk not at offset 36 — unsupported chunk layout"
        }
        val declared = readLeUint32(header, 40)
        require(declared in 0L..Int.MAX_VALUE.toLong()) {
            "WAV dataSize out of safe range: $declared bytes"
        }
        // Guard against a header that over-declares the data size relative to the
        // bytes actually present on disk (the legacy streamMono16 trusted the
        // header and could over-read). raf is positioned just past the header.
        val availableBytes = (raf.length() - HEADER_SIZE).coerceAtLeast(0L)
        val usableBytes = minOf(declared, availableBytes)
        return Header(sampleRateHz = sampleRate, totalSamples = usableBytes / BYTES_PER_SAMPLE)
    }

    /** Whole-file decode. Allocates one [ShortArray] of size `totalSamples`. */
    fun readMono16(file: File): Pair<ShortArray, Int> =
        RandomAccessFile(file, "r").use { raf ->
            val header = parseHeader(raf)
            val sampleCount = header.totalSamples.toInt()
            val raw = ByteArray(sampleCount * BYTES_PER_SAMPLE)
            raf.readFully(raw)
            val samples = ShortArray(sampleCount) { i ->
                val lo = raw[2 * i].toInt() and 0xFF
                val hi = raw[2 * i + 1].toInt()
                ((hi shl 8) or lo).toShort()
            }
            samples to header.sampleRateHz
        }

    /**
     * Streams the PCM payload in blocks of [blockSamples] samples. [onChunk] is
     * invoked with each decoded block and the absolute sample offset of its
     * first element. Never reads past the (clamped) declared data size.
     */
    fun stream(
        file: File,
        blockSamples: Int = DEFAULT_BLOCK_SAMPLES,
        onChunk: (chunk: ShortArray, startSample: Long) -> Unit,
    ) {
        require(blockSamples > 0) { "blockSamples must be positive" }
        RandomAccessFile(file, "r").use { raf ->
            val header = parseHeader(raf)
            val totalSamples = header.totalSamples
            val raw = ByteArray(blockSamples * BYTES_PER_SAMPLE)
            var startSample = 0L
            while (startSample < totalSamples) {
                val toRead = minOf(blockSamples.toLong(), totalSamples - startSample).toInt()
                raf.readFully(raw, 0, toRead * BYTES_PER_SAMPLE)
                val chunk = ShortArray(toRead)
                for (i in 0 until toRead) {
                    val lo = raw[2 * i].toInt() and 0xFF
                    val hi = raw[2 * i + 1].toInt()
                    chunk[i] = ((hi shl 8) or lo).toShort()
                }
                onChunk(chunk, startSample)
                startSample += toRead
            }
        }
    }
}
