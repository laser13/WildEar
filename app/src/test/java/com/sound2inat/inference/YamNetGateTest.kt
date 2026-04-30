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

    @Test
    fun `returns false when speech is top class and bio score below 0_15`() = runTest {
        val probs = FloatArray(521)
        probs[0] = 0.90f    // Speech (noise) is top class
        probs[100] = 0.05f  // Bird bio score < 0.15
        assertThat(buildGate(probs).isBiological(FloatArray(16_000), 16_000)).isFalse()
    }

    @Test
    fun `returns true when bird score is above threshold`() = runTest {
        val probs = FloatArray(521)
        probs[100] = 0.80f  // Bird high bio score
        probs[0] = 0.10f    // Speech low
        assertThat(buildGate(probs).isBiological(FloatArray(16_000), 16_000)).isTrue()
    }

    @Test
    fun `returns true when top is noise but bio score is at or above threshold`() = runTest {
        val probs = FloatArray(521)
        probs[0] = 0.70f    // Speech top
        probs[100] = 0.20f  // Bird bio score >= 0.15 → still biological
        assertThat(buildGate(probs).isBiological(FloatArray(16_000), 16_000)).isTrue()
    }

    @Test
    fun `returns true on interpreter creation exception — fail-open`() = runTest {
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
        assertThat(gate.isBiological(FloatArray(16_000), 16_000)).isTrue()
    }

    @Test
    fun `returns true when model not yet installed — fail-open`() = runTest {
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
        assertThat(gate.isBiological(FloatArray(16_000), 16_000)).isTrue()
    }
}
