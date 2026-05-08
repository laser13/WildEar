package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InferenceUseCaseTest {

    @Test
    fun `bundle exposes both inference and perch analysis seams`() {
        val canned = object : InferenceUseCase {
            override val inference: InferenceJob = InferenceJob { _, _, _, _, _ ->
                InferenceOutcome.Success(
                    modelId = "fake",
                    modelVersion = "0",
                    detections = emptyList(),
                )
            }
            override val perchAnalysis: PerchAnalysisJob = PerchAnalysisJob { _, _, _, _, _ ->
                PerchAnalysisOutcome.NotInstalled
            }
        }

        assertThat(canned.inference).isNotNull()
        assertThat(canned.perchAnalysis).isNotNull()
    }

    @Test
    fun `inference job seam routes through InferenceUseCase property`() = runTest {
        val canned = object : InferenceUseCase {
            override val inference: InferenceJob = InferenceJob { _, _, _, _, _ ->
                InferenceOutcome.Failure("canned")
            }
            override val perchAnalysis: PerchAnalysisJob = PerchAnalysisJob { _, _, _, _, _ ->
                PerchAnalysisOutcome.NotInstalled
            }
        }

        val outcome = canned.inference.run(
            audioPath = "/dev/null",
            latitude = null,
            longitude = null,
            observedAtMillis = 0L,
            onProgress = {},
        )

        assertThat(outcome).isInstanceOf(InferenceOutcome.Failure::class.java)
        assertThat((outcome as InferenceOutcome.Failure).message).isEqualTo("canned")
    }
}
