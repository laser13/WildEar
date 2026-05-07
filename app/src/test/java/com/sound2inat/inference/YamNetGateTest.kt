package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import com.sound2inat.modelmanager.ModelDescriptor
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class YamNetGateTest {
    @get:Rule val tmp = TemporaryFolder()

    // Minimal CSV: header + 521 rows. Bio=index 100 ("Bird"), Noise=index 0 ("Speech").
    private val labels521 = buildString {
        appendLine("index,mid,display_name")
        appendLine("0,/m/09x0r,Speech")
        appendLine("1,/m/abc,Engine")
        for (i in 2..99) appendLine("$i,/m/x$i,Other$i")
        appendLine("100,/m/015p6,Bird")
        appendLine("101,/m/03vt0,Frog")
        for (i in 102..520) appendLine("$i,/m/x$i,Dummy$i")
    }

    private fun buildGate(probs: FloatArray): YamNetTfliteGate {
        val modelFile = tmp.newFile("yamnet.tflite").apply { writeBytes(byteArrayOf(0)) }
        val labelsFile = tmp.newFile("yamnet.labels.csv").apply { writeText(labels521) }
        val fakeFactory = object : InterpreterFactory {
            override fun create(m: File, threads: Int): InterpreterApi = object : InterpreterApi {
                override val outputTensorCount = 1
                override fun getOutputShape(index: Int) = intArrayOf(1, probs.size)
                override fun run(input: Any, output: Any) {
                    @Suppress("UNCHECKED_CAST")
                    probs.copyInto((output as Array<FloatArray>)[0])
                }
                override fun runForMultipleOutputs(input: Any, outputs: Map<Int, Any>) = Unit
                override fun close() = Unit
            }
        }
        val fakeManager = object : ModelManager(tmp.root, OkHttpClient()) {
            override suspend fun stateFor(descriptor: ModelDescriptor) =
                ModelInstallState.Ready(modelFile, labelsFile)
            override suspend fun install(
                descriptor: ModelDescriptor,
                emit: (ModelInstallState) -> Unit,
            ) = Unit
            override fun remove(descriptor: ModelDescriptor) = Unit
        }
        return YamNetTfliteGate(fakeFactory, fakeManager)
    }

    // --- classify() result tests ---

    @Test
    fun `returns DOWNRANK when speech is top class and bio score below threshold`() = runTest {
        val probs = FloatArray(521)
        probs[0] = 0.90f // Speech (noise) is top class
        probs[100] = 0.05f // Bird bio score < 0.15
        val result = buildGate(probs).classify(FloatArray(16_000), 16_000)
        assertThat(result).isNotNull()
        assertThat(result!!.recommendation).isEqualTo(GateRecommendation.DOWNRANK)
        assertThat(result.biologicalScore).isWithin(1e-5f).of(0.05f)
    }

    @Test
    fun `returns PASS when bird score is above threshold`() = runTest {
        val probs = FloatArray(521)
        probs[100] = 0.80f // Bird high bio score
        probs[0] = 0.10f // Speech low
        val result = buildGate(probs).classify(FloatArray(16_000), 16_000)
        assertThat(result).isNotNull()
        assertThat(result!!.recommendation).isEqualTo(GateRecommendation.PASS)
        assertThat(result.biologicalScore).isWithin(1e-5f).of(0.80f)
    }

    @Test
    fun `returns PASS when top is noise but bio score is at or above threshold`() = runTest {
        val probs = FloatArray(521)
        probs[0] = 0.70f // Speech top
        probs[100] = 0.20f // Bird bio score >= 0.15 → still biological
        val result = buildGate(probs).classify(FloatArray(16_000), 16_000)
        assertThat(result).isNotNull()
        assertThat(result!!.recommendation).isEqualTo(GateRecommendation.PASS)
    }

    @Test
    fun `backgroundScore reflects highest noise class probability`() = runTest {
        val probs = FloatArray(521)
        probs[0] = 0.60f // Speech (noise)
        probs[1] = 0.30f // Engine (noise)
        probs[100] = 0.05f // Bird bio score
        val result = buildGate(probs).classify(FloatArray(16_000), 16_000)
        assertThat(result).isNotNull()
        assertThat(result!!.backgroundScore).isWithin(1e-5f).of(0.60f)
    }

    // --- fail-open tests ---

    @Test
    fun `returns null on interpreter creation exception — fail-open`() = runTest {
        val modelFile = tmp.newFile("boom.tflite").apply { writeBytes(byteArrayOf(0)) }
        val labelsFile = tmp.newFile("boom.csv").apply { writeText(labels521) }
        val throwingFactory = object : InterpreterFactory {
            override fun create(m: File, threads: Int): InterpreterApi = error("deliberate failure")
        }
        val fakeManager = object : ModelManager(tmp.root, OkHttpClient()) {
            override suspend fun stateFor(descriptor: ModelDescriptor) =
                ModelInstallState.Ready(modelFile, labelsFile)
            override suspend fun install(
                descriptor: ModelDescriptor,
                emit: (ModelInstallState) -> Unit,
            ) = Unit
            override fun remove(descriptor: ModelDescriptor) = Unit
        }
        val gate = YamNetTfliteGate(throwingFactory, fakeManager)
        assertThat(gate.classify(FloatArray(16_000), 16_000)).isNull()
    }

    @Test
    fun `returns null when model not yet installed — fail-open`() = runTest {
        val fakeFactory = object : InterpreterFactory {
            override fun create(m: File, threads: Int): InterpreterApi = error("should not be called")
        }
        val fakeManager = object : ModelManager(tmp.root, OkHttpClient()) {
            override suspend fun stateFor(descriptor: ModelDescriptor) = ModelInstallState.NotInstalled
            override suspend fun install(
                descriptor: ModelDescriptor,
                emit: (ModelInstallState) -> Unit,
            ) = Unit
            override fun remove(descriptor: ModelDescriptor) = Unit
        }
        val gate = YamNetTfliteGate(fakeFactory, fakeManager)
        assertThat(gate.classify(FloatArray(16_000), 16_000)).isNull()
    }

    // --- InferenceRunner soft-override test ---

    /**
     * High species confidence (>= 0.7) must override a DOWNRANK gate result and
     * include the prediction in InferenceRunner output.
     */
    @Test
    fun `high confidence species prediction overrides DOWNRANK gate in InferenceRunner`() = runTest {
        // Gate always returns DOWNRANK (noise window)
        val downrankGate = YamNetGate { _, _ ->
            YamNetGateResult(
                biologicalScore = 0.02f,
                backgroundScore = 0.85f,
                recommendation = GateRecommendation.DOWNRANK,
            )
        }

        // Model always returns a high-confidence prediction
        val highConfidenceModel = object : BioacousticModel {
            override val modelId = "fake"
            override val modelVersion = "0"
            override val expectedSampleRateHz = 16_000
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
            ) = listOf(
                WindowPrediction(
                    startMs = windowStartMs,
                    endMs = windowEndMs,
                    taxonScientificName = "Parus major",
                    taxonCommonName = "Great Tit",
                    confidence = 0.85f, // well above HIGH_CONFIDENCE_OVERRIDE (0.7)
                )
            )
            override fun close() = Unit
        }

        val runner = InferenceRunner(
            model = highConfidenceModel,
            hopSeconds = 3f,
            yamNetGate = downrankGate,
        )
        // Provide enough fake WAV data for at least one window (3 s at 16 kHz = 48 000 samples)
        val fakeWav = buildFakeWav(sampleRateHz = 16_000, durationSamples = 48_000)
        val results = runner.run(fakeWav, null, null, 0L)
        assertThat(results).isNotEmpty()
        assertThat(results.first().taxonScientificName).isEqualTo("Parus major")
    }

    /**
     * DOWNRANK gate with LOW species confidence must suppress the window.
     */
    @Test
    fun `low confidence prediction is suppressed when gate returns DOWNRANK`() = runTest {
        val downrankGate = YamNetGate { _, _ ->
            YamNetGateResult(
                biologicalScore = 0.02f,
                backgroundScore = 0.85f,
                recommendation = GateRecommendation.DOWNRANK,
            )
        }

        val lowConfidenceModel = object : BioacousticModel {
            override val modelId = "fake"
            override val modelVersion = "0"
            override val expectedSampleRateHz = 16_000
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
            ) = listOf(
                WindowPrediction(
                    startMs = windowStartMs,
                    endMs = windowEndMs,
                    taxonScientificName = "Parus major",
                    taxonCommonName = "Great Tit",
                    confidence = 0.30f, // below HIGH_CONFIDENCE_OVERRIDE (0.7)
                )
            )
            override fun close() = Unit
        }

        val runner = InferenceRunner(
            model = lowConfidenceModel,
            hopSeconds = 3f,
            yamNetGate = downrankGate,
        )
        val fakeWav = buildFakeWav(sampleRateHz = 16_000, durationSamples = 48_000)
        val results = runner.run(fakeWav, null, null, 0L)
        assertThat(results).isEmpty()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Builds a minimal 44-byte WAV header + silent PCM data that WavReader can parse. */
    private fun buildFakeWav(sampleRateHz: Int, durationSamples: Int): File {
        val dataBytes = durationSamples * 2 // 16-bit mono
        val file = tmp.newFile("fake.wav")
        file.outputStream().use { out ->
            fun writeInt32LE(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF)
                out.write((v shr 24) and 0xFF)
            }
            fun writeInt16LE(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
            out.write("RIFF".toByteArray()) // ChunkID
            writeInt32LE(36 + dataBytes) // ChunkSize
            out.write("WAVE".toByteArray()) // Format
            out.write("fmt ".toByteArray()) // Subchunk1ID
            writeInt32LE(16) // Subchunk1Size (PCM)
            writeInt16LE(1) // AudioFormat (PCM)
            writeInt16LE(1) // NumChannels (mono)
            writeInt32LE(sampleRateHz) // SampleRate
            writeInt32LE(sampleRateHz * 2) // ByteRate
            writeInt16LE(2) // BlockAlign
            writeInt16LE(16) // BitsPerSample
            out.write("data".toByteArray()) // Subchunk2ID
            writeInt32LE(dataBytes) // Subchunk2Size
            out.write(ByteArray(dataBytes)) // silent PCM samples
        }
        return file
    }
}
