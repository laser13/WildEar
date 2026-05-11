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
    private val windowSamples = sampleRateHz * 3 // 144_000
    private val hopSamples = sampleRateHz * 3 / 2 // 72_000

    private fun fakeModel(label: String, confidence: Float) = object : BioacousticModel {
        override val modelId = "fake"
        override val modelVersion = "0"
        override val expectedSampleRateHz = 48_000
        override val windowMs = 3_000L
        override suspend fun load(modelFile: java.io.File, labelsFile: java.io.File) = Unit
        override suspend fun predict(
            pcmFloat32: FloatArray,
            sampleRateHz: Int,
            latitude: Double?,
            longitude: Double?,
            observedAtMillis: Long,
            windowStartMs: Long,
            windowEndMs: Long,
        ): List<WindowPrediction> = listOf(
            WindowPrediction(
                startMs = windowStartMs,
                endMs = windowEndMs,
                taxonScientificName = label,
                taxonCommonName = null,
                confidence = confidence,
                source = modelId,
            ),
        )
        override fun close() = Unit
        override fun newInstance(): BioacousticModel = this
    }

    @Test
    fun `emits prediction when one full window is fed`() = runTest(UnconfinedTestDispatcher()) {
        val model = fakeModel("Turdus merula", 0.9f)
        val engine = LiveInferenceEngine(
            model = model,
            yamNetGate = null,
            spectralSubtractor = null, // disable to isolate timing
            usePreprocessing = false, // disable for predictability
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
            model = model,
            yamNetGate = null,
            spectralSubtractor = null,
            usePreprocessing = false,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
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
            override suspend fun load(modelFile: java.io.File, labelsFile: java.io.File) = Unit
            override suspend fun predict(
                pcmFloat32: FloatArray,
                sampleRateHz: Int,
                latitude: Double?,
                longitude: Double?,
                observedAtMillis: Long,
                windowStartMs: Long,
                windowEndMs: Long,
            ): List<WindowPrediction> = listOf(
                WindowPrediction(
                    startMs = windowStartMs,
                    endMs = windowEndMs,
                    taxonScientificName = "Fakus testus",
                    taxonCommonName = null,
                    confidence = 0.30f, // below 0.7 HIGH_CONFIDENCE_OVERRIDE
                )
            )
            override fun close() = Unit
            override fun newInstance(): BioacousticModel = this
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
            model = model,
            yamNetGate = downrankGate,
            spectralSubtractor = null,
            usePreprocessing = false,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
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
            yamNetGate = null,
            spectralSubtractor = null,
            usePreprocessing = false,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
        )
        engine.start(backgroundScope)
        engine.stop() // no exception, no hang
    }

    @Test
    fun `stop is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val engine = LiveInferenceEngine(
            model = fakeModel("x", 0f),
            yamNetGate = null,
            spectralSubtractor = null,
            usePreprocessing = false,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
        )
        engine.start(backgroundScope)
        engine.stop()
        engine.stop() // second call is a no-op, must not throw
        engine.stop()
    }

    @Test
    fun `feed in tiny blocks still emits at correct cadence`() = runTest(UnconfinedTestDispatcher()) {
        val engine = LiveInferenceEngine(
            model = fakeModel("Y", 0.5f),
            yamNetGate = null,
            spectralSubtractor = null,
            usePreprocessing = false,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
        )
        val collected = mutableListOf<WindowPrediction>()
        val collector = backgroundScope.launch { engine.predictions.toList(collected) }
        engine.start(backgroundScope)
        val totalSamples = windowSamples + hopSamples // expect 2 windows
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

    // ── Preprocessing flag tests ──────────────────────────────────────────────

    /** Spy SpectralSubtractor that records whether process() was called. */
    private class SpySpectralSubtractor : SpectralSubtractor() {
        var called = false
        override fun process(window: FloatArray): FloatArray {
            called = true
            return super.process(window)
        }
    }

    /**
     * Verify [LiveInferenceEngine.usePreprocessing] defaults to false (raw-first).
     * This is a structural test — it checks the flag value directly rather than
     * observable audio effects (HPF accuracy is covered in AudioPreprocessorTest).
     */
    @Test
    fun `usePreprocessing defaults to false (raw-first)`() {
        val engine = LiveInferenceEngine(
            model = fakeModel("x", 0f),
            yamNetGate = null,
            spectralSubtractor = null,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
        )
        assertThat(engine.usePreprocessing).isFalse()
    }

    @Test
    fun `usePreprocessing=true flag is stored and readable`() {
        val engine = LiveInferenceEngine(
            model = fakeModel("x", 0f),
            yamNetGate = null,
            spectralSubtractor = null,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
            usePreprocessing = true,
        )
        assertThat(engine.usePreprocessing).isTrue()
    }

    /**
     * SpectralSubtractor that signals a latch when process() is first called.
     * Used to reliably detect whether the preprocessing path was taken,
     * independently of test-scheduler timing.
     */
    private class LatchSpectralSubtractor : SpectralSubtractor() {
        private val latch = java.util.concurrent.CountDownLatch(1)
        var called = false
        fun awaitCalledOrTimeout(ms: Long): Boolean {
            return latch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        override fun process(window: FloatArray): FloatArray {
            called = true
            latch.countDown()
            return super.process(window)
        }
    }

    @Test
    fun `usePreprocessing=false skips spectral subtractor even when one is provided`() = runTest(
        UnconfinedTestDispatcher()
    ) {
        val spy = SpySpectralSubtractor()
        val engine = LiveInferenceEngine(
            model = fakeModel("X", 0.5f),
            yamNetGate = null,
            spectralSubtractor = spy,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
            usePreprocessing = false, // explicit — subtractor must NOT be called
        )
        engine.start(backgroundScope)
        engine.feed(FloatArray(windowSamples) { 0.1f })
        runCurrent()
        engine.stop()
        runCurrent()

        assertThat(spy.called).isFalse()
    }

    // ── Backpressure / queue-rejection accounting ─────────────────────────────

    /**
     * Engine subclass that always reports [trySendWindow] failure. Simulates a
     * channel-rejection scenario (closed channel, or — hypothetically — a
     * non-default `BufferOverflow` policy) so we can verify the engine still
     * keeps emitted/consumed/dropped counts consistent. Without this seam the
     * default `BUFFERED + DROP_OLDEST` channel makes the failure branch
     * unreachable in unit tests.
     */
    private class RejectingEngine(
        model: BioacousticModel,
        sampleRateHz: Int,
        windowSamples: Int,
        hopSamples: Int,
    ) : LiveInferenceEngine(
        model = model,
        yamNetGate = null,
        spectralSubtractor = null,
        sampleRateHz = sampleRateHz,
        windowSamples = windowSamples,
        hopSamples = hopSamples,
        usePreprocessing = false,
    ) {
        override fun trySendWindow(window: Window): Boolean = false

        // Silence the architectural-drift assertion: this test deliberately
        // exercises the failure-accounting path, it is not a real drift event.
        override fun onSendRejected() = Unit
    }

    @Test
    fun `windows rejected by trySend are tracked in droppedOnFull`() {
        val engine = RejectingEngine(
            model = fakeModel("X", 0.5f),
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
        )
        val totalWindows = 5
        val totalSamples = windowSamples + (totalWindows - 1) * hopSamples
        engine.feed(FloatArray(totalSamples) { 0.05f })

        assertThat(engine.droppedOnFull.value).isEqualTo(totalWindows)
        assertThat(engine.backlog.value).isEqualTo(0)
    }

    @Test
    fun `slow consumer with happy-path channel keeps emitted equals consumed plus droppedOnFull`() = runTest(
        UnconfinedTestDispatcher()
    ) {
        // Default BUFFERED+DROP_OLDEST channel: trySend never fails on an open
        // queue, so droppedOnFull stays at 0. The worker runs on Dispatchers.Default
        // (real wallclock), not the test scheduler, so we coordinate with a latch
        // to wait until the slow consumer has actually processed at least one
        // window before tearing the engine down.
        val processed = java.util.concurrent.atomic.AtomicInteger(0)
        val firstWindowProcessed = java.util.concurrent.CountDownLatch(1)
        val slowModel = object : BioacousticModel {
            override val modelId = "slow"
            override val modelVersion = "0"
            override val expectedSampleRateHz = 48_000
            override val windowMs = 3_000L
            override suspend fun load(modelFile: java.io.File, labelsFile: java.io.File) = Unit
            override suspend fun predict(
                pcmFloat32: FloatArray,
                sampleRateHz: Int,
                latitude: Double?,
                longitude: Double?,
                observedAtMillis: Long,
                windowStartMs: Long,
                windowEndMs: Long,
            ): List<WindowPrediction> {
                // Real-time delay (worker is on Dispatchers.Default).
                Thread.sleep(50)
                processed.incrementAndGet()
                firstWindowProcessed.countDown()
                return emptyList()
            }
            override fun close() = Unit
            override fun newInstance(): BioacousticModel = this
        }
        val engine = LiveInferenceEngine(
            model = slowModel,
            yamNetGate = null,
            spectralSubtractor = null,
            usePreprocessing = false,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
            queueCapacity = 2,
        )
        engine.start(backgroundScope)
        // Feed enough samples to produce many windows back-to-back; with
        // queueCapacity=2 and a slow consumer some will be silently evicted
        // by the channel itself. We don't assert on those — only that the
        // explicit-failure counter remains 0 when the channel is open.
        val windowsToProduce = 12
        val totalSamples = windowSamples + (windowsToProduce - 1) * hopSamples
        engine.feed(FloatArray(totalSamples) { 0.05f })
        // Wait for at least one window to flow end-to-end through the queue
        // and the slow consumer (real-time, not virtual). 5 s is generous.
        val firstSeen = firstWindowProcessed.await(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        engine.stop()

        // Invariant under default BUFFERED+DROP_OLDEST: trySend never reports
        // failure for an open channel, so the safety-net counter is zero.
        assertThat(engine.droppedOnFull.value).isEqualTo(0)
        // Sanity check: the slow consumer must actually have processed at
        // least one window. Without this, a zero-windows run would pass the
        // droppedOnFull assertion vacuously.
        assertThat(firstSeen).isTrue()
        assertThat(processed.get()).isGreaterThan(0)
    }

    @Test
    fun `usePreprocessing=true invokes spectral subtractor per window`() = runTest(UnconfinedTestDispatcher()) {
        val latchSub = LatchSpectralSubtractor()
        // Use a tiny window (1024 samples) to keep the FFT inside SpectralSubtractor fast.
        val tinyWindow = 1024
        val tinyHop = 512
        val engine = LiveInferenceEngine(
            model = fakeModel("X", 0.5f),
            yamNetGate = null,
            spectralSubtractor = latchSub,
            sampleRateHz = sampleRateHz,
            windowSamples = tinyWindow,
            hopSamples = tinyHop,
            usePreprocessing = true,
        )
        engine.start(backgroundScope)
        engine.feed(FloatArray(tinyWindow) { 0.1f })
        // Wait up to 5 s of real time for the Default-thread worker to invoke the subtractor,
        // bypassing the virtual-clock drain-timeout issue in stop().
        val called = latchSub.awaitCalledOrTimeout(5_000L)
        engine.stop()

        assertThat(called).isTrue()
    }
}
