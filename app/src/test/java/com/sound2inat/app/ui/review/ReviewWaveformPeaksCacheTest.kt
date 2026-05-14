package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ReviewWaveformPeaksCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `second call loads cached peaks without recomputing`() {
        val cache = ReviewWaveformPeaksCache()
        val audioFile = createAudioFile("audio.wav")
        val calls = AtomicInteger(0)

        val first = runSuspend {
            cache.getOrCreate(audioFile, "draft", tmp.root) {
                calls.incrementAndGet()
                floatArrayOf(-1f, 1f, -0.5f, 0.5f)
            }
        }
        val second = runSuspend {
            cache.getOrCreate(audioFile, "draft", tmp.root) {
                calls.incrementAndGet()
                floatArrayOf(0f)
            }
        }

        assertThat(first.toList()).containsExactly(-1f, 1f, -0.5f, 0.5f).inOrder()
        assertThat(second.toList()).containsExactly(-1f, 1f, -0.5f, 0.5f).inOrder()
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `corrupt cache file is deleted and rebuilt`() {
        val cache = ReviewWaveformPeaksCache()
        val audioFile = createAudioFile("corrupt.wav")
        val cacheFile = cache.cacheFile(audioFile, "draft", tmp.root)
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(byteArrayOf(1, 2, 3))
        val calls = AtomicInteger(0)

        val peaks = runSuspend {
            cache.getOrCreate(audioFile, "draft", tmp.root) {
                calls.incrementAndGet()
                floatArrayOf(-0.25f, 0.25f)
            }
        }

        assertThat(peaks.toList()).containsExactly(-0.25f, 0.25f).inOrder()
        assertThat(calls.get()).isEqualTo(1)
        assertThat(cacheFile.exists()).isTrue()
    }

    private fun createAudioFile(name: String): File =
        tmp.newFile(name).apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        }

    private fun <T> runSuspend(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
}
