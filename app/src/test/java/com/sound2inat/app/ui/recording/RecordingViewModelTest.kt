package com.sound2inat.app.ui.recording

import android.content.Context
import android.os.Build
import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import com.sound2inat.app.recording.FakeRecordingController
import com.sound2inat.app.recording.RecordingController
import com.sound2inat.app.recording.RecordingServiceLauncher
import com.sound2inat.app.recording.RecordingSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class RecordingViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeController: FakeRecordingController
    private lateinit var fakeLauncher: FakeRecordingServiceLauncher
    private val fakeContext: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fakeController = FakeRecordingController()
        fakeLauncher = FakeRecordingServiceLauncher()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildVm(
        perms: PermissionsController = grantedPerms(),
        controller: RecordingController = fakeController,
        launcher: RecordingServiceLauncher = fakeLauncher,
    ) = RecordingViewModel(
        perms = perms,
        controller = controller,
        launcher = launcher,
        appContext = fakeContext,
    )

    @Test
    fun `start calls launcher start when RECORD_AUDIO granted`() = runTest {
        val vm = buildVm()

        vm.start()
        runCurrent()

        assertThat(fakeLauncher.startCalled).isTrue()
    }

    @Test
    fun `start does not call launcher when RECORD_AUDIO denied and surfaces Error`() = runTest {
        val vm = buildVm(perms = deniedPerms())

        vm.start()
        runCurrent()

        assertThat(fakeLauncher.startCalled).isFalse()
        assertThat(vm.state.value).isEqualTo(
            RecordingUiState.Error("Microphone permission required."),
        )
    }

    @Test
    fun `stop calls launcher stop`() = runTest {
        val vm = buildVm()

        vm.stop()

        assertThat(fakeLauncher.stopCalled).isTrue()
    }

    @Test
    fun `cancel calls launcher cancel`() = runTest {
        val vm = buildVm()

        vm.cancel()

        assertThat(fakeLauncher.cancelCalled).isTrue()
    }

    @Test
    fun `state mirrors controller Recording state`() = runTest {
        val vm = buildVm()

        fakeController.setState(
            RecordingSessionState.Recording(
                draftId = "d1",
                recordingStartMs = 0L,
                elapsedMs = 5_000L,
                rms = 0.3f,
                gps = GpsStatus.NoFix,
                warningSoftLimit = false,
                backlogWindows = 2,
                liveCards = emptyList(),
                lastDetection = null,
            ),
        )
        runCurrent()

        val state = vm.state.value as RecordingUiState.Recording
        assertThat(state.elapsedMs).isEqualTo(5_000L)
        assertThat(state.rms).isEqualTo(0.3f)
        assertThat(state.gps).isEqualTo(GpsStatus.NoFix)
        assertThat(state.backlogWindows).isEqualTo(2)
    }

    @Test
    fun `state mirrors controller Done state`() = runTest {
        val vm = buildVm()

        // The VM guards against stale Done from a previous session by requiring at
        // least one Recording state before it transitions to Done (hasSeenRecording).
        fakeController.setState(
            RecordingSessionState.Recording(
                draftId = "d2", recordingStartMs = 0L, elapsedMs = 0L,
                rms = 0f, gps = GpsStatus.NoFix, warningSoftLimit = false,
                backlogWindows = 0, liveCards = emptyList(), lastDetection = null,
            ),
        )
        runCurrent()
        fakeController.setState(RecordingSessionState.Done("d2"))
        runCurrent()

        assertThat(vm.state.value).isEqualTo(RecordingUiState.Done("d2"))
    }

    private fun grantedPerms() = FakePerms(
        mapOf(
            Permission.RECORD_AUDIO to PermissionStatus.GRANTED,
            Permission.ACCESS_FINE_LOCATION to PermissionStatus.GRANTED,
            Permission.POST_NOTIFICATIONS to PermissionStatus.GRANTED,
        ),
    )

    private fun deniedPerms() = FakePerms(
        mapOf(Permission.RECORD_AUDIO to PermissionStatus.DENIED),
    )
}

private class FakePerms(
    private val grants: Map<Permission, PermissionStatus>,
) : PermissionsController {
    private val _statuses = MutableStateFlow(grants)
    override val statuses: StateFlow<Map<Permission, PermissionStatus>> = _statuses

    override suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus> =
        permissions.associateWith { grants[it] ?: PermissionStatus.DENIED }

    override fun openAppSettings() = Unit
}

private class FakeRecordingServiceLauncher : RecordingServiceLauncher {
    var startCalled = false
    var stopCalled = false
    var cancelCalled = false

    override fun start(context: Context) {
        startCalled = true
    }

    override fun stop(context: Context) {
        stopCalled = true
    }

    override fun cancel(context: Context) {
        cancelCalled = true
    }
}
