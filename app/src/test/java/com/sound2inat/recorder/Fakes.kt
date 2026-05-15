package com.sound2inat.recorder

import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin

class FakeAudioSource(
    private val emitFrames: Int,
    private val amplitude: Float = 0.5f,
) : AudioRecordSource {
    override val sampleRate = 48_000
    override val channels = 1
    override val bitsPerSample = 16
    private var pos = 0
    private var ended = false

    override suspend fun start() {
        pos = 0
        ended = false
    }

    override suspend fun read(buf: ShortArray, off: Int, len: Int): Int {
        if (ended) return 0
        val remaining = emitFrames - pos
        if (remaining <= 0) return 0
        val n = minOf(len, remaining)
        for (i in 0 until n) {
            val v = sin(2 * PI * 1_000.0 * (pos + i) / sampleRate) * amplitude * Short.MAX_VALUE
            buf[off + i] = v.toInt().toShort()
        }
        pos += n
        return n
    }

    override suspend fun stop() {
        ended = true
    }

    fun flush() {
        ended = true
    }
}

/**
 * Emits a fixed list of pre-built short blocks, one per `read()` call,
 * then returns 0 (EOF). Useful for asserting exact sample → float conversion.
 */
class ScriptedAudioSource(
    private val blocks: List<ShortArray>,
) : AudioRecordSource {
    override val sampleRate = 48_000
    override val channels = 1
    override val bitsPerSample = 16
    private var idx = 0
    private var ended = false

    override suspend fun start() {
        idx = 0
        ended = false
    }

    override suspend fun read(buf: ShortArray, off: Int, len: Int): Int {
        if (ended || idx >= blocks.size) return 0
        val b = blocks[idx++]
        val n = minOf(len, b.size)
        System.arraycopy(b, 0, buf, off, n)
        return n
    }

    override suspend fun stop() {
        ended = true
    }
}

class TestClock(start: Long = 0L) : Clock {
    private var t = start
    override fun nowMs(): Long {
        t += 1
        return t
    }
}

/**
 * A [WavWriter] subclass whose [close] always throws [IOException].
 * Used to verify that [DefaultRecorder.stop] still returns a [RecordingResult]
 * even when the underlying writer fails to close (e.g. disk full).
 */
class ThrowingOnCloseWavWriter(file: File) : WavWriter(file, 48_000, 1, 16) {
    override fun open() { /* no-op — avoid real filesystem I/O */ }
    override fun close() { throw IOException("Simulated disk-full error") }
}
