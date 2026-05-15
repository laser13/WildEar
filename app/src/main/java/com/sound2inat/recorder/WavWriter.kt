package com.sound2inat.recorder

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Open for testing — subclasses (e.g. fakes in unit tests) may override [open] and
 * [close] to inject failure modes. Subclasses overriding [open] as a no-op MUST NOT
 * then call [writeBytes] / [writeShorts]; the underlying streams are null and will
 * NPE.
 */
open class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
) {
    private var out: BufferedOutputStream? = null
    private var rawOut: FileOutputStream? = null
    private var dataBytesWritten: Long = 0L
    private var bytesSinceLastPatch: Long = 0L

    open fun open() {
        require(channels == 1) { "Only mono supported in Spec 1" }
        require(bitsPerSample == 16) { "Only 16-bit PCM supported in Spec 1" }
        val raw = FileOutputStream(file).also { writeHeaderPlaceholder(it) }
        rawOut = raw
        out = BufferedOutputStream(raw)
        dataBytesWritten = 0L
        bytesSinceLastPatch = 0L
    }

    fun writeBytes(buf: ByteArray, off: Int, len: Int) {
        out!!.write(buf, off, len)
        dataBytesWritten += len
        bytesSinceLastPatch += len
        if (bytesSinceLastPatch >= PATCH_INTERVAL_BYTES) {
            out!!.flush()
            patchHeader()
            bytesSinceLastPatch = 0L
        }
    }

    fun writeShorts(buf: ShortArray, off: Int, len: Int) {
        val bytes = ByteArray(len * 2)
        var bi = 0
        for (i in 0 until len) {
            val s = buf[off + i].toInt()
            bytes[bi++] = (s and 0xFF).toByte()
            bytes[bi++] = ((s ushr 8) and 0xFF).toByte()
        }
        writeBytes(bytes, 0, bytes.size)
    }

    open fun close() {
        out?.flush()
        // Force PCM data to stable storage. patchHeader() syncs the header on a
        // separate fd, so without this sync the PCM payload can stay in page
        // cache while the header on disk advertises bytes that aren't durable.
        rawOut?.fd?.sync()
        out?.close()
        out = null
        rawOut = null
        require(dataBytesWritten <= 0xFFFF_FFFFL - 36L) {
            "WAV data exceeds 4 GiB RIFF limit (dataBytesWritten=$dataBytesWritten)"
        }
        patchHeader()
    }

    private fun writeHeaderPlaceholder(stream: FileOutputStream) {
        stream.write(ByteArray(HEADER_SIZE))
    }

    private fun patchHeader() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.writeIntLe((dataBytesWritten + 36L).toInt())
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            raf.write("fmt ".toByteArray(Charsets.US_ASCII))
            raf.writeIntLe(16)
            raf.writeShortLe(1)
            raf.writeShortLe(channels.toShort())
            raf.writeIntLe(sampleRate)
            raf.writeIntLe(byteRate)
            raf.writeShortLe(blockAlign)
            raf.writeShortLe(bitsPerSample.toShort())
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.writeIntLe(dataBytesWritten.toInt())
            // Force header to stable storage. Without this, on a sudden kill the
            // last patch can stay in page cache and the WAV becomes unreadable.
            raf.fd.sync()
        }
    }

    private fun RandomAccessFile.writeIntLe(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLe(v: Short) {
        val i = v.toInt()
        write(i and 0xFF)
        write((i ushr 8) and 0xFF)
    }

    companion object {
        const val HEADER_SIZE = 44

        /** ~10 s at 48 kHz / 16-bit / mono before flushing and patching the header. */
        private const val PATCH_INTERVAL_BYTES = 960_000L
    }
}
