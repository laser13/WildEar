package com.sound2inat.app.ui.photos

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sound2inat.app.permissions.LocalPermissionsController
import kotlinx.coroutines.launch

@Suppress("FunctionNaming")
@Composable
fun PhotoCaptureScreen(
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissions = LocalPermissionsController.current
    val vm: PhotoCaptureViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(permissions) {
        vm.initWithPermissions(permissions)
    }

    DisposableEffect(state.canBindCamera, lifecycleOwner) {
        if (!state.canBindCamera) {
            onDispose { }
        } else {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener(
                {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                        )
                    }.onSuccess {
                        cameraProvider = provider
                        imageCapture = capture
                    }.onFailure {
                        imageCapture = null
                    }
                },
                mainExecutor,
            )
            onDispose {
                cameraProvider?.unbindAll()
                imageCapture = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Photo capture", style = MaterialTheme.typography.headlineMedium)
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        when {
            state.canBindCamera -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.photoCount.takeIf { it > 0 }?.let { count ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                        ) {
                            Text(
                                "$count photo${if (count == 1) "" else "s"}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = imageCapture != null,
                        onClick = {
                            val capture = imageCapture ?: return@Button
                            val prepared = vm.prepareOutputFile()
                            val options = ImageCapture.OutputFileOptions.Builder(prepared.file).build()
                            capture.takePicture(
                                options,
                                mainExecutor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        scope.launch {
                                            vm.onPhotoSaved(
                                                photoId = prepared.photoId,
                                                file = prepared.file,
                                                width = null,
                                                height = null,
                                            )
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        vm.onPhotoCaptureFailed(
                                            photoId = prepared.photoId,
                                            file = prepared.file,
                                            message = exception.message ?: "Photo capture failed.",
                                        )
                                    }
                                },
                            )
                        },
                    ) {
                        Text("Shutter")
                    }
                    Button(
                        enabled = state.doneEnabled && state.draftId != null,
                        onClick = { state.draftId?.let(onDone) },
                    ) {
                        Text("Done")
                    }
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
        OutlinedButton(
            onClick = {
                scope.launch {
                    vm.discardIfEmpty()
                    onCancel()
                }
            },
        ) {
            Text("Cancel")
        }
    }
}
