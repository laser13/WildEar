package com.sound2inat.app.ui.photos

import android.view.ScaleGestureDetector
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sound2inat.app.permissions.LocalPermissionsController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ZoomIn
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
    var showDiscardDialog by remember { mutableStateOf(false) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }

    val zoomGestureDetector = remember(context) {
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val boundCamera = camera ?: return false
                    val nextZoom = (zoomRatio * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                    boundCamera.cameraControl.setZoomRatio(nextZoom)
                    return true
                }
            },
        )
    }

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
                        val boundCamera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                        )
                        cameraProvider = provider
                        imageCapture = capture
                        camera = boundCamera
                    }.onFailure {
                        imageCapture = null
                        camera = null
                    }
                },
                mainExecutor,
            )
            onDispose {
                cameraProvider?.unbindAll()
                imageCapture = null
                camera = null
            }
        }
    }

    DisposableEffect(camera, lifecycleOwner) {
        val zoomLiveData = camera?.cameraInfo?.zoomState
        if (zoomLiveData == null) {
            onDispose { }
        } else {
            val observer = Observer<ZoomState> { state ->
                zoomRatio = state.zoomRatio
                minZoom = state.minZoomRatio
                maxZoom = state.maxZoomRatio
            }
            zoomLiveData.observe(lifecycleOwner, observer)
            onDispose { zoomLiveData.removeObserver(observer) }
        }
    }

    DisposableEffect(previewView, zoomGestureDetector) {
        previewView.setOnTouchListener { _, event ->
            zoomGestureDetector.onTouchEvent(event)
        }
        onDispose {
            previewView.setOnTouchListener(null)
        }
    }

    fun setZoom(target: Float) {
        val boundCamera = camera ?: return
        val bounded = target.coerceIn(minZoom, maxZoom)
        boundCamera.cameraControl.setZoomRatio(bounded)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.canBindCamera -> {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.photoCount > 0) {
                                CameraHudPill(
                                    icon = Icons.Outlined.PhotoLibrary,
                                    text = state.photoCount.toString(),
                                    contentDescription = "${state.photoCount} photos",
                                )
                            }
                            CameraHudPill(
                                icon = Icons.Outlined.ZoomIn,
                                text = "${formatZoom(zoomRatio)}x",
                                contentDescription = "Zoom ${formatZoom(zoomRatio)}x",
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ZoomChip("1x", onClick = { setZoom(1f) })
                            if (maxZoom >= 2f) ZoomChip("2x", onClick = { setZoom(2f) })
                            if (maxZoom >= 4f) ZoomChip("4x", onClick = { setZoom(4f) })
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                when {
                                    state.isExistingDraft -> onCancel()
                                    state.photoCount == 0 -> {
                                        scope.launch {
                                            vm.discardIfEmpty()
                                            onCancel()
                                        }
                                    }
                                    else -> showDiscardDialog = true
                                }
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Cancel",
                            )
                        }
                        FilledTonalIconButton(
                            enabled = imageCapture != null,
                            modifier = Modifier.size(72.dp),
                            onClick = {
                                val capture = imageCapture ?: return@FilledTonalIconButton
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
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.CameraAlt,
                                contentDescription = "Shutter",
                            )
                        }
                        IconButton(
                            enabled = state.doneEnabled && state.draftId != null,
                            modifier = Modifier.size(48.dp),
                            onClick = { state.draftId?.let(onDone) },
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Done",
                            )
                        }
                    }
                }
            }

            state.showCameraPermissionDenied -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Camera permission is required to take photos.")
                            Button(onClick = { vm.openAppSettings(permissions) }) {
                                Text("Open settings")
                            }
                        }
                    }
                }
            }

            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Checking camera permission...")
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard photo session?") },
            text = {
                Text(
                    "You have already taken ${state.photoCount} photo${if (state.photoCount == 1) "" else "s"}. " +
                        "Do you want to keep this album or discard it?",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep album")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.discardDraft()
                        showDiscardDialog = false
                        onCancel()
                    }
                }) {
                    Text("Discard")
                }
            },
        )
    }
}

@Composable
private fun ZoomChip(
    label: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(999.dp),
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}

@Composable
private fun CameraHudPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    contentDescription: String,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = contentDescription,
            )
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun formatZoom(ratio: Float): String = "%.1f".format(ratio)
