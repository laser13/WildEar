package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.exp

private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

/**
 * Fake [InterpreterFactory] that returns an interpreter whose `run` always
 * copies a fixed [output] FloatArray into `output[0]`. Lets us drive the
 * model wrapper without loading a real `.tflite`.
 */
private class FakeInterpreterFactory(
    private val output: FloatArray,
) : InterpreterFactory {
    var lastInput: FloatArray? = null
    var closed = false

    override fun create(modelFile: File, threads: Int): InterpreterApi =
        object : InterpreterApi {
            override val outputTensorCount: Int = 1
            override fun getOutputShape(index: Int): IntArray = intArrayOf(1, output.size)
            override fun run(input: Any, outputArg: Any) {
                @Suppress("UNCHECKED_CAST")
                val inArr = input as Array<FloatArray>
                lastInput = inArr[0]
                @Suppress("UNCHECKED_CAST")
                val outArr = outputArg as Array<FloatArray>
                System.arraycopy(output, 0, outArr[0], 0, output.size)
            }

            override fun runForMultipleOutputs(input: Any, outputs: Map<Int, Any>) {
                error("BirdNet path uses single-output run")
            }

            override fun close() {
                closed = true
            }
        }
}

class BirdNetTfliteModelTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `predictions above floor ordered by confidence`() = runTest {
        val labels = tmp.newFile("labels.txt").apply {
            writeText("Sylvia melanothorax_Cyprus Warbler\nPasser domesticus_House Sparrow\nNoise\n")
        }
        val model = tmp.newFile("model.tflite").apply { writeBytes(byteArrayOf(0)) }
        val fake = FakeInterpreterFactory(
            // labels file order: [Sylvia, Passer, Noise]
            // sigmoid(-5.0) ≈ 0.0067 < 0.01 floor — Noise excluded
            output = floatArrayOf(0.7f, 0.2f, -5.0f),
        )
        val m = BirdNetTfliteModel(fake)
        m.load(model, labels)

        val pcm = FloatArray(48_000 * 3)
        val out = m.predict(pcm, 48_000, null, null, 0L, 0L, 3_000L)

        assertThat(out).hasSize(2)
        assertThat(out.map { it.taxonScientificName })
            .containsExactly("Sylvia melanothorax", "Passer domesticus")
            .inOrder()
        // Model output is raw logits — wrapper applies sigmoid per class.
        assertThat(out[0].confidence).isWithin(1e-6f).of(sigmoid(0.7f))
        assertThat(out[0].taxonCommonName).isEqualTo("Cyprus Warbler")
        assertThat(out[1].confidence).isWithin(1e-6f).of(sigmoid(0.2f))
        assertThat(out[1].taxonCommonName).isEqualTo("House Sparrow")
        assertThat(out[0].startMs).isEqualTo(0L)
        assertThat(out[0].endMs).isEqualTo(3_000L)
    }

    @Test
    fun `predict requires 48 kHz sample rate`() = runTest {
        val labels = tmp.newFile("labels.txt").apply { writeText("A_a\nB_b\n") }
        val model = tmp.newFile("model.tflite").apply { writeBytes(byteArrayOf(0)) }
        val fake = FakeInterpreterFactory(floatArrayOf(0.1f, 0.9f))
        val m = BirdNetTfliteModel(fake)
        m.load(model, labels)

        val ex = runCatching {
            m.predict(FloatArray(0), 44_100, null, null, 0L, 0L, 0L)
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `close releases the interpreter`() = runTest {
        val labels = tmp.newFile("labels.txt").apply { writeText("A_a\n") }
        val model = tmp.newFile("model.tflite").apply { writeBytes(byteArrayOf(0)) }
        val fake = FakeInterpreterFactory(floatArrayOf(1.0f))
        val m = BirdNetTfliteModel(fake)
        m.load(model, labels)

        m.close()
        assertThat(fake.closed).isTrue()
        // Re-using the model after close must fail because interp is null.
        val ex = runCatching {
            m.predict(FloatArray(0), 48_000, null, null, 0L, 0L, 0L)
        }.exceptionOrNull()
        assertThat(ex).isNotNull()
    }
}
