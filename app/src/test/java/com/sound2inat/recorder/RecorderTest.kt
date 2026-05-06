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
}
