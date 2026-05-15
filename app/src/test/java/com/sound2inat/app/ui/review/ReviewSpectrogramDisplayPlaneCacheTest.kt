package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        val inkSuffix = ReviewSpectrogramConfig.BirdDefault.copy(
            palette = SpectrogramPalette.INK
        ).displayPlaneCacheSuffix()
        val viridisSuffix = ReviewSpectrogramConfig.BirdDefault.copy(
            palette = SpectrogramPalette.VIRIDIS
        ).displayPlaneCacheSuffix()

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
        val fullSuffix = ReviewSpectrogramConfig.BirdDefault.copy(
            displayRange = SpectrogramDisplayRange.FULL
        ).displayPlaneCacheSuffix()
        val birdSuffix = ReviewSpectrogramConfig.BirdDefault.copy(
            displayRange = SpectrogramDisplayRange.BIRDNET_BIRD
        ).displayPlaneCacheSuffix()

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
                    config = ReviewSpectrogramConfig.BirdDefault.copy(
                        displayRange = SpectrogramDisplayRange.BIRDNET_BIRD
                    ),
                ).buildDisplayPlane(matrix)
            }
        }

        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(calls.get()).isEqualTo(2)
    }

    private fun fakePlane(width: Int = 4, height: Int = 2): ReviewSpectrogramDisplayPlane =
        ReviewSpectrogramDisplayPlane(
            width = width,
            height = height,
            values = Array(height) { FloatArray(width) { 0f } },
        )

    @Test
    fun `parallel getOrCreate for different keys runs builds concurrently`() {
        // If mutex were held during build, A would wait for B to start but B
        // couldn't start until A released the lock → deadlock.
        val cache = ReviewSpectrogramDisplayPlaneCache()
        val startA = CompletableDeferred<Unit>()
        val startB = CompletableDeferred<Unit>()
        runSuspend {
            coroutineScope {
                val a = async {
                    cache.getOrCreate("a", "d", "f") {
                        startA.complete(Unit)
                        startB.await()
                        fakePlane(width = 1, height = 1)
                    }
                }
                val b = async {
                    cache.getOrCreate("b", "d", "f") {
                        startB.complete(Unit)
                        startA.await()
                        fakePlane(width = 2, height = 2)
                    }
                }
                a.await()
                b.await()
            }
        }
        // Reaching here without deadlock/timeout means builds ran concurrently.
    }

    @Test
    fun `parallel getOrCreate for same key keeps the first inserted value`() {
        val cache = ReviewSpectrogramDisplayPlaneCache()
        val planeA = fakePlane(width = 3, height = 3)
        val planeB = fakePlane(width = 5, height = 5)
        runSuspend {
            coroutineScope {
                // B finishes build faster (delay 10ms) but A also races.
                val a = async {
                    cache.getOrCreate("k", "d", "f") {
                        delay(50)
                        planeA
                    }
                }
                val b = async {
                    cache.getOrCreate("k", "d", "f") {
                        delay(10)
                        planeB
                    }
                }
                val ra = a.await()
                val rb = b.await()
                // Both callers must see the same cached plane (whichever won the getOrPut race).
                assertThat(ra).isEqualTo(rb)
            }
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
}
