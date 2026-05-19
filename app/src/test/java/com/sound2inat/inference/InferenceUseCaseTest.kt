package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.data.Settings
import com.sound2inat.modelmanager.ModelDescriptor
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.recorder.WavWriter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ProductionInferenceJob] (the production implementation of
 * [InferenceJob]) using a fake [BioacousticModel] and a real silent WAV.
 *
 * [Settings] is mocked via MockK since it wraps Android DataStore and cannot
 * be constructed without a [android.content.Context].
 */
class InferenceUseCaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fakeSettings(
        minConf: Float = 0.10f,
        minWindows: Int = 1,
        yamNetEnabled: Boolean = false,
        birdNetMetaEnabled: Boolean = false,
    ): Settings = mockk<Settings>().also { s ->
        every { s.minConfidenceDisplay } returns flowOf(minConf)
        every { s.minWindows } returns flowOf(minWindows)
        every { s.yamNetGateEnabled } returns flowOf(yamNetEnabled)
        every { s.birdNetMetaEnabled } returns flowOf(birdNetMetaEnabled)
        every { s.lastKnownLat } returns flowOf(null)
        every { s.lastKnownLon } returns flowOf(null)
    }

    private fun fakeModelManager(stubModel: File, stubLabels: File): ModelManager =
        object : ModelManager(tmp.newFolder("models_mgr"), OkHttpClient()) {
            override suspend fun stateFor(descriptor: ModelDescriptor): ModelInstallState =
                ModelInstallState.Ready(stubModel, stubLabels)
        }

    /**
     * Writes a silent WAV file of [durationSeconds] length at 48 kHz mono 16-bit.
     * Mirrors the helper in [InferenceRunnerTest].
     */
    private fun writeSilentWav(durationSeconds: Int = 4): File {
        val file = tmp.newFile("silence_${durationSeconds}s.wav")
        val writer = WavWriter(file, sampleRate = 48_000, channels = 1, bitsPerSample = 16)
        writer.open()
        val total = durationSeconds * 48_000
        val chunk = ShortArray(48_000)
        var written = 0
        while (written < total) {
            val n = minOf(chunk.size, total - written)
            writer.writeShorts(chunk, 0, n)
            written += n
        }
        writer.close()
        return file
    }

    private fun fakeBioacousticModel(
        predictions: (windowStartMs: Long, windowEndMs: Long) -> List<WindowPrediction>,
    ): BioacousticModel = object : BioacousticModel {
        override val modelId = "fake_birdnet"
        override val modelVersion = "0.0-test"
        override val expectedSampleRateHz = 48_000
        override val windowMs = 3_000L
        override suspend fun load(modelFile: File, labelsFile: File) = Unit
        override suspend fun predict(
            pcmFloat32: FloatArray,
            sampleRateHz: Int,
            latitude: Double?,
            longitude: Double?,
            observedAtMillis: Long,
            windowStartMs: Long,
            windowEndMs: Long,
        ): List<WindowPrediction> = predictions(windowStartMs, windowEndMs)
        override fun close() = Unit
        override fun newInstance(): BioacousticModel = this
    }

    private fun makeJob(
        model: BioacousticModel,
        settings: Settings,
        descriptor: ModelDescriptor = com.sound2inat.modelmanager.BirdNetV24.descriptor.copy(
            id = "fake_birdnet",
        ),
        modelManager: ModelManager = fakeModelManager(
            stubModel = tmp.newFile("fake.tflite"),
            stubLabels = tmp.newFile("fake.labels.txt"),
        ),
    ): ProductionInferenceJob = ProductionInferenceJob(
        models = listOf(model),
        descriptors = listOf(descriptor),
        modelManager = modelManager,
        settings = settings,
        yamNetGate = null,
        birdNetMeta = null,
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - one detection above threshold reaches InferenceOutcome Success`() = runTest {
        val settings = fakeSettings(minConf = 0.10f, minWindows = 1)
        val model = fakeBioacousticModel { startMs, endMs ->
            listOf(
                WindowPrediction(
                    startMs = startMs,
                    endMs = endMs,
                    taxonScientificName = "Turdus merula",
                    taxonCommonName = "Common Blackbird",
                    confidence = 0.92f,
                    source = "fake_birdnet",
                ),
            )
        }
        val wav = writeSilentWav(durationSeconds = 4)
        val job = makeJob(model, settings)

        val outcome = job.run(
            audioPath = wav.absolutePath,
            latitude = null,
            longitude = null,
            observedAtMillis = 0L,
            onProgress = {},
        )

        assertThat(outcome).isInstanceOf(InferenceOutcome.Success::class.java)
        val success = outcome as InferenceOutcome.Success
        assertThat(success.modelId).isEqualTo("fake_birdnet")
        assertThat(success.detections).hasSize(1)
        assertThat(success.detections[0].taxonScientificName).isEqualTo("Turdus merula")
    }

    @Test
    fun `model throwing an exception produces InferenceOutcome Failure`() = runTest {
        val settings = fakeSettings()
        val brokenModel = fakeBioacousticModel { _, _ -> throw RuntimeException("TFLite exploded") }
        val wav = writeSilentWav(durationSeconds = 4)
        val job = makeJob(brokenModel, settings)

        val outcome = job.run(
            audioPath = wav.absolutePath,
            latitude = null,
            longitude = null,
            observedAtMillis = 0L,
            onProgress = {},
        )

        assertThat(outcome).isInstanceOf(InferenceOutcome.Failure::class.java)
        val failure = outcome as InferenceOutcome.Failure
        assertThat(failure.message).contains("fake_birdnet")
    }

    @Test
    fun `no model installed returns InferenceOutcome Failure`() = runTest {
        val settings = fakeSettings()
        val model = fakeBioacousticModel { _, _ -> emptyList() }
        val descriptor = com.sound2inat.modelmanager.BirdNetV24.descriptor.copy(id = "fake_birdnet")
        val notInstalledManager = object : ModelManager(tmp.newFolder("models_not_installed"), OkHttpClient()) {
            override suspend fun stateFor(d: ModelDescriptor): ModelInstallState =
                ModelInstallState.NotInstalled
        }
        val wav = writeSilentWav(durationSeconds = 4)
        val job = ProductionInferenceJob(
            models = listOf(model),
            descriptors = listOf(descriptor),
            modelManager = notInstalledManager,
            settings = settings,
            yamNetGate = null,
            birdNetMeta = null,
        )

        val outcome = job.run(
            audioPath = wav.absolutePath,
            latitude = null,
            longitude = null,
            observedAtMillis = 0L,
            onProgress = {},
        )

        assertThat(outcome).isInstanceOf(InferenceOutcome.Failure::class.java)
    }

    @Test
    fun `prediction below minConfidence threshold is excluded from detections`() = runTest {
        val settings = fakeSettings(minConf = 0.50f, minWindows = 1)
        val model = fakeBioacousticModel { startMs, endMs ->
            listOf(
                WindowPrediction(
                    startMs = startMs,
                    endMs = endMs,
                    taxonScientificName = "Fakus lowconfidus",
                    taxonCommonName = null,
                    confidence = 0.20f, // below 0.50 threshold
                    source = "fake_birdnet",
                ),
            )
        }
        val wav = writeSilentWav(durationSeconds = 4)
        val job = makeJob(model, settings)

        val outcome = job.run(
            audioPath = wav.absolutePath,
            latitude = null,
            longitude = null,
            observedAtMillis = 0L,
            onProgress = {},
        )

        assertThat(outcome).isInstanceOf(InferenceOutcome.Success::class.java)
        val success = outcome as InferenceOutcome.Success
        assertThat(success.detections).isEmpty()
    }

    @Test
    fun `ProductionPerchAnalysisJob parallelism=2 calls newInstance for all instances and closes them`() = runTest {
        val modelFile = tmp.newFile("perch.tflite").apply { writeBytes(byteArrayOf(0)) }
        val labelsFile = tmp.newFile("perch_labels.csv").apply {
            // Row 0 = dataset tag (filtered); row 1 = species.
            writeText("inat2024_fsd50k\nRana temporaria\n")
        }
        // 12 s WAV at 32 kHz (Perch's native rate — no resampling needed).
        // frames = 1 + (12*32000 - 5*32000) / 32000 = 8 windows.
        val perchWav = run {
            val f = tmp.newFile("perch_silence.wav")
            val w = WavWriter(f, sampleRate = 32_000, channels = 1, bitsPerSample = 16)
            w.open()
            val total = 12 * 32_000
            val chunk = ShortArray(32_000)
            var written = 0
            while (written < total) {
                val n = minOf(chunk.size, total - written)
                w.writeShorts(chunk, 0, n)
                written += n
            }
            w.close()
            f
        }

        var newInstanceCalls = 0
        val closedFlags = mutableListOf<Boolean>()

        fun makeTracker(): BioacousticModel {
            val idx = closedFlags.size
            closedFlags += false
            return object : BioacousticModel {
                override val modelId = ModelIds.PERCH
                override val modelVersion = "2"
                override val expectedSampleRateHz = 32_000
                override val windowMs = 5_000L
                override suspend fun load(modelFile: File, labelsFile: File) = Unit
                override suspend fun predict(
                    pcmFloat32: FloatArray,
                    sampleRateHz: Int,
                    latitude: Double?,
                    longitude: Double?,
                    observedAtMillis: Long,
                    windowStartMs: Long,
                    windowEndMs: Long,
                ) = listOf(
                    WindowPrediction(windowStartMs, windowEndMs, "Rana temporaria", null, 0.5f, ModelIds.PERCH),
                )
                override fun close() { closedFlags[idx] = true }
                override fun newInstance(): BioacousticModel {
                    newInstanceCalls++
                    return makeTracker()
                }
            }
        }

        val root = makeTracker()
        val job = ProductionPerchAnalysisJob(
            models = listOf(root),
            modelManager = fakeModelManager(modelFile, labelsFile),
            settings = fakeSettings(),
            yamNetGate = null,
            parallelism = 2, // fails to compile until parameter exists
        )

        val outcome = job.run(perchWav.absolutePath, null, null, 0L) {}

        assertThat(outcome).isInstanceOf(PerchAnalysisOutcome.Success::class.java)
        // All parallelism instances are created via newInstance() — the DI singleton is not consumed.
        assertThat(newInstanceCalls).isEqualTo(2)
        assertThat(closedFlags).hasSize(3) // root (not consumed) + 2 instances
        assertThat(closedFlags[0]).isFalse() // root/DI-singleton is NOT closed
        assertThat(closedFlags.drop(1).all { it }).isTrue() // both instances are closed
    }

    @Test
    fun `ProductionPerchAnalysisJob load failure closes already-loaded instances`() = runTest {
        val modelFile = tmp.newFile("pf_model.tflite").apply { writeBytes(byteArrayOf(0)) }
        val labelsFile = tmp.newFile("pf_labels.csv").apply {
            writeText("inat2024_fsd50k\nRana temporaria\n")
        }

        var loadCount = 0
        var firstInstanceClosed = false
        var secondInstanceClosed = false
        var newInstanceCallCount = 0

        // Instances returned by newInstance(): first loads OK, second throws.
        val firstInstance = object : BioacousticModel {
            override val modelId = ModelIds.PERCH
            override val modelVersion = "2"
            override val expectedSampleRateHz = 32_000
            override val windowMs = 5_000L
            override suspend fun load(modelFile: File, labelsFile: File) { loadCount++ }
            override suspend fun predict(
                pcmFloat32: FloatArray,
                sampleRateHz: Int,
                latitude: Double?,
                longitude: Double?,
                observedAtMillis: Long,
                windowStartMs: Long,
                windowEndMs: Long,
            ) = emptyList<WindowPrediction>()
            override fun close() { firstInstanceClosed = true }
            override fun newInstance(): BioacousticModel = this
        }

        val secondInstance = object : BioacousticModel {
            override val modelId = ModelIds.PERCH
            override val modelVersion = "2"
            override val expectedSampleRateHz = 32_000
            override val windowMs = 5_000L
            override suspend fun load(modelFile: File, labelsFile: File) {
                throw RuntimeException("OOM on second load")
            }
            override suspend fun predict(
                pcmFloat32: FloatArray,
                sampleRateHz: Int,
                latitude: Double?,
                longitude: Double?,
                observedAtMillis: Long,
                windowStartMs: Long,
                windowEndMs: Long,
            ) = emptyList<WindowPrediction>()
            override fun close() { secondInstanceClosed = true }
            override fun newInstance(): BioacousticModel = this
        }

        val root = object : BioacousticModel {
            override val modelId = ModelIds.PERCH
            override val modelVersion = "2"
            override val expectedSampleRateHz = 32_000
            override val windowMs = 5_000L
            override suspend fun load(modelFile: File, labelsFile: File) = Unit // root is never loaded
            override suspend fun predict(
                pcmFloat32: FloatArray,
                sampleRateHz: Int,
                latitude: Double?,
                longitude: Double?,
                observedAtMillis: Long,
                windowStartMs: Long,
                windowEndMs: Long,
            ) = emptyList<WindowPrediction>()
            override fun close() = Unit // root is never closed (it's the DI singleton)
            override fun newInstance(): BioacousticModel {
                newInstanceCallCount++
                return if (newInstanceCallCount == 1) firstInstance else secondInstance
            }
        }

        val job = ProductionPerchAnalysisJob(
            models = listOf(root),
            modelManager = fakeModelManager(modelFile, labelsFile),
            settings = fakeSettings(),
            yamNetGate = null,
            parallelism = 2,
        )

        // Use any valid WAV — even a short silent one, since we never get to inference.
        val shortWav = tmp.newFile("short.wav").also { f ->
            val w = WavWriter(f, sampleRate = 32_000, channels = 1, bitsPerSample = 16)
            w.open()
            w.writeShorts(ShortArray(320), 0, 320)
            w.close()
        }

        val outcome = job.run(shortWav.absolutePath, null, null, 0L) {}

        assertThat(outcome).isInstanceOf(PerchAnalysisOutcome.Failure::class.java)
        assertThat(loadCount).isEqualTo(1) // first instance loaded successfully
        assertThat(firstInstanceClosed).isTrue() // first instance closed in guard
        assertThat(secondInstanceClosed).isFalse() // second instance never loaded, so not closed
    }
}
