package com.sound2inat.inference

import java.io.File
import kotlin.math.exp

/**
 * BirdNET v2.4 raw-audio TFLite wrapper.
 *
 * Input contract (frozen in `MODEL_SPIKE.md`):
 *   shape `[1, 144000]`, FLOAT32, mono PCM at 48 kHz, normalised to `[-1, 1]`.
 * Output contract:
 *   shape `[1, 6522]`, FLOAT32 raw logits (NOT softmax — confirmed against the
 *   whoBIRD reference; `MODEL_SPIKE.md` was wrong about this). We apply a
 *   sigmoid per class so [WindowPrediction.confidence] is in `[0, 1]`.
 *
 * NOT thread-safe — callers (e.g. [InferenceRunner]) must serialise calls
 * for a given instance.
 */
class BirdNetTfliteModel(
    private val factory: InterpreterFactory,
    // 4 CPU threads is the practical sweet spot on modern phones (most have
    // 6–8 cores; over-threading hurts due to cache contention). Combined
    // with TFLite's XNNPACK CPU kernels (enabled by default in 2.16) and
    // the optional NNAPI delegate in TfliteInterpreterFactory, this keeps
    // BirdNET ahead of real time on mid-range devices.
    private val threads: Int = 4,
) : BioacousticModel {

    override val modelId: String = ModelIds.BIRDNET
    override val modelVersion: String = "2.4"
    override val expectedSampleRateHz: Int = AudioConstants.BIRDNET_SAMPLE_RATE_HZ
    override val windowMs: Long = 3_000L

    private var interp: InterpreterApi? = null
    private var labels: List<Label> = emptyList()

    override suspend fun load(modelFile: File, labelsFile: File) {
        interp?.close()
        interp = null
        interp = factory.create(modelFile, threads)
        labels = Labels.load(labelsFile)
    }

    @Suppress("LongParameterList")
    override suspend fun predict(
        pcmFloat32: FloatArray,
        sampleRateHz: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<WindowPrediction> {
        val i = interp ?: error("Model not loaded")
        require(sampleRateHz == AudioConstants.BIRDNET_SAMPLE_RATE_HZ) { "BirdNET v2.4 expects 48 kHz, got $sampleRateHz" }

        // BirdNET v2.4 raw-audio input shape: [1, 144000].
        val input = arrayOf(pcmFloat32)
        val output = arrayOf(FloatArray(labels.size))
        i.run(input, output)

        val logits = output[0]
        // Sigmoid is monotonic — sort on raw logits, apply sigmoid only for included entries.
        val idx = logits.indices
            .filter { sigmoid(logits[it]) >= PREDICTION_FLOOR }
            .sortedByDescending { logits[it] }
        return idx.map { ki ->
            val l = labels[ki]
            WindowPrediction(
                startMs = windowStartMs,
                endMs = windowEndMs,
                taxonScientificName = l.scientificName,
                taxonCommonName = l.commonName,
                confidence = sigmoid(logits[ki]),
                source = modelId,
            )
        }
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    private companion object {
        const val PREDICTION_FLOOR = 0.01f
    }

    override fun newInstance(): BioacousticModel = BirdNetTfliteModel(factory, threads)

    override fun close() {
        interp?.close()
        interp = null
    }
}
