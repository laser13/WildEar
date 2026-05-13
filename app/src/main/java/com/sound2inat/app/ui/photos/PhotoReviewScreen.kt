package com.sound2inat.app.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Suppress("FunctionNaming")
@Composable
fun PhotoReviewScreen(
    onBack: () -> Unit,
    onAddMorePhotos: (String) -> Unit,
) {
    val vm: PhotoReviewViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selectedImage by remember { mutableStateOf<com.sound2inat.storage.PhotoDraftImageEntity?>(null) }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading photo review...")
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PhotoGallerySection(
            images = state.images,
            onOpenImage = { selectedImage = it },
            onDeleteImage = { imageId -> scope.launch { vm.deleteImage(imageId) } },
        )

        PhotoTechnicalSection(state = state)

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3,
        ) {
            PhotoActionTile(
                icon = Icons.Outlined.AddPhotoAlternate,
                label = "Add photos",
                onClick = { onAddMorePhotos(state.draftId) },
            )
            PhotoActionTile(
                icon = Icons.Outlined.CloudUpload,
                label = if (state.inatObservationId == null) "Upload" else "Uploaded",
                enabled = state.images.isNotEmpty() && state.inatObservationId == null && !state.isSubmitting,
                onClick = { vm.submit() },
            )
            if (state.inatObservationId != null) {
                PhotoActionTile(
                    icon = Icons.Outlined.Search,
                    label = "CV",
                    enabled = !state.vision.isLoading,
                    onClick = { vm.loadVisionSuggestions() },
                )
            }
        }

        state.submitError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        state.uploadedUrl?.let {
            Text("Uploaded to iNaturalist.", color = MaterialTheme.colorScheme.primary)
        }

        if (state.vision.isLoading || state.vision.error != null || state.vision.ladder != null || state.vision.message != null) {
            PhotoVisionSection(
                vision = state.vision,
                onApplySpecies = { vm.applyVision(PhotoVisionTarget.SPECIES) },
                onApplyGenus = { vm.applyVision(PhotoVisionTarget.GENUS) },
                onApplyFamily = { vm.applyVision(PhotoVisionTarget.FAMILY) },
                onApplyCandidate = { suggestion -> vm.applyVisionSuggestion(suggestion, suggestion.rank) },
                onClose = { vm.clearVisionSuggestions() },
                onRetry = { vm.loadVisionSuggestions() },
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2,
        ) {
            PhotoActionTile(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                label = "Back",
                onClick = onBack,
            )
            PhotoActionTile(
                icon = Icons.Outlined.Delete,
                label = "Delete",
                onClick = {
                    scope.launch {
                        vm.deleteAlbum()
                        onBack()
                    }
                },
            )
        }
    }

    selectedImage?.let { image ->
        PhotoImageDialog(
            image = image,
            onClose = { selectedImage = null },
            onCropSquare = { request ->
                scope.launch {
                    vm.cropImage(image.id, request)
                    selectedImage = null
                }
            },
            onDelete = {
                scope.launch {
                    vm.deleteImage(image.id)
                    selectedImage = null
                }
            },
        )
    }
}

