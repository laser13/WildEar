package com.sound2inat.app.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidPermissionsController(private val activity: ComponentActivity) : PermissionsController {

    private val _statuses = MutableStateFlow(snapshot())
    override val statuses: StateFlow<Map<Permission, PermissionStatus>> = _statuses

    private var currentRequest: CompletableDeferred<Map<Permission, PermissionStatus>>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val mapped = result.entries.associate { (rawName, granted) ->
            permissionFromRaw(rawName) to if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
        _statuses.value = _statuses.value + mapped
        currentRequest?.complete(mapped)
        currentRequest = null
    }

    override suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus> {
        val toRequest = permissions.filter { isRequestable(it) }
        if (toRequest.isEmpty()) return permissions.associateWith { PermissionStatus.GRANTED }
        val raw = toRequest.map { rawNameFor(it) }.toTypedArray()
        val deferred = CompletableDeferred<Map<Permission, PermissionStatus>>()
        currentRequest = deferred
        launcher.launch(raw)
        return deferred.await()
    }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", activity.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun snapshot(): Map<Permission, PermissionStatus> =
        Permission.values().associateWith {
            if (!isRequestable(it)) return@associateWith PermissionStatus.GRANTED
            val granted = ContextCompat.checkSelfPermission(activity, rawNameFor(it)) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }

    private fun isRequestable(p: Permission) = when (p) {
        Permission.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        else -> true
    }

    private fun rawNameFor(p: Permission) = when (p) {
        Permission.RECORD_AUDIO -> Manifest.permission.RECORD_AUDIO
        Permission.ACCESS_FINE_LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
        Permission.POST_NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
    }

    private fun permissionFromRaw(raw: String): Permission =
        Permission.values().first { isRequestable(it) && rawNameFor(it) == raw }
}
