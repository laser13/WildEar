package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Fake model for parallel runner tests. Records processed window start times
 * and whether close() was called. Each instance has a distinct [id].
 */
private class TrackingFakeModel(
    private val id: String,
    override val expectedSampleRateHz: Int = 48_000,
    override val windowMs: Long = 3_000L,
) : BioacousticModel {
    override val modelId: String = id
    override val modelVersion: String = "0"
    val processedStartMs = mutableListOf<Long>()
    var closed = false

    override suspend fun load(modelFile: File, labelsFile: File) = Unit

    override suspend fun predict(
        pcmFloat32: FloatArray,
        sampleRateHz: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<WindowPrediction> {
        processedStartMs += windowStartMs
        return listOf(
            WindowPrediction(
                startMs = windowStartMs,
                endMs = windowEndMs,
                taxonScientificName = "Fakus $id",
                taxonCommonName = null,
                confidence = 0.5f,
            ),
        )
    }

    override fun close() { closed = true }
    override fun newInstance(): BioacousticModel = TrackingFakeModel(id, expectedSampleRateHz, windowMs)
}

class ParallelInferenceRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * 12 s at 48 kHz → 10 windows (window=3 s / hop=1 s):
     *   frames = 1 + (12×48000 − 3×48000) / 48000 = 10
     * 6 s at 48 kHz → 4 windows: frames = 1 + (6−3)×48000/48000 = 4
     */
    private fun writeSilentWav(durationSeconds: Int, sampleRate: Int = 48_000): File {
        val file = tmp.newFile("sil_${durationSeconds}s_${sampleRate}.wav")
        val writer = WavWriter(file, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        val total = durationSeconds * sampleRate
        val chunk = ShortArray(sampleRate)
        var written = 0
        while (written < total) {
            val n = minOf(chunk.size, total - written)
            writer.writeShorts(chunk, 0, n)
            written += n
        }
        writer.close()
        return file
    }

    @Test
    fun `parallel path covers all windows with no gaps or duplicates`() = runTest {
        val wav = writeSilentWav(durationSeconds = 12) // 10 windows
        val fake1 = TrackingFakeModel("m1")
        val fake2 = TrackingFakeModel("m2")
        // Fails to compile until InferenceRunner accepts List<BioacousticModel>.
        val runner = InferenceRunner(listOf(fake1, fake2), hopSeconds = 1f)

        runner.run(wav, null, null, 0L)

        val allStarts = (fake1.processedStartMs + fake2.processedStartMs).sorted()
        assertThat(allStarts).containsExactly(
            0L, 1_000L, 2_000L, 3_000L, 4_000L,
            5_000L, 6_000L, 7_000L, 8_000L, 9_000L,
        ).inOrder()
        assertThat(fake1.processedStartMs).containsNoneIn(fake2.processedStartMs)
    }

    @Test
    fun `parallel results are sorted by windowStartMs`() = runTest {
        val wav = writeSilentWav(durationSeconds = 12)
        val runner = InferenceRunner(
            listOf(TrackingFakeModel("m1"), TrackingFakeModel("m2")),
            hopSeconds = 1f,
        )

        val out = runner.run(wav, null, null, 0L)

        assertThat(out).hasSize(10)
        assertThat(out.map { it.startMs }).isInOrder()
    }

    @Test
    fun `parallel progress is non-decreasing and reaches 1`() = runTest {
        val wav = writeSilentWav(durationSeconds = 12)
        val runner = InferenceRunner(
            listOf(TrackingFakeModel("m1"), TrackingFakeModel("m2")),
            hopSeconds = 1f,
        )
        val progressValues = mutableListOf(0f)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runner.progress.collect { progressValues += it }
        }
        runner.run(wav, null, null, 0L)

        for (i in 1 until progressValues.size) {
            assertThat(progressValues[i]).isAtLeast(progressValues[i - 1])
        }
        assertThat(progressValues.last()).isEqualTo(1.0f)
    }

    @Test
    fun `sequential path calls single model for every window`() = runTest {
        val wav = writeSilentWav(durationSeconds = 6) // (6−3)/1+1 = 4 windows
        val fake = TrackingFakeModel("solo")
        val runner = InferenceRunner(listOf(fake), hopSeconds = 1f)

        runner.run(wav, null, null, 0L)

        assertThat(fake.processedStartMs).containsExactly(0L, 1_000L, 2_000L, 3_000L).inOrder()
        assertThat(runner.progress.value).isEqualTo(1.0f)
    }

    @Test
    fun `failure in one parallel coroutine propagates and both models are closed`() = runTest {
        val wav = writeSilentWav(durationSeconds = 12)
        val normal = TrackingFakeModel("normal")

        class FailingModel : BioacousticModel {
            override val modelId = "failing"
            override val modelVersion = "0"
            override val expectedSampleRateHz = 48_000
            override val windowMs = 3_000L
            var closed = false
            override suspend fun load(modelFile: File, labelsFile: File) = Unit
            override suspend fun predict(
                pcmFloat32: FloatArray, sampleRateHz: Int, latitude: Double?,
                longitude: Double?, observedAtMillis: Long,
                windowStartMs: Long, windowEndMs: Long,
            ): List<WindowPrediction> = throw RuntimeException("intentional failure")
            override fun close() { closed = true }
            override fun newInstance(): BioacousticModel = FailingModel()
        }

        val failing = FailingModel()
        val runner = InferenceRunner(listOf(normal, failing), hopSeconds = 1f)

        var thrown: Throwable? = null
        try {
            runner.run(wav, null, null, 0L)
        } catch (t: Throwable) {
            thrown = t
        }

        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
        assertThat(normal.closed).isTrue()
        assertThat(failing.closed).isTrue()
    }
}
