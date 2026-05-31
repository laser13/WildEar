package com.sound2inat.recorder

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultRecorderStopTimeoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A source whose read() never returns and never cooperatively cancels —
     * simulating a wedged native AudioRecord.read(). The pump coroutine will
     * be stuck inside read(); stop() must still return via its internal timeout.
     */
    private class WedgedSource : AudioRecordSource {
        override val sampleRate = 48_000
        override val channels = 1
        override val bitsPerSample = 16

        // Set true by the test (in a finally) to let the wedged read() thread exit
        // instead of leaking for the JVM lifetime. Stays false during the test so
        // read() remains blocked and stop()'s join timeout is genuinely exercised.
        @Volatile
        var released = false

        override suspend fun start() { /* no-op */ }

        override suspend fun read(buf: ShortArray, off: Int, len: Int): Int {
            // Block on a real thread ignoring coroutine cancellation: Thread.sleep
            // does not check isActive/isCancelled, so the pump coroutine is stuck
            // here regardless of Job.cancel() — simulating a wedged native
            // AudioRecord.read(). Exits only once the test releases it.
            while (!released) {
                Thread.sleep(20)
            }
            return 0
        }

        override suspend fun stop() { /* no-op */ }
    }

    /**
     * WavWriter subclass that skips all filesystem I/O.
     * Mirrors the pattern from ThrowingOnCloseWavWriter in Fakes.kt:
     * only open() and close() are `open` in WavWriter.
     * Safe to use with WedgedSource because read() never returns data,
     * so writeShorts is never invoked (which would NPE on null streams).
     */
    private class NoIoWavWriter(file: File) : WavWriter(file, 48_000, 1, 16) {
        override fun open() { /* no-op: avoids real filesystem I/O */ }
        override fun close() { /* no-op */ }
    }

    @Test
    fun `stop returns even when pump is wedged in read`() = runBlocking {
        // Use real Dispatchers.IO so the pump coroutine runs on a real thread
        // and Thread.sleep in WedgedSource.read() actually blocks it.
        // runBlocking (not runTest) so withTimeout measures real wall-clock time,
        // not virtual test time — required because the pump is stuck on a real thread.
        // stopJoinTimeoutMs=200 makes the test fast and deterministic.
        val source = WedgedSource()
        val recorder = DefaultRecorder(
            source = source,
            ioDispatcher = Dispatchers.IO,
            wavWriterFactory = { file -> NoIoWavWriter(file) },
            stopJoinTimeoutMs = 200L,
        )
        val target = tmp.newFile("rec.wav")
        recorder.start(target)

        try {
            // stop() must complete within a real-time bound well above the internal
            // join timeout (200ms) but far below "forever" (5000ms).
            val result = withTimeout(5_000) { recorder.stop() }
            assertThat(result.audioPath).isEqualTo(target.absolutePath)
        } finally {
            // Let the wedged read() thread exit so it does not leak past the test.
            source.released = true
        }
    }
}
