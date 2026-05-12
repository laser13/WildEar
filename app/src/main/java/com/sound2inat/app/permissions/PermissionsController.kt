package com.sound2inat.app.permissions

import kotlinx.coroutines.flow.StateFlow

enum class Permission { RECORD_AUDIO, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS, CAMERA }
enum class PermissionStatus { GRANTED, DENIED, PERMANENTLY_DENIED }

interface PermissionsController {
    val statuses: StateFlow<Map<Permission, PermissionStatus>>
    suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus>
    fun openAppSettings()
}
