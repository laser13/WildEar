package com.sound2inat.modelmanager

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() { server = MockWebServer().also { it.start() } }

    @After
    fun tearDown() { server.shutdown() }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun `downloads and verifies model and labels`() = runTest {
        val modelBytes = ByteArray(1024) { (it and 0x7F).toByte() }
        val labelBytes = "A_a\nB_b\n".toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(modelBytes)))
        server.enqueue(MockResponse().setBody(Buffer().write(labelBytes)))

        val descriptor = BirdNetV24.descriptor.copy(
            modelUrl = server.url("/m.tflite").toString(),
            labelsUrl = server.url("/labels.txt").toString(),
            modelSha256 = sha256(modelBytes),
            labelsSha256 = sha256(labelBytes),
        )
        val mm = ModelManager(filesDir = tmp.root, http = OkHttpClient())
        val states = mutableListOf<ModelInstallState>()
        mm.install(descriptor) { states += it }
        assertThat(states.last()).isInstanceOf(ModelInstallState.Ready::class.java)
        val ready = states.last() as ModelInstallState.Ready
        assertThat(ready.modelFile.readBytes()).isEqualTo(modelBytes)
        assertThat(ready.labelsFile.readBytes()).isEqualTo(labelBytes)
    }

    @Test fun `wrong checksum results in Failed and no installed file`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10))))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10))))
        val descriptor = BirdNetV24.descriptor.copy(
            modelUrl = server.url("/m.tflite").toString(),
            labelsUrl = server.url("/labels.txt").toString(),
            modelSha256 = "deadbeef",
            labelsSha256 = "cafef00d",
        )
        val mm = ModelManager(filesDir = tmp.root, http = OkHttpClient())
        val states = mutableListOf<ModelInstallState>()
        mm.install(descriptor) { states += it }
        assertThat(states.last()).isInstanceOf(ModelInstallState.Failed::class.java)
        assertThat(File(tmp.root, "models/birdnet_v2_4.tflite").exists()).isFalse()
    }

    @Test fun `stateFor returns Ready when both files exist with matching SHAs`() = runTest {
        val modelBytes = ByteArray(64)
        val labelBytes = "A_a\n".toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(modelBytes)))
        server.enqueue(MockResponse().setBody(Buffer().write(labelBytes)))

        val descriptor = BirdNetV24.descriptor.copy(
            modelUrl = server.url("/m").toString(),
            labelsUrl = server.url("/l").toString(),
            modelSha256 = sha256(modelBytes),
            labelsSha256 = sha256(labelBytes),
        )
        val mm = ModelManager(filesDir = tmp.root, http = OkHttpClient())
        mm.install(descriptor) { /* ignore */ }
        val state = mm.stateFor(descriptor)
        assertThat(state).isInstanceOf(ModelInstallState.Ready::class.java)
    }

    /**
     * Two parallel install() calls for the same descriptor must not corrupt state:
     * - Final state is Ready.
     * - Exactly one download happened (server received 2 requests: model + labels),
     *   proving the mutex caused the second caller to skip the download via re-check.
     */
    @Test fun `parallel install for same descriptor downloads only once`() = runBlocking {
        val modelBytes = ByteArray(1024) { (it and 0x7F).toByte() }
        val labelBytes = "A_a\nB_b\n".toByteArray()
        // Only enqueue one pair — if the mutex works, only one caller downloads.
        // The 200 ms delay ensures the first download is still in-flight when the
        // second coroutine starts, so the test would fail (requestCount > 2) without
        // the mutex re-check.
        server.enqueue(
            MockResponse()
                .setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setBody(Buffer().write(modelBytes))
        )
        server.enqueue(MockResponse().setBody(Buffer().write(labelBytes)))

        val descriptor = BirdNetV24.descriptor.copy(
            modelUrl = server.url("/m.tflite").toString(),
            labelsUrl = server.url("/labels.txt").toString(),
            modelSha256 = sha256(modelBytes),
            labelsSha256 = sha256(labelBytes),
        )
        val mm = ModelManager(filesDir = tmp.root, http = OkHttpClient())

        coroutineScope {
            val a = async { mm.install(descriptor) {} }
            val b = async { mm.install(descriptor) {} }
            a.await()
            b.await()
        }

        val finalState = mm.stateFor(descriptor)
        assertThat(finalState).isInstanceOf(ModelInstallState.Ready::class.java)
        // Exactly one download: model + labels = 2 HTTP requests
        assertThat(server.requestCount).isEqualTo(2)
    }
}
