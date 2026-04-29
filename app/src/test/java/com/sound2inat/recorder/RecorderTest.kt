package com.sound2inat.recorder

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
