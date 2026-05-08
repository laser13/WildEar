package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

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
        val model = RecordingFakeModel() // returns confidence 0.5, below 0.7 override threshold
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

    // ── Preprocessing flag tests ──────────────────────────────────────────────

    /**
     * Model that captures the first window it receives so we can inspect
     * whether samples were modified by the preprocessing pipeline.
     */
    private class CapturingFakeModel(
        override val expectedSampleRateHz: Int = 48_000,
        override val windowMs: Long = 3_000L,
    ) : BioacousticModel {
        override val modelId = "capturing"
        override val modelVersion = "0"
        var capturedWindow: FloatArray? = null

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
            if (capturedWindow == null) capturedWindow = pcmFloat32.copyOf()
            return emptyList()
        }
        override fun close() = Unit
    }

    /** SpectralSubtractor spy: tracks whether process() was called. */
    private class SpySpectralSubtractor : SpectralSubtractor() {
        var called = false
        override fun process(window: FloatArray): FloatArray {
            called = true
            return super.process(window)
        }
    }

    @Test
    fun `default usePreprocessing=false passes normalized-only samples to model`() = runTest {
        // Write a WAV with a non-zero DC offset (constant amplitude) — the high-pass
        // filter would drive this to zero, so we can detect whether it was applied.
        val file = tmp.newFile("dc_offset.wav")
        val sampleRate = 48_000
        val writer = WavWriter(file, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        // 5 seconds of constant value = 16384 (half of Short.MAX_VALUE) — strong DC
        val dc = ShortArray(5 * sampleRate) { 16_384.toShort() }
        writer.writeShorts(dc, 0, dc.size)
        writer.close()

        val model = CapturingFakeModel()
        val runner = InferenceRunner(model, hopSeconds = 1f) // usePreprocessing = false (default)

        runner.run(file, null, null, 0L)

        val window = requireNotNull(model.capturedWindow) { "No window was captured" }
        // Normalized DC value is 16384 / 32767 ≈ 0.5
        // Without high-pass filter the mean of a constant signal stays ~0.5.
        // After high-pass filter (removes DC) the mean converges toward 0.
        val mean = window.fold(0.0) { acc, s -> acc + s } / window.size
        assertThat(abs(mean)).isGreaterThan(0.3)
    }

    @Test
    fun `usePreprocessing=true high-pass filter removes DC offset before model receives samples`() = runTest {
        val file = tmp.newFile("dc_hpf.wav")
        val sampleRate = 48_000
        val writer = WavWriter(file, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        // 5 seconds of constant DC at 16384
        val dc = ShortArray(5 * sampleRate) { 16_384.toShort() }
        writer.writeShorts(dc, 0, dc.size)
        writer.close()

        val model = CapturingFakeModel()
        val runner = InferenceRunner(model, hopSeconds = 1f, usePreprocessing = true)

        runner.run(file, null, null, 0L)

        val window = requireNotNull(model.capturedWindow) { "No window was captured" }
        // High-pass Butterworth at 250 Hz removes DC; mean of output should be near 0.
        val mean = window.fold(0.0) { acc, s -> acc + s } / window.size
        assertThat(abs(mean)).isLessThan(0.01)
    }

    @Test
    fun `usePreprocessing=false skips spectral subtractor even when one is provided`() = runTest {
        val wav = writeSilentWav(durationSeconds = 5)
        val model = RecordingFakeModel()
        val spy = SpySpectralSubtractor()
        // Preprocessing is OFF — subtractor should never be called
        val runner = InferenceRunner(
            model,
            hopSeconds = 1f,
            spectralSubtractor = spy,
            usePreprocessing = false,
        )

        runner.run(wav, null, null, 0L)

        assertThat(spy.called).isFalse()
    }

    @Test
    fun `usePreprocessing=true invokes spectral subtractor per window`() = runTest {
        val wav = writeSilentWav(durationSeconds = 5)
        val model = RecordingFakeModel()
        val spy = SpySpectralSubtractor()
        val runner = InferenceRunner(
            model,
            hopSeconds = 1f,
            spectralSubtractor = spy,
            usePreprocessing = true,
        )

        runner.run(wav, null, null, 0L)

        assertThat(spy.called).isTrue()
    }
}
