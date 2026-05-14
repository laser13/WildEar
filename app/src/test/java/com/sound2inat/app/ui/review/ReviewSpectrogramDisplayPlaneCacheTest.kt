package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReviewSpectrogramDisplayPlaneCacheTest {
    private fun sampleMatrix(
        displayRange: SpectrogramDisplayRange = SpectrogramDisplayRange.FULL,
    ): ReviewSpectrogramMatrix {
        val config = ReviewSpectrogramAnalysisConfig.from(displayRange, sampleRateHz = 48_000)
        val values = Array(config.displayHeightBins) { row ->
            FloatArray(32) { frame ->
                row.toFloat() / config.displayHeightBins.toFloat() + frame.toFloat() / 64f
            }
        }
        return ReviewSpectrogramMatrix(config = config, frames = 32, values = values)
    }

    @Test
    fun `palette-only changes reuse the same display plane`() {
        val cache = ReviewSpectrogramDisplayPlaneCache()
        val matrix = sampleMatrix()
        val calls = AtomicInteger(0)
        val baseKey = "matrix-key"
        val inkSuffix = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.INK).displayPlaneCacheSuffix()
        val viridisSuffix = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.VIRIDIS).displayPlaneCacheSuffix()

        val first = runSuspend {
            cache.getOrCreate(
                key = "$baseKey|$inkSuffix",
                draftId = "draft",
                audioName = "audio.wav",
            ) {
                calls.incrementAndGet()
                SpectrogramRenderer(
                    targetWidth = 64,
                    config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.INK),
                ).buildDisplayPlane(matrix)
            }
        }
        val second = runSuspend {
            cache.getOrCreate(
                key = "$baseKey|$viridisSuffix",
                draftId = "draft",
                audioName = "audio.wav",
            ) {
                calls.incrementAndGet()
                SpectrogramRenderer(
                    targetWidth = 64,
                    config = ReviewSpectrogramConfig.BirdDefault.copy(palette = SpectrogramPalette.VIRIDIS),
                ).buildDisplayPlane(matrix)
            }
        }

        assertThat(first).isEqualTo(second)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `display-range changes produce a distinct cached plane`() {
        val cache = ReviewSpectrogramDisplayPlaneCache()
        val matrix = sampleMatrix()
        val calls = AtomicInteger(0)
        val fullSuffix = ReviewSpectrogramConfig.BirdDefault.copy(displayRange = SpectrogramDisplayRange.FULL).displayPlaneCacheSuffix()
        val birdSuffix = ReviewSpectrogramConfig.BirdDefault.copy(displayRange = SpectrogramDisplayRange.BIRDNET_BIRD).displayPlaneCacheSuffix()

        val first = runSuspend {
            cache.getOrCreate(
                key = "matrix-key|$fullSuffix",
                draftId = "draft",
                audioName = "audio.wav",
            ) {
                calls.incrementAndGet()
                SpectrogramRenderer(
                    targetWidth = 64,
                    config = ReviewSpectrogramConfig.BirdDefault.copy(displayRange = SpectrogramDisplayRange.FULL),
                ).buildDisplayPlane(matrix)
            }
        }
        val second = runSuspend {
            cache.getOrCreate(
                key = "matrix-key|$birdSuffix",
                draftId = "draft",
                audioName = "audio.wav",
            ) {
                calls.incrementAndGet()
                SpectrogramRenderer(
                    targetWidth = 64,
                    config = ReviewSpectrogramConfig.BirdDefault.copy(displayRange = SpectrogramDisplayRange.BIRDNET_BIRD),
                ).buildDisplayPlane(matrix)
            }
        }

        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(calls.get()).isEqualTo(2)
    }

    private fun <T> runSuspend(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
}
