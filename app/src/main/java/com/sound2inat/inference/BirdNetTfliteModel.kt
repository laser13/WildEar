package com.sound2inat.inference

import java.io.File

/**
 * BirdNET v2.4 raw-audio TFLite wrapper.
 *
 * Input contract (frozen in `MODEL_SPIKE.md`):
 *   shape `[1, 144000]`, FLOAT32, mono PCM at 48 kHz, normalised to `[-1, 1]`.
 * Output contract:
 *   shape `[1, 6522]`, FLOAT32 softmax over [Labels].
 *
 * NOT thread-safe — callers (e.g. [InferenceRunner]) must serialise calls
 * for a given instance.
 */
class BirdNetTfliteModel(
    private val factory: InterpreterFactory,
    private val topK: Int = 5,
    private val threads: Int = 2,
) : BioacousticModel {

    override val modelId: String = "birdnet_v2_4"
    override val modelVersion: String = "2.4"

    private var interp: InterpreterApi? = null
    private var labels: List<Label> = emptyList()

    override suspend fun load(modelFile: File, labelsFile: File) {
        interp = factory.create(modelFile, threads)
        labels = Labels.load(labelsFile)
    }

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
        require(sampleRateHz == 48_000) { "BirdNET v2.4 expects 48 kHz, got $sampleRateHz" }

        // BirdNET v2.4 raw-audio input shape: [1, 144000].
        val input = arrayOf(pcmFloat32)
        val output = arrayOf(FloatArray(labels.size))
        i.run(input, output)

        val probs = output[0]
        val k = topK.coerceAtMost(probs.size)
        val idx = probs.indices.sortedByDescending { probs[it] }.take(k)
        return idx.map { ki ->
            val l = labels[ki]
            WindowPrediction(
                startMs = windowStartMs,
                endMs = windowEndMs,
                taxonScientificName = l.scientificName,
                taxonCommonName = l.commonName,
                confidence = probs[ki],
            )
        }
    }

    override fun close() {
        interp?.close()
        interp = null
    }
}
