package com.sound2inat.app.permissions

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PermissionsControllerContractTest {

    private class Fake(initial: Map<Permission, PermissionStatus>) : PermissionsController {
        private val _state = MutableStateFlow(initial)
        override val statuses: StateFlow<Map<Permission, PermissionStatus>> = _state
        var openSettingsCalls = 0
        override suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus> {
            val granted = permissions.associateWith { PermissionStatus.GRANTED }
            _state.value = _state.value + granted
            return granted
        }
        override fun openAppSettings() { openSettingsCalls++ }
    }

    @Test fun `initial statuses are exposed`() = runTest {
        val fake = Fake(mapOf(Permission.RECORD_AUDIO to PermissionStatus.DENIED))
        assertThat(fake.statuses.value[Permission.RECORD_AUDIO]).isEqualTo(PermissionStatus.DENIED)
    }

    @Test fun `request returns granted map and updates state`() = runTest {
        val fake = Fake(mapOf(Permission.RECORD_AUDIO to PermissionStatus.DENIED))
        val out = fake.request(setOf(Permission.RECORD_AUDIO, Permission.ACCESS_FINE_LOCATION))
        assertThat(out[Permission.RECORD_AUDIO]).isEqualTo(PermissionStatus.GRANTED)
        assertThat(out[Permission.ACCESS_FINE_LOCATION]).isEqualTo(PermissionStatus.GRANTED)
        assertThat(fake.statuses.value[Permission.RECORD_AUDIO]).isEqualTo(PermissionStatus.GRANTED)
    }

    @Test fun `openAppSettings is invoked`() = runTest {
        val fake = Fake(emptyMap())
        fake.openAppSettings()
        fake.openAppSettings()
        assertThat(fake.openSettingsCalls).isEqualTo(2)
    }
}
