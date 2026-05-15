package com.sound2inat.recorder

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RecorderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `produces a WAV with the recorded duration`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val source = FakeAudioSource(emitFrames = 48_000) // 1 second
        val recorder = DefaultRecorder(source, clock = TestClock(), ioDispatcher = testDispatcher, externalScope = this)
        val target = tmp.newFile("rec.wav")
        recorder.start(target)
        testScheduler.advanceUntilIdle()
        val result = recorder.stop()
        assertThat(result.audioPath).isEqualTo(target.absolutePath)
        assertThat(target.length()).isAtLeast(44L + 48_000L * 2)
    }

    @Test
    fun `cancel deletes the file`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val source = FakeAudioSource(emitFrames = 0)
        val recorder = DefaultRecorder(source, clock = TestClock(), ioDispatcher = testDispatcher, externalScope = this)
        val target = tmp.newFile("rec.wav")
        recorder.start(target)
        recorder.cancel()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `audioBlocks emits float blocks scaled to -1 to 1`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val source = ScriptedAudioSource(
            blocks = listOf(shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE, 16384)),
        )
        val recorder = DefaultRecorder(
            source,
            clock = TestClock(),
            ioDispatcher = testDispatcher,
            externalScope = this,
        )
        val target = tmp.newFile("rec.wav")
        val collected = mutableListOf<FloatArray>()
        val collectorJob = launch { recorder.audioBlocks.collect { collected += it } }
        recorder.start(target)
        testScheduler.advanceUntilIdle()
        recorder.stop()
        collectorJob.cancel()

        assertThat(collected).hasSize(1)
        assertThat(collected[0][0]).isWithin(1e-4f).of(0f)
        assertThat(collected[0][1]).isWithin(1e-4f).of(1f)
        assertThat(collected[0][2]).isLessThan(-0.99f)
        assertThat(collected[0][3]).isWithin(1e-3f).of(16384f / Short.MAX_VALUE)
    }

    @Test
    fun `sampleRate matches underlying source`() = runTest {
        val source = FakeAudioSource(emitFrames = 0)
        val recorder = DefaultRecorder(source, clock = TestClock(), externalScope = this)
        assertThat(recorder.sampleRate).isEqualTo(source.sampleRate)
    }

    @Test
    fun `stop returns RecordingResult even when writer close throws IOException`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val source = FakeAudioSource(emitFrames = 0)
        val target = tmp.newFile("rec.wav")
        val recorder = DefaultRecorder(
            source,
            clock = TestClock(start = 1000L),
            ioDispatcher = testDispatcher,
            externalScope = this,
            wavWriterFactory = { ThrowingOnCloseWavWriter(it) },
        )
        recorder.start(target)
        testScheduler.advanceUntilIdle()
        // Must not throw, must return a valid RecordingResult
        val result = recorder.stop()
        assertThat(result.audioPath).isEqualTo(target.absolutePath)
        assertThat(result.durationMs).isAtLeast(0L)
    }

    @Test
    fun `rmsLevel emits non-negative bounded values`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val source = FakeAudioSource(emitFrames = 48_000, amplitude = 0.5f)
        val recorder = DefaultRecorder(source, clock = TestClock(), ioDispatcher = testDispatcher, externalScope = this)
        val target = tmp.newFile("rec.wav")
        recorder.start(target)
        testScheduler.advanceUntilIdle()
        recorder.rmsLevel.test {
            val v = awaitItem()
            assertThat(v).isAtLeast(0.0f)
            assertThat(v).isAtMost(1.0f)
            cancelAndIgnoreRemainingEvents()
        }
        recorder.stop()
    }

    // ── Ring-buffer RMS history tests ────────────────────────────────────────

    /**
     * Helper: builds a [ScriptedAudioSource] where each "block" is a constant
     * amplitude short array of [frameSize] samples. Each block produces a
     * distinct, predictable RMS value via [amplitudes].
     */
    private fun scriptedSource(amplitudes: List<Float>, frameSize: Int = 64): ScriptedAudioSource {
        val blocks = amplitudes.map { amp ->
            val value = (amp * Short.MAX_VALUE).toInt().toShort()
            ShortArray(frameSize) { value }
        }
        return ScriptedAudioSource(blocks)
    }

    @Test
    fun `rmsHistory is empty immediately after start`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val source = ScriptedAudioSource(blocks = emptyList())
        val recorder = DefaultRecorder(
            source,
            clock = TestClock(),
            ioDispatcher = testDispatcher,
            externalScope = this,
        )
        val target = tmp.newFile("rec.wav")
        recorder.start(target)
        // Before pump processes any blocks: history must be empty
        assertThat(recorder.rmsHistory.value).isEmpty()
        testScheduler.advanceUntilIdle()
        recorder.stop()
    }

    @Test
    fun `rmsHistory grows from 0 up to HISTORY_SIZE`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val blockCount = 50
        val amplitudes = List(blockCount) { idx -> (idx + 1) / 100f }
        val source = scriptedSource(amplitudes)
        val recorder = DefaultRecorder(
            source,
            clock = TestClock(),
            ioDispatcher = testDispatcher,
            externalScope = this,
        )
        val target = tmp.newFile("rec.wav")

        val snapshots = mutableListOf<FloatArray>()
        val collectorJob = launch { recorder.rmsHistory.collect { snapshots += it.copyOf() } }

        recorder.start(target)
        testScheduler.advanceUntilIdle()
        recorder.stop()
        collectorJob.cancel()

        // First snapshot is the empty reset from start()
        assertThat(snapshots.first()).isEmpty()
        // After all blocks: size == blockCount (< HISTORY_SIZE)
        assertThat(snapshots.last().size).isEqualTo(blockCount)
        // Size must never exceed HISTORY_SIZE
        assertThat(snapshots.map { it.size }.max()).isAtMost(Recorder.HISTORY_SIZE)
        // Each consecutive snapshot grows by exactly 1
        val nonEmpty = snapshots.filter { it.isNotEmpty() }
        for (i in 1 until nonEmpty.size) {
            assertThat(nonEmpty[i].size).isEqualTo(nonEmpty[i - 1].size + 1)
        }
        // Verify the last value is the real RMS of the last block, not a stale zero.
        // Last block: idx=49, amplitude=50/100f=0.5f, all samples constant.
        // RMS = round(0.5 * Short.MAX_VALUE) / Short.MAX_VALUE ≈ 0.5f (allow 1 LSB drift).
        val lastAmp = blockCount / 100f // 0.5f
        val lastSampleValue = (lastAmp * Short.MAX_VALUE).toInt().toShort()
        val expectedLastRms = lastSampleValue / Short.MAX_VALUE.toFloat()
        assertThat(snapshots.last().last()).isWithin(1e-5f).of(expectedLastRms)
    }

    @Test
    fun `rmsHistory keeps last HISTORY_SIZE values in oldest-first order after overflow`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val cap = Recorder.HISTORY_SIZE // 200
        val totalBlocks = cap + 50 // 250 — 50 blocks wrap around
        // Each block i gets a unique amplitude i+1 (in range 0.001..0.250 so still ≤1)
        val amplitudes = List(totalBlocks) { idx -> (idx + 1) / 1000f }
        val source = scriptedSource(amplitudes)
        val recorder = DefaultRecorder(
            source,
            clock = TestClock(),
            ioDispatcher = testDispatcher,
            externalScope = this,
        )
        val target = tmp.newFile("rec.wav")
        recorder.start(target)
        testScheduler.advanceUntilIdle()
        recorder.stop()

        val history = recorder.rmsHistory.value
        assertThat(history.size).isEqualTo(cap)

        // The last cap blocks are [totalBlocks-cap .. totalBlocks-1] (0-indexed),
        // i.e. amplitudes (totalBlocks-cap+1)/1000 .. totalBlocks/1000.
        // Because each short array is constant (all samples = amplitude*MAX_VALUE),
        // RMS == amplitude, so we can verify the last few values directly.
        //
        // Block index (0-based) b → amplitude = (b+1)/1000f → RMS ≈ (b+1)/1000f
        // First block kept: b = totalBlocks - cap = 50 → amp = 51/1000 = 0.051
        // Last  block kept: b = totalBlocks - 1 = 249 → amp = 250/1000 = 0.25
        val firstKeptAmp = (totalBlocks - cap + 1) / 1000f
        val lastKeptAmp = totalBlocks / 1000f

        // Allow ±1 LSB tolerance because computeRms is float arithmetic
        assertThat(history.first()).isWithin(2e-4f).of(firstKeptAmp)
        assertThat(history.last()).isWithin(2e-4f).of(lastKeptAmp)

        // Verify monotonically increasing (amplitudes strictly increase)
        for (i in 1 until history.size) {
            assertThat(history[i]).isGreaterThan(history[i - 1])
        }
    }
}
