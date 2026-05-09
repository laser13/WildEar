package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.exp

private fun softmax(logits: FloatArray): FloatArray {
    val maxLogit = logits.max()
    val exps = FloatArray(logits.size) { exp(logits[it] - maxLogit) }
    val sumExp = exps.sum()
    return FloatArray(exps.size) { exps[it] / sumExp }
}

/**
 * Fake interpreter that mimics Perch's 4-output layout: `embedding[1,1536]`
 * at index 0, then 3 dummies, with the `label[1, output.size]` tensor at
 * [labelIndex] (default 3, matching the real export order). This lets the
 * test verify the shape-based lookup picks the correct tensor.
 */
private class PerchFakeInterpreterFactory(
    private val output: FloatArray,
    private val labelIndex: Int = 3,
) : InterpreterFactory {
    var lastInput: FloatArray? = null
    var closed = false

    override fun create(modelFile: File, threads: Int): InterpreterApi =
        object : InterpreterApi {
            override val outputTensorCount: Int = 4
            override fun getOutputShape(index: Int): IntArray = when (index) {
                labelIndex -> intArrayOf(1, output.size)
                0 -> intArrayOf(1, 1536) // embedding
                1 -> intArrayOf(1, 16, 4, 1536) // spatial_embedding
                else -> intArrayOf(1, 500, 128) // spectrogram
            }
            override fun run(input: Any, outputArg: Any) {
                error("Perch path uses runForMultipleOutputs")
            }
            override fun runForMultipleOutputs(input: Any, outputs: Map<Int, Any>) {
                @Suppress("UNCHECKED_CAST")
                val inArr = input as Array<FloatArray>
                lastInput = inArr[0]
                val out = outputs[labelIndex] ?: error("label tensor not requested")

                @Suppress("UNCHECKED_CAST")
                val outArr = out as Array<FloatArray>
                System.arraycopy(output, 0, outArr[0], 0, output.size)
            }
            override fun close() { closed = true }
        }
}

class PerchTfliteModelTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `predictions above floor ordered by confidence`() = runTest {
        // Row 0 is the CSV header/dataset tag — dropped by Labels.parsePerch.
        // Rows 1-3 are the actual classes; output tensor must be size 3.
        val labels = tmp.newFile("labels.csv").apply {
            writeText("inat2024_fsd50k\nSylvia melanothorax\nPasser domesticus\nNoise\n")
        }
        val model = tmp.newFile("model.tflite").apply { writeBytes(byteArrayOf(0)) }
        val logits = floatArrayOf(0.7f, 0.2f, -4.0f)
        val fake = PerchFakeInterpreterFactory(output = logits)
        // softmax(-4.0) over these logits ≈ 0.0056 < 0.01 floor → Noise excluded
        val m = PerchTfliteModel(fake)
        m.load(model, labels)

        val pcm = FloatArray(32_000 * 5)
        val out = m.predict(pcm, 32_000, null, null, 0L, 0L, 5_000L)

        assertThat(out).hasSize(2)
        assertThat(out.map { it.taxonScientificName })
            .containsExactly("Sylvia melanothorax", "Passer domesticus")
            .inOrder()
        val expectedProb = softmax(logits)[0]
        assertThat(out[0].confidence).isWithin(1e-6f).of(expectedProb)
        assertThat(out[0].taxonCommonName).isNull()
        assertThat(out[0].startMs).isEqualTo(0L)
        assertThat(out[0].endMs).isEqualTo(5_000L)
    }

    @Test
    fun `predict requires 32 kHz sample rate`() = runTest {
        val labels = tmp.newFile("labels.csv").apply { writeText("tag\nA b\n") }
        val model = tmp.newFile("model.tflite").apply { writeBytes(byteArrayOf(0)) }
        val fake = PerchFakeInterpreterFactory(floatArrayOf(0.9f))
        val m = PerchTfliteModel(fake)
        m.load(model, labels)

        val ex = runCatching {
            m.predict(FloatArray(0), 48_000, null, null, 0L, 0L, 0L)
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `close releases the interpreter`() = runTest {
        val labels = tmp.newFile("labels.csv").apply { writeText("tag\nA b\n") }
        val model = tmp.newFile("model.tflite").apply { writeBytes(byteArrayOf(0)) }
        val fake = PerchFakeInterpreterFactory(floatArrayOf(1.0f))
        val m = PerchTfliteModel(fake)
        m.load(model, labels)

        m.close()
        assertThat(fake.closed).isTrue()
    }

    @Test
    fun `newInstance returns independent model - closing original does not break copy`() = runTest {
        // Row 0 = dataset tag (filtered); row 1 = species label.
        val labels = tmp.newFile("ni_labels.csv").apply {
            writeText("inat2024_fsd50k\nRana temporaria\n")
        }
        val modelFile = tmp.newFile("ni_model.tflite").apply { writeBytes(byteArrayOf(0)) }
        // 1 label after filtering; logit 2.0 softmax → probability 1.0 > 0.01 floor → included.
        val fake = PerchFakeInterpreterFactory(output = floatArrayOf(2.0f), labelIndex = 3)

        val original = PerchTfliteModel(fake)
        original.load(modelFile, labels)
        val copy = original.newInstance() as PerchTfliteModel
        copy.load(modelFile, labels)

        original.close()

        val pcm = FloatArray(32_000 * 5)
        val out = copy.predict(pcm, 32_000, null, null, 0L, 0L, 5_000L)
        assertThat(out).hasSize(1)
        assertThat(out[0].taxonScientificName).isEqualTo("Rana temporaria")
        copy.close()
    }
}
