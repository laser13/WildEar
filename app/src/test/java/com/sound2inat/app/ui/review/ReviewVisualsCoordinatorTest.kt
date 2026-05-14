package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewVisualsCoordinatorTest {
    private fun preview(color: Int = 0xFF000000.toInt()) =
        ReviewSpectrogramPreview(width = 1, height = 1, argb = intArrayOf(color))

    @Test
    fun `identical key shares the same in-flight build`() = runTest {
        val coordinator = ReviewVisualsCoordinator(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val buildCalls = AtomicInteger(0)
        val expected = Visuals(preview(), floatArrayOf(-1f, 1f))

        val first = async {
            coordinator.getOrBuild("same-key") {
                buildCalls.incrementAndGet()
                gate.await()
                expected
            }
        }
        val second = async {
            coordinator.getOrBuild("same-key") {
                buildCalls.incrementAndGet()
                expected
            }
        }

        runCurrent()
        assertThat(buildCalls.get()).isEqualTo(1)

        gate.complete(Unit)
        val a = first.await()
        val b = second.await()

        assertThat(buildCalls.get()).isEqualTo(1)
        assertThat(a).isEqualTo(expected)
        assertThat(b).isEqualTo(expected)
    }

    @Test
    fun `different keys are serialized through shared build mutex`() = runTest {
        val coordinator = ReviewVisualsCoordinator(backgroundScope)
        val allowFirstToFinish = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val firstCalls = AtomicInteger(0)
        val secondCalls = AtomicInteger(0)

        val first = async {
            coordinator.getOrBuild("first-key") {
                firstCalls.incrementAndGet()
                firstEntered.complete(Unit)
                allowFirstToFinish.await()
                Visuals(preview(0xFF112233.toInt()), floatArrayOf(-1f, 1f))
            }
        }
        firstEntered.await()

        val second = async {
            coordinator.getOrBuild("second-key") {
                secondCalls.incrementAndGet()
                secondEntered.complete(Unit)
                Visuals(preview(0xFF445566.toInt()), floatArrayOf(-0.5f, 0.5f))
            }
        }

        runCurrent()
        assertThat(secondEntered.isCompleted).isFalse()

        allowFirstToFinish.complete(Unit)
        first.await()
        second.await()

        assertThat(secondEntered.isCompleted).isTrue()
        assertThat(firstCalls.get()).isEqualTo(1)
        assertThat(secondCalls.get()).isEqualTo(1)
    }
}
