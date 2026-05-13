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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
    var heroImageId by rememberSaveable(state.draftId) { mutableStateOf<String?>(null) }

    LaunchedEffect(state.images) {
        val firstId = state.images.firstOrNull()?.id
        if (firstId != null && heroImageId !in state.images.map { it.id }) {
            heroImageId = firstId
        }
    }

    val heroImage = state.images.firstOrNull { it.id == heroImageId } ?: state.images.firstOrNull()

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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PhotoHeroSection(
            images = state.images,
            heroImage = heroImage,
            onSelectHero = { heroImageId = it.id },
            onOpenHero = { image -> selectedImage = image },
            onDeleteImage = { imageId -> scope.launch { vm.deleteImage(imageId) } },
        )

        PhotoMetadataStrip(state = state)

        PhotoActionRail(
            canUpload = state.images.isNotEmpty() && state.inatObservationId == null && !state.isSubmitting,
            canRunVision = state.inatObservationId != null && !state.vision.isLoading,
            onAddMorePhotos = { onAddMorePhotos(state.draftId) },
            onUpload = { vm.submit() },
            onRunVision = { vm.loadVisionSuggestions() },
        )

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhotoActionTile(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                label = "Back",
                onClick = onBack,
            )
            PhotoActionTile(
                icon = Icons.Outlined.Delete,
                label = "Delete album",
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
private fun PhotoHeroSection(
    images: List<com.sound2inat.storage.PhotoDraftImageEntity>,
    heroImage: com.sound2inat.storage.PhotoDraftImageEntity?,
    onSelectHero: (com.sound2inat.storage.PhotoDraftImageEntity) -> Unit,
    onOpenHero: (com.sound2inat.storage.PhotoDraftImageEntity) -> Unit,
    onDeleteImage: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (images.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.AddPhotoAlternate,
                                contentDescription = null,
                            )
                            Text("No photos yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Take a few shots to build the album.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                val cover = heroImage ?: images.first()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clickable(onClick = { onOpenHero(cover) }),
                ) {
                    AsyncImage(
                        model = File(cover.photoPath),
                        contentDescription = "Cover photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.02f),
                                        Color.Black.copy(alpha = 0.42f),
                                    ),
                                ),
                            ),
                    )
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("${images.size} photo${if (images.size == 1) "" else "s"}") },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                    )
                    Text(
                        "Tap any photo to inspect or crop it.",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                    )
                }

                if (images.size > 1) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        images.forEachIndexed { index, image ->
                            PhotoThumbnail(
                                index = index + 1,
                                image = image,
                                compact = true,
                                selected = image.id == cover.id,
                                onOpen = { onSelectHero(image) },
                                onDelete = { onDeleteImage(image.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoMetadataStrip(state: PhotoReviewUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        FlowRow(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhotoMetaChip(
                label = "Observed",
                value = state.observedAtUtcMs?.let(::formatUtc) ?: "Unknown",
            )
            PhotoMetaChip(
                label = "Coordinates",
                value = formatCoordinates(state.latitude, state.longitude, state.locationAccuracyMeters),
            )
            PhotoMetaChip(
                label = "Resolution",
                value = formatResolution(state.images.firstOrNull()),
            )
            PhotoMetaChip(
                label = "Photos",
                value = state.images.size.toString(),
            )
            state.inatObservationUrl?.let {
                PhotoMetaChip(
                    label = "iNaturalist",
                    value = "Uploaded",
                )
            }
        }
    }
}

@Composable
private fun PhotoActionRail(
    canUpload: Boolean,
    canRunVision: Boolean,
    onAddMorePhotos: () -> Unit,
    onUpload: () -> Unit,
    onRunVision: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoActionTile(
                icon = Icons.Outlined.AddPhotoAlternate,
                label = "Add photos",
                onClick = onAddMorePhotos,
            )
            PhotoActionTile(
                icon = Icons.Outlined.CloudUpload,
                label = "Upload to iNaturalist",
                enabled = canUpload,
                onClick = onUpload,
            )
            PhotoActionTile(
                icon = Icons.Outlined.AutoAwesome,
                label = "Computer vision",
                enabled = canRunVision,
                onClick = onRunVision,
            )
        }
    }
}

@Composable
private fun PhotoMetaChip(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    )
    {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
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
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
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
    compact: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnailWidth = if (compact) 92.dp else 112.dp
    Card(
        modifier = Modifier
            .widthIn(max = thumbnailWidth)
            .semantics { contentDescription = "Photo $index" }
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(if (compact) 18.dp else 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = thumbnailWidth)
                .aspectRatio(1f),
        ) {
            AsyncImage(
                model = File(image.photoPath),
                contentDescription = "Photo $index",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(32.dp)
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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Adjust crop", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Drag and pinch to fit the frame.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
                    "${formatResolution(image.width, image.height)} • ${image.mimeType}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhotoActionTile(
                        icon = Icons.Outlined.Crop,
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
