package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.modelmanager.YamNetV1
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class YamNetTfliteGateSceneTagsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `aggregates max probabilities per scene category across two frames`() = runTest {
        val labels = tmp.newFile("yamnet_labels.csv")
        labels.writeText(
            buildString {
                appendLine("index,mid,display_name")
                appendLine("0,/m/x,Bird vocalization, bird call, bird song")
                appendLine("1,/m/x,Owl")
                appendLine("2,/m/x,Frog")
                appendLine("3,/m/x,Cricket")
                appendLine("4,/m/x,Howl")
                appendLine("5,/m/x,Speech")
            }
        )
        val modelFile = tmp.newFile("yamnet.tflite")

        val mm = mockk<ModelManager>()
        coEvery { mm.stateFor(YamNetV1.descriptor) } returns ModelInstallState.Ready(modelFile, labels)

        val probabilityFrames = listOf(
            floatArrayOf(0.10f, 0.05f, 0.02f, 0.40f, 0.01f, 0.00f),
            floatArrayOf(0.85f, 0.30f, 0.02f, 0.20f, 0.50f, 0.00f),
        )
        val interp = mockk<InterpreterApi>(relaxed = true)
        var call = 0
        every { interp.run(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val output = secondArg<Array<FloatArray>>()
            probabilityFrames[call].copyInto(output[0])
            call += 1
        }

        val factory = mockk<InterpreterFactory>()
        every { factory.create(any<File>(), any<Int>(), any<Boolean>()) } returns interp

        val gate = YamNetTfliteGate(factory, mm)
        val pcm = FloatArray(15_600 * 2) { 0f }
        val result = gate.classify(pcm, sampleRateHz = 16_000)

        assertThat(result).isNotNull()
        val tags = result!!.sceneTags
        assertThat(tags.bird).isWithin(1e-4f).of(0.85f)
        assertThat(tags.owl).isWithin(1e-4f).of(0.30f)
        assertThat(tags.frog).isWithin(1e-4f).of(0.02f)
        assertThat(tags.insect).isWithin(1e-4f).of(0.40f)
        assertThat(tags.mammal).isWithin(1e-4f).of(0.50f)
    }
}
