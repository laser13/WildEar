package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Fake [BioacousticModel] for runner tests. Returns a single [WindowPrediction]
 * per call labelled with the supplied window timestamps so the test can assert
 * window slicing.
 */
private class RecordingFakeModel(
    override val expectedSampleRateHz: Int = 48_000,
    override val windowMs: Long = 3_000L,
) : BioacousticModel {
    override val modelId = "fake"
    override val modelVersion = "0"
    val calls = mutableListOf<Pair<Long, Long>>()
    var lastSampleRate: Int = -1

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
        lastSampleRate = sampleRateHz
        calls += windowStartMs to windowEndMs
        return listOf(
            WindowPrediction(
                startMs = windowStartMs,
                endMs = windowEndMs,
                taxonScientificName = "Fakus testus",
                taxonCommonName = null,
                confidence = 0.5f,
            ),
        )
    }

    override fun close() = Unit
}

/**
 * Integration test for [InferenceRunner].
 *
 * `app/src/test/resources/spike_fixtures/` is intentionally empty in Spec 1 (see
 * `MODEL_SPIKE.md` "Deferred work"), so this test synthesises a 5-second mono
 * 16-bit silent WAV via the production [WavWriter] to verify both the WAV
 * reader contract and the slicing math.
 */
class InferenceRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeSilentWav(durationSeconds: Int, sampleRate: Int = 48_000): File {
        val file = tmp.newFile("silence_${durationSeconds}s.wav")
        val writer = WavWriter(file, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        val total = durationSeconds * sampleRate
        val chunk = ShortArray(sampleRate) // 1 second of zeros
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
    fun `slices 5s WAV into three 3s windows at 1s hop`() = runTest {
        val wav = writeSilentWav(durationSeconds = 5)
        val model = RecordingFakeModel()
        val runner = InferenceRunner(model, hopSeconds = 1f)

        val out = runner.run(wav, latitude = null, longitude = null, observedAtMillis = 0L)

        // 5 s @ window=3 / hop=1 => floor((5-3)/1)+1 = 3 windows.
        assertThat(out).hasSize(3)
        assertThat(model.calls).containsExactly(
            0L to 3_000L,
            1_000L to 4_000L,
            2_000L to 5_000L,
        ).inOrder()
        assertThat(model.lastSampleRate).isEqualTo(48_000)
        assertThat(runner.progress.value).isEqualTo(1.0f)
    }

    @Test
    fun `WAV shorter than window yields no predictions but progress still 1`() = runTest {
        val wav = writeSilentWav(durationSeconds = 2) // shorter than 3 s window
        val model = RecordingFakeModel()
        val runner = InferenceRunner(model)

        val out = runner.run(wav, null, null, 0L)

        assertThat(out).isEmpty()
        assertThat(model.calls).isEmpty()
        assertThat(runner.progress.value).isEqualTo(1.0f)
    }

    @Test
    fun `48k WAV is resampled to model's 32k expected rate before slicing`() = runTest {
        val wav = writeSilentWav(durationSeconds = 6, sampleRate = 48_000)
        val model = RecordingFakeModel(expectedSampleRateHz = 32_000, windowMs = 5_000L)
        val runner = InferenceRunner(model, hopSeconds = 1f)

        val out = runner.run(wav, null, null, 0L)

        // After resample to 32k there are 6 s of audio. 5 s window @ 1 s hop
        // gives floor((6-5)/1)+1 = 2 windows. Model sees the model rate, not
        // the WAV's native rate.
        assertThat(out).hasSize(2)
        assertThat(model.lastSampleRate).isEqualTo(32_000)
        assertThat(model.calls).containsExactly(
            0L to 5_000L,
            1_000L to 6_000L,
        ).inOrder()
    }

    @Test
    fun `gate returning DOWNRANK skips all windows when model confidence is low`() = runTest {
        val wav = writeSilentWav(durationSeconds = 5)
        val model = RecordingFakeModel()  // returns confidence 0.5, below 0.7 override threshold
        val alwaysDownrankGate = YamNetGate { _, _ ->
            YamNetGateResult(
                biologicalScore = 0.02f,
                backgroundScore = 0.85f,
                recommendation = GateRecommendation.DOWNRANK,
            )
        }
        val runner = InferenceRunner(model, hopSeconds = 1f, yamNetGate = alwaysDownrankGate)

        val out = runner.run(wav, latitude = null, longitude = null, observedAtMillis = 0L)

        // No predictions emitted — DOWNRANK + confidence 0.5 < 0.7 override threshold
        assertThat(out).isEmpty()
        assertThat(runner.progress.value).isEqualTo(1.0f)
    }
}
