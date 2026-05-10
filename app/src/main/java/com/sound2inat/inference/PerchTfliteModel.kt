package com.sound2inat.inference

import com.sound2inat.modelmanager.LabelsFormat
import java.io.File
import kotlin.math.exp

/**
 * Google Perch v2 raw-audio TFLite wrapper.
 *
 * Input contract:
 *   shape `[1, 160000]`, FLOAT32, mono PCM at 32 kHz, normalised to `[-1, 1]`.
 * Output contract:
 *   shape `[1, 14795]`, FLOAT32 raw logits — softmax-normalized over all classes so
 *   [WindowPrediction.confidence] is a proper probability in `[0, 1]`.
 *
 * Labels file is a CSV of bare scientific names (parsed via
 * [LabelsFormat.PerchScientificName]). Row 0 is a dataset tag and is filtered
 * out downstream by [DetectionAggregator]'s binomial check.
 *
 * NOT thread-safe — callers must serialise calls per instance.
 */
class PerchTfliteModel(
    private val factory: InterpreterFactory,
    private val threads: Int = 4,
) : BioacousticModel {

    override val modelId: String = ModelIds.PERCH
    override val modelVersion: String = "2"
    override val expectedSampleRateHz: Int = 32_000
    override val windowMs: Long = 5_000L

    private var interp: InterpreterApi? = null
    private var labels: List<Label> = emptyList()

    /**
     * Index of the `label` output tensor (shape `[1, labels.size]`). Perch v2
     * ships four outputs (`embedding`, `spatial_embedding`, `spectrogram`,
     * `label`); the order can vary across exports, so we discover it by
     * matching shape rather than hard-coding.
     */
    private var logitsTensorIndex: Int = -1

    override suspend fun load(modelFile: File, labelsFile: File) {
        interp?.close()
        interp = null
        val i = factory.create(modelFile, threads)
        interp = i
        labels = Labels.load(labelsFile, format = LabelsFormat.PerchScientificName)
        logitsTensorIndex = (0 until i.outputTensorCount)
            .firstOrNull { idx ->
                val shape = i.getOutputShape(idx)
                shape.size == 2 && shape[0] == 1 && shape[1] == labels.size
            }
            ?: error(
                "Perch model has no output tensor matching [1, ${labels.size}]. " +
                    "Found shapes: " +
                    (0 until i.outputTensorCount).joinToString { idx ->
                        i.getOutputShape(idx).toList().toString()
                    },
            )
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
        require(sampleRateHz == 32_000) { "Perch v2 expects 32 kHz, got $sampleRateHz" }
        check(logitsTensorIndex >= 0) { "logits tensor index not resolved (load() not called?)" }

        val input = arrayOf(pcmFloat32)
        val labelBuf = arrayOf(FloatArray(labels.size))
        i.runForMultipleOutputs(input, mapOf(logitsTensorIndex to labelBuf))

        val logits = labelBuf[0]
        val probabilities = softmax(logits)
        val idx = probabilities.indices
            .filter { probabilities[it] >= PREDICTION_FLOOR }
            .sortedByDescending { probabilities[it] }
        return idx.map { ki ->
            val l = labels[ki]
            WindowPrediction(
                startMs = windowStartMs,
                endMs = windowEndMs,
                taxonScientificName = l.scientificName,
                taxonCommonName = l.commonName,
                confidence = probabilities[ki],
                source = modelId,
            )
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.max()
        val exps = FloatArray(logits.size) { exp(logits[it] - maxLogit) }
        val sumExp = exps.sum()
        return FloatArray(exps.size) { exps[it] / sumExp }
    }

    private companion object {
        const val PREDICTION_FLOOR = 0.01f
    }

    override fun newInstance(): BioacousticModel = PerchTfliteModel(factory, threads)

    override fun close() {
        interp?.close()
        interp = null
    }
}