@Composable
private fun PhotoTechnicalSection(state: PhotoReviewUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Technical details", style = MaterialTheme.typography.titleMedium)
            Divider()
            Text("Observed: ${state.observedAtUtcMs?.let(::formatUtc) ?: "Unknown"}")
            Text("Coordinates: ${formatCoordinates(state.latitude, state.longitude, state.locationAccuracyMeters)}")
            Text("First photo: ${formatResolution(state.images.firstOrNull())}")
            Text("Photos: ${state.images.size}")
            state.inatObservationUrl?.let { Text("iNaturalist: $it") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoGallerySection(
    images: List<com.sound2inat.storage.PhotoDraftImageEntity>,
    onOpenImage: (com.sound2inat.storage.PhotoDraftImageEntity) -> Unit,
    onDeleteImage: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.isEmpty()) {
            Text("No photos yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Take a few photos first. You can remove any shot before upload.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 3,
            ) {
                images.forEachIndexed { index, image ->
                    PhotoThumbnail(
                        index = index + 1,
                        image = image,
                        onOpen = { onOpenImage(image) },
                        onDelete = { onDeleteImage(image.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoVisionSection(
    vision: PhotoVisionPanelUiState,
    onApplySpecies: () -> Unit,
    onApplyGenus: () -> Unit,
    onApplyFamily: () -> Unit,
    onApplyCandidate: (PhotoVisionSuggestion) -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Text("Computer vision", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedIconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close CV")
                }
            }

            when {
                vision.isLoading -> {
                    Text("Loading suggestions...")
                }
                vision.error != null -> {
                    Text(vision.error, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry) {
                        Text("Try again")
                    }
                }
                vision.ladder != null -> {
                    vision.message?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary)
                    }
                    vision.ladder.topCandidates.firstOrNull()?.let { top ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("Best suggestion", style = MaterialTheme.typography.labelMedium)
                                Text(top.commonName ?: top.scientificName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    top.scientificName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${top.rank} • ${"%.1f".format(top.score)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PhotoRankAction("Apply species", vision.ladder.topCandidates.firstOrNull(), !vision.isLoading, onApplySpecies)
                        PhotoRankAction("Apply genus", vision.ladder.higherTaxa.firstOrNull { it.rank == "genus" }, !vision.isLoading, onApplyGenus)
                        PhotoRankAction("Apply family", vision.ladder.higherTaxa.firstOrNull { it.rank == "family" }, !vision.isLoading, onApplyFamily)
                    }

                    if (vision.ladder.topCandidates.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Other options", style = MaterialTheme.typography.labelMedium)
                            vision.ladder.topCandidates.forEach { suggestion ->
                                VisionCandidateRow(
                                    suggestion = suggestion,
                                    enabled = !vision.isLoading,
                                    onApply = { onApplyCandidate(suggestion) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoRankAction(
    label: String,
    suggestion: PhotoVisionSuggestion?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        enabled = suggestion != null && enabled,
        onClick = onClick,
    ) {
        Text(label)
    }
}

@Composable
private fun VisionCandidateRow(
    suggestion: PhotoVisionSuggestion,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(suggestion.commonName ?: suggestion.scientificName, style = MaterialTheme.typography.bodyMedium)
            Text(
                suggestion.scientificName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(enabled = enabled, onClick = onApply) {
            Text("Apply")
        }
    }
}

@Composable
private fun PhotoActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (enabled) {
        FilledTonalIconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
    } else {
        OutlinedIconButton(onClick = onClick, enabled = false) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun PhotoThumbnail(
    index: Int,
    image: com.sound2inat.storage.PhotoDraftImageEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(112.dp)
            .semantics { contentDescription = "Photo $index" }
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            AsyncImage(
                model = File(image.photoPath),
                contentDescription = "Photo $index",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(0.dp)),
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(999.dp),
                    ),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete photo $index")
            }
        }
    }
}

@Composable
private fun PhotoImageDialog(
    image: com.sound2inat.storage.PhotoDraftImageEntity,
    onClose: () -> Unit,
    onCropSquare: (PhotoCropRequest) -> Unit,
    onDelete: () -> Unit,
) {
    var cropScale by remember(image.id) { mutableStateOf(1f) }
    var cropOffset by remember(image.id) { mutableStateOf(Offset.Zero) }
    var cropFrameSizePx by remember(image.id) { mutableStateOf(0) }
    val imageBounds = remember(image.photoPath) { loadImageBounds(image.photoPath) }

    fun clampOffset(offset: Offset, scale: Float): Offset {
        val bounds = imageBounds ?: return offset
        if (cropFrameSizePx <= 0) return offset
        val coverScale = maxOf(
            cropFrameSizePx.toFloat() / bounds.first.toFloat(),
            cropFrameSizePx.toFloat() / bounds.second.toFloat(),
        )
        val totalScale = coverScale * scale
        val renderedWidth = bounds.first * totalScale
        val renderedHeight = bounds.second * totalScale
        val maxX = ((renderedWidth - cropFrameSizePx) / 2f).coerceAtLeast(0f)
        val maxY = ((renderedHeight - cropFrameSizePx) / 2f).coerceAtLeast(0f)
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY),
        )
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Photo preview", style = MaterialTheme.typography.titleMedium)
                    OutlinedIconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close preview")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AsyncImage(
                            model = File(image.photoPath),
                            contentDescription = "Selected photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(image.id, cropFrameSizePx, imageBounds) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val nextScale = (cropScale * zoom).coerceIn(1f, 6f)
                                        cropScale = nextScale
                                        cropOffset = clampOffset(cropOffset + pan, nextScale)
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = cropScale
                                    scaleY = cropScale
                                    translationX = cropOffset.x
                                    translationY = cropOffset.y
                                },
                        )
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { cropFrameSizePx = it.width },
                        ) {
                            drawRect(color = Color.Black.copy(alpha = 0.32f))
                            drawRect(
                                color = Color.White,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
                            )
                            val third = size.width / 3f
                            drawLine(
                                color = Color.White.copy(alpha = 0.6f),
                                start = Offset(third, 0f),
                                end = Offset(third, size.height),
                                strokeWidth = 2f,
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.6f),
                                start = Offset(third * 2f, 0f),
                                end = Offset(third * 2f, size.height),
                                strokeWidth = 2f,
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.6f),
                                start = Offset(0f, third),
                                end = Offset(size.width, third),
                                strokeWidth = 2f,
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.6f),
                                start = Offset(0f, third * 2f),
                                end = Offset(size.width, third * 2f),
                                strokeWidth = 2f,
                            )
                        }
                    }
                }

                Text(
                    "Drag to position, pinch to zoom • ${formatResolution(image.width, image.height)} • ${image.mimeType}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhotoActionTile(
                        icon = Icons.Outlined.Search,
                        label = "Crop",
                        onClick = {
                            val request = PhotoCropRequest(
                                frameSizePx = cropFrameSizePx,
                                scale = cropScale,
                                offsetX = cropOffset.x,
                                offsetY = cropOffset.y,
                            )
                            onCropSquare(request)
                        },
                    )
                    PhotoActionTile(
                        icon = Icons.Outlined.Delete,
                        label = "Delete",
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

private val UTC_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneId.of("UTC"))

private fun formatUtc(epochMs: Long): String = UTC_FORMATTER.format(Instant.ofEpochMilli(epochMs))

private fun formatCoordinates(
    latitude: Double?,
    longitude: Double?,
    accuracyMeters: Float?,
): String {
    val lat = latitude?.let { "%.5f".format(it) } ?: return "Unknown"
    val lon = longitude?.let { "%.5f".format(it) } ?: return "Unknown"
    val accuracy = accuracyMeters?.let { " • ±${it.toInt()}m" }.orEmpty()
    return "$lat, $lon$accuracy"
}

private fun formatResolution(photo: com.sound2inat.storage.PhotoDraftImageEntity?): String {
    if (photo == null) return "Unknown"
    return formatResolution(photo.width, photo.height)
}

private fun formatResolution(width: Int?, height: Int?): String {
    if (width == null || height == null) return "Unknown"
    return "${width}×${height}px"
}

private fun loadImageBounds(path: String): Pair<Int, Int>? {
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, options)
    val width = options.outWidth.takeIf { it > 0 } ?: return null
    val height = options.outHeight.takeIf { it > 0 } ?: return null
    return width to height
}
