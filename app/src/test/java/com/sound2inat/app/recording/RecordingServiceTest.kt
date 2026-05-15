package com.sound2inat.app.recording

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.di.RecordingModule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S], application = dagger.hilt.android.testing.HiltTestApplication::class)
@UninstallModules(RecordingModule::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingServiceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @BindValue @JvmField
    val controller: RecordingController = FakeRecordingController()

    private val fakeController get() = controller as FakeRecordingController

    @Before
    fun setUp() { hiltRule.inject() }

    @Test
    fun `ACTION_START calls startForeground and controller start`() = runTest {
        Robolectric.buildService(
            RecordingService::class.java,
            Intent(RuntimeEnvironment.getApplication(), RecordingService::class.java)
                .setAction(RecordingService.ACTION_START),
        ).create().startCommand(0, 1).get()

        val nm = Shadows.shadowOf(
            RuntimeEnvironment.getApplication()
                .getSystemService(NotificationManager::class.java),
        )
        assertThat(nm.allNotifications).isNotEmpty()
        assertThat(fakeController.startCalled).isTrue()
    }

    @Test
    fun `ACTION_STOP calls controller stop`() = runTest {
        Robolectric.buildService(
            RecordingService::class.java,
            Intent(RuntimeEnvironment.getApplication(), RecordingService::class.java)
                .setAction(RecordingService.ACTION_STOP),
        ).create().startCommand(0, 1).get()

        waitUntilStopCalled()
        assertThat(fakeController.stopCalled).isTrue()
    }

    @Test
    fun `ACTION_CANCEL calls controller cancel`() {
        Robolectric.buildService(
            RecordingService::class.java,
            Intent(RuntimeEnvironment.getApplication(), RecordingService::class.java)
                .setAction(RecordingService.ACTION_CANCEL),
        ).create().startCommand(0, 1).get()

        assertThat(fakeController.cancelCalled).isTrue()
    }

    @Test
    fun `null intent does not call any controller method and returns START_NOT_STICKY`() {
        val service = Robolectric.buildService(RecordingService::class.java, null)
            .create()
            .get()
        val result = service.onStartCommand(null, 0, 1)

        assertThat(result).isEqualTo(android.app.Service.START_NOT_STICKY)
        assertThat(Shadows.shadowOf(service).isStoppedBySelf).isTrue()
        assertThat(fakeController.startCalled).isFalse()
        assertThat(fakeController.stopCalled).isFalse()
        assertThat(fakeController.cancelCalled).isFalse()
    }

    private fun waitUntilStopCalled(timeoutMs: Long = 2_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!fakeController.stopCalled && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
    }
}
