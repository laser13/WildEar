package com.sound2inat.app.ui.photos

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PhotoCaptureViewModelTest {
    @Test
    fun `capture is ready when camera permission is granted`() = runTest {
        val vm = PhotoCaptureViewModel()

        vm.initWithPermissions(FakePermissions(Permission.CAMERA to PermissionStatus.GRANTED))

        assertThat(vm.state.value.canBindCamera).isTrue()
        assertThat(vm.state.value.showCameraPermissionDenied).isFalse()
    }

    @Test
    fun `capture shows denial state when camera permission is denied`() = runTest {
        val vm = PhotoCaptureViewModel()

        vm.initWithPermissions(FakePermissions(Permission.CAMERA to PermissionStatus.DENIED))

        assertThat(vm.state.value.canBindCamera).isFalse()
        assertThat(vm.state.value.showCameraPermissionDenied).isTrue()
    }
}

private class FakePermissions(
    vararg initial: Pair<Permission, PermissionStatus>,
) : PermissionsController {
    private val _state = MutableStateFlow(initial.toMap())
    override val statuses: StateFlow<Map<Permission, PermissionStatus>> = _state

    override suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus> =
        permissions.associateWith { _state.value[it] ?: PermissionStatus.DENIED }

    override fun openAppSettings() = Unit
}
