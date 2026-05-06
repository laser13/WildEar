package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveInferenceEngineTest {

    private val sampleRateHz = 48_000
    private val windowSamples = sampleRateHz * 3       // 144_000
    private val hopSamples = sampleRateHz * 3 / 2      // 72_000

    private fun fakeModel(label: String, confidence: Float) = object : BioacousticModel {
        override val modelId = "fake"
        override val modelVersion = "0"
        override val expectedSampleRateHz = 48_000
        override val windowMs = 3_000L
        override suspend fun load(modelFile: java.io.File, labelsFile: java.io.File) {}
        override suspend fun predict(
            pcmFloat32: FloatArray, sampleRateHz: Int,
            latitude: Double?, longitude: Double?, observedAtMillis: Long,
            windowStartMs: Long, windowEndMs: Long,
        ): List<WindowPrediction> = listOf(
            WindowPrediction(
                startMs = windowStartMs, endMs = windowEndMs,
                taxonScientificName = label, taxonCommonName = null,
                confidence = confidence, source = modelId,
            ),
        )
        override fun close() {}
    }

    @Test
    fun `emits prediction when one full window is fed`() = runTest(UnconfinedTestDispatcher()) {
        val model = fakeModel("Turdus merula", 0.9f)
        val engine = LiveInferenceEngine(
            model = model,
            yamNetGate = null,
            spectralSubtractor = null,            // disable to isolate timing
            applyHighPass = false,                 // disable for predictability
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
        )
        val collected = mutableListOf<WindowPrediction>()
        val collector = backgroundScope.launch { engine.predictions.toList(collected) }
        engine.start(backgroundScope)
        engine.feed(FloatArray(windowSamples) { 0.5f })
        runCurrent()
        engine.stop()
        runCurrent()

        assertThat(collected.size).isAtLeast(1)
        assertThat(collected[0].taxonScientificName).isEqualTo("Turdus merula")
        assertThat(collected[0].endMs - collected[0].startMs).isEqualTo(3_000L)
        collector.cancel()
    }

    @Test
    fun `subsequent windows hop by 1500ms`() = runTest(UnconfinedTestDispatcher()) {
        val model = fakeModel("X x", 0.5f)
        val engine = LiveInferenceEngine(
            model = model, yamNetGate = null, spectralSubtractor = null, applyHighPass = false,
            sampleRateHz = sampleRateHz, windowSamples = windowSamples, hopSamples = hopSamples,
        )
        val collected = mutableListOf<WindowPrediction>()
        val collector = backgroundScope.launch { engine.predictions.toList(collected) }
        engine.start(backgroundScope)
        engine.feed(FloatArray(windowSamples + hopSamples) { 0.1f })
        runCurrent()
        engine.stop()
        runCurrent()

        assertThat(collected).hasSize(2)
        assertThat(collected[1].startMs).isEqualTo(1_500L)
        assertThat(collected[1].endMs).isEqualTo(4_500L)
        collector.cancel()
    }

    @Test
    fun `DOWNRANK gate with low-confidence model suppresses predictions`() = runTest(UnconfinedTestDispatcher()) {
        val emitted = mutableListOf<WindowPrediction>()
        val model = object : BioacousticModel {
            override val modelId = "fake"
            override val modelVersion = "0"
            override val expectedSampleRateHz = 48_000
            override val windowMs = 3_000L
            override suspend fun load(modelFile: java.io.File, labelsFile: java.io.File) {}
            override suspend fun predict(
                pcmFloat32: FloatArray, sampleRateHz: Int,
                latitude: Double?, longitude: Double?, observedAtMillis: Long,
                windowStartMs: Long, windowEndMs: Long,
            ): List<WindowPrediction> = listOf(
                WindowPrediction(
                    startMs = windowStartMs, endMs = windowEndMs,
                    taxonScientificName = "Fakus testus", taxonCommonName = null,
                    confidence = 0.30f,  // below 0.7 HIGH_CONFIDENCE_OVERRIDE
                )
            )
            override fun close() {}
        }
        // Gate returns DOWNRANK — low-confidence predictions should be suppressed
        val downrankGate = YamNetGate { _, _ ->
            YamNetGateResult(
                biologicalScore = 0.02f,
                backgroundScore = 0.85f,
                recommendation = GateRecommendation.DOWNRANK,
            )
        }
        val engine = LiveInferenceEngine(
            model = model, yamNetGate = downrankGate, spectralSubtractor = null, applyHighPass = false,
            sampleRateHz = sampleRateHz, windowSamples = windowSamples, hopSamples = hopSamples,
        )
        engine.start(backgroundScope)
        val collector = backgroundScope.launch { engine.predictions.collect { emitted += it } }
        engine.feed(FloatArray(windowSamples) { 0.1f })
        runCurrent()
        engine.stop()
        runCurrent()

        assertThat(emitted).isEmpty()
        collector.cancel()
    }

    @Test
    fun `stop without feed completes cleanly`() = runTest(UnconfinedTestDispatcher()) {
        val engine = LiveInferenceEngine(
            model = fakeModel("x", 0f),
            yamNetGate = null, spectralSubtractor = null, applyHighPass = false,
            sampleRateHz = sampleRateHz, windowSamples = windowSamples, hopSamples = hopSamples,
        )
        engine.start(backgroundScope)
        engine.stop()  // no exception, no hang
    }

    @Test
    fun `stop is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val engine = LiveInferenceEngine(
            model = fakeModel("x", 0f),
            yamNetGate = null, spectralSubtractor = null, applyHighPass = false,
            sampleRateHz = sampleRateHz, windowSamples = windowSamples, hopSamples = hopSamples,
        )
        engine.start(backgroundScope)
        engine.stop()
        engine.stop()  // second call is a no-op, must not throw
        engine.stop()
    }

    @Test
    fun `feed in tiny blocks still emits at correct cadence`() = runTest(UnconfinedTestDispatcher()) {
        val engine = LiveInferenceEngine(
            model = fakeModel("Y", 0.5f),
            yamNetGate = null, spectralSubtractor = null, applyHighPass = false,
            sampleRateHz = sampleRateHz, windowSamples = windowSamples, hopSamples = hopSamples,
        )
        val collected = mutableListOf<WindowPrediction>()
        val collector = backgroundScope.launch { engine.predictions.toList(collected) }
        engine.start(backgroundScope)
        val totalSamples = windowSamples + hopSamples  // expect 2 windows
        val chunk = 1024
        var sent = 0
        while (sent < totalSamples) {
            val n = (totalSamples - sent).coerceAtMost(chunk)
            engine.feed(FloatArray(n) { 0.05f })
            sent += n
        }
        runCurrent()
        engine.stop()
        runCurrent()

        assertThat(collected).hasSize(2)
        assertThat(collected[0].startMs).isEqualTo(0L)
        assertThat(collected[1].startMs).isEqualTo(1_500L)
        collector.cancel()
    }
}
