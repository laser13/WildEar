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

class BirdNetMetaModelTest {
    @get:Rule val tmp = TemporaryFolder()

    // 4 species lined up to exercise each threshold band.
    private val labelsContent = """
        Aaa speciesA_Common A
        Bbb speciesB_Common B
        Ccc speciesC_Common C
        Ddd speciesD_Common D
    """.trimIndent()

    private fun buildModel(probs: FloatArray, capturedInput: FloatArray? = null): BirdNetMetaModel {
        val modelFile = tmp.newFile("meta.tflite").apply { writeBytes(byteArrayOf(0)) }
        val labelsFile = tmp.newFile("labels.txt").apply { writeText(labelsContent) }
        val fakeFactory = object : InterpreterFactory {
            override fun create(
                m: File,
                threads: Int,
                allowDelegate: Boolean
            ): InterpreterApi = object : InterpreterApi {
                override val outputTensorCount = 1
                override fun getOutputShape(index: Int) = intArrayOf(1, probs.size)
                override fun run(input: Any, output: Any) {
                    @Suppress("UNCHECKED_CAST")
                    val inArr = input as Array<FloatArray>
                    capturedInput?.let { inArr[0].copyInto(it) }
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
        return BirdNetMetaModel(fakeFactory, fakeManager)
    }

    @Test
    fun `priors map quantizes raw probabilities into the four-band ladder`() = runTest {
        // 0.02 → 1.0, 0.0085 → 0.8, 0.0015 → 0.5, 0.00005 → 0 (dropped)
        val probs = floatArrayOf(0.02f, 0.0085f, 0.0015f, 0.00005f)
        val model = buildModel(probs)

        val priors = model.priorsByScientificName(latitude = 51.5, longitude = -0.1, weekOfYear = 12)
        assertThat(priors).isNotNull()
        requireNotNull(priors)

        assertThat(priors).containsEntry("Aaa speciesA", 1.0f)
        assertThat(priors).containsEntry("Bbb speciesB", 0.8f)
        assertThat(priors).containsEntry("Ccc speciesC", 0.5f)
        assertThat(priors).doesNotContainKey("Ddd speciesD")
    }

    @Test
    fun `weekOfYear is folded into input via cos transform`() = runTest {
        val captured = FloatArray(3)
        val model = buildModel(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), capturedInput = captured)

        // Week 12 ≈ cos(90°)+1 = 1.0; Week 1 ≈ cos(7.5°)+1 ≈ 1.99144
        model.priorsByScientificName(latitude = 0.0, longitude = 0.0, weekOfYear = 12)
        assertThat(captured[0]).isEqualTo(0.0f)
        assertThat(captured[1]).isEqualTo(0.0f)
        assertThat(captured[2]).isWithin(1e-4f).of(1.0f)
    }

    @Test
    fun `lat and lon are passed through unchanged`() = runTest {
        val captured = FloatArray(3)
        val model = buildModel(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), capturedInput = captured)
        model.priorsByScientificName(latitude = 51.5, longitude = -0.13, weekOfYear = 24)
        assertThat(captured[0]).isWithin(1e-4f).of(51.5f)
        assertThat(captured[1]).isWithin(1e-4f).of(-0.13f)
    }

    @Test
    fun `priors returns null when the meta-model is not installed`() = runTest {
        val fakeFactory = object : InterpreterFactory {
            override fun create(
                m: File,
                threads: Int,
                allowDelegate: Boolean
            ): InterpreterApi = error("should not be called")
        }
        val fakeManager = object : ModelManager(tmp.root, OkHttpClient()) {
            override suspend fun stateFor(descriptor: ModelDescriptor) = ModelInstallState.NotInstalled
            override suspend fun install(
                descriptor: ModelDescriptor,
                emit: (ModelInstallState) -> Unit,
            ) = Unit
            override fun remove(descriptor: ModelDescriptor) = Unit
        }
        val model = BirdNetMetaModel(fakeFactory, fakeManager)
        assertThat(model.priorsByScientificName(0.0, 0.0, 1)).isNull()
    }

    @Test
    fun `priors returns null on interpreter creation failure (fail-open)`() = runTest {
        val modelFile = tmp.newFile("meta.tflite").apply { writeBytes(byteArrayOf(0)) }
        val labelsFile = tmp.newFile("labels.txt").apply { writeText(labelsContent) }
        val throwingFactory = object : InterpreterFactory {
            override fun create(
                m: File,
                threads: Int,
                allowDelegate: Boolean
            ): InterpreterApi = error("deliberate failure")
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
        val model = BirdNetMetaModel(throwingFactory, fakeManager)
        assertThat(model.priorsByScientificName(0.0, 0.0, 1)).isNull()
    }

    @Test
    fun `weekOfYearFromDayOfYear maps day 1 to week 1 and day 366 to week 48`() {
        assertThat(BirdNetMetaModel.weekOfYearFromDayOfYear(1)).isEqualTo(1)
        assertThat(BirdNetMetaModel.weekOfYearFromDayOfYear(366)).isEqualTo(48)
        assertThat(BirdNetMetaModel.weekOfYearFromDayOfYear(183)).isEqualTo(24)
    }
}
