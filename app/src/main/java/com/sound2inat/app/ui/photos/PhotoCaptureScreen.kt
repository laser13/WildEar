package com.sound2inat.app.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sound2inat.app.permissions.LocalPermissionsController

@Suppress("FunctionNaming")
@Composable
fun PhotoCaptureScreen(
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val permissions = LocalPermissionsController.current
    val vm: PhotoCaptureViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(permissions) {
        vm.initWithPermissions(permissions)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Photo capture", style = MaterialTheme.typography.headlineMedium)
        when {
            state.canBindCamera -> {
                Text("CameraX capture will be implemented in the capture task.")
                Button(onClick = { onDone("placeholder-photo-draft") }) {
                    Text("Done")
                }
            }
            state.showCameraPermissionDenied -> {
                Text("Camera permission is required to take photos.")
                Button(onClick = { vm.openAppSettings(permissions) }) {
                    Text("Open settings")
                }
            }
            else -> Text("Checking camera permission...")
        }
        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}
