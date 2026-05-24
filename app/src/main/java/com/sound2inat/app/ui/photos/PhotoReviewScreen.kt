package com.sound2inat.app.ui.photos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.sound2inat.app.R
import com.sound2inat.inat.SubmissionProgress
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoReviewScreen(
    onBack: () -> Unit,
    onAddMorePhotos: (String) -> Unit,
) {
    val vm: PhotoReviewViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var selectedImage by remember { mutableStateOf<com.sound2inat.storage.PhotoDraftImageEntity?>(null) }
    var heroImageId by rememberSaveable(state.draftId) { mutableStateOf<String?>(null) }

    LaunchedEffect(state.images) {
        val firstId = state.images.firstOrNull()?.id
        if (firstId != null && state.images.none { it.id == heroImageId }) {
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

    PullToRefreshBox(
        isRefreshing = state.isSyncingObservation,
        onRefresh = { vm.syncObservationDetails(force = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .padding(bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.incompleteObservation?.let { incomplete ->
                    PhotoIncompleteObservationBanner(
                        row = incomplete,
                        retrying = state.retryingIncomplete,
                        lastError = state.retryIncompleteError,
                        onView = { uriHandler.openUri(it) },
                        onRecreate = { vm.retryIncomplete() },
                    )
                }

                PhotoHeroSection(
                    images = state.images,
                    heroImage = heroImage,
                    onSelectHero = { heroImageId = it.id },
                    onOpenHero = { image -> selectedImage = image },
                    onDeleteImage = { imageId -> scope.launch { vm.deleteImage(imageId) } },
                    onAddMorePhotos = { onAddMorePhotos(state.draftId) },
                )

                IdentificationStatusCard(state = state)

                PhotoObservationSyncCard(
                    state = state,
                    onRefresh = { vm.syncObservationDetails(force = true) },
                )

                val showVisionCard = state.isUploaded || state.vision.isLoading ||
                    state.vision.error != null || state.vision.ladder != null || state.vision.message != null
                if (showVisionCard) {
                    PhotoVisionSuggestionsCard(
                        state = state,
                        onApplySuggestion = { suggestion, label -> vm.applyVisionSuggestion(suggestion, label) },
                        onRetry = { vm.loadVisionSuggestions() },
                    )
                }

                PhotoObservationFooter(
                    state = state,
                    onOpenObservation = { uriHandler.openUri(it) },
                )

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

            PhotoSubmitBottomBar(
                state = state,
                onUpload = { vm.submit() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    selectedImage?.let { image ->
        PhotoImageDialog(
            image = image,
            onClose = { selectedImage = null },
            onCrop = { request ->
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
    onAddMorePhotos: () -> Unit,
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
                            OutlinedButton(onClick = onAddMorePhotos) {
                                Icon(imageVector = Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                                Text("Add photos")
                            }
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
                    FilledIconButton(
                        onClick = { onDeleteImage(cover.id) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(34.dp),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete selected photo")
                    }
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(images.size.toString()) },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
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
                            )
                        }
                        AddPhotoThumbnail(onClick = onAddMorePhotos)
                    }
                } else {
                    AddPhotoThumbnail(onClick = onAddMorePhotos)
                }
            }
        }
    }
}

@Composable
private fun PhotoObservationFooter(
    state: PhotoReviewUiState,
    onOpenObservation: (String) -> Unit,
) {
    val observationUrl = state.observationUrl
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Observed ${state.observedAtUtcMs?.let(::formatUtc) ?: "Unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Coordinates ${formatCoordinates(
                    state.latitude,
                    state.longitude,
                    state.locationAccuracyMeters
                )}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                state.submitError != null && !state.isUploaded -> {
                    Text(
                        text = "iNaturalist upload error: ${state.submitError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.isUploaded -> {
                    state.inatObservationId?.let { id ->
                        if (observationUrl != null) {
                            TextButton(onClick = { onOpenObservation(observationUrl) }) {
                                Text("Observation ID $id")
                            }
                        } else {
                            Text(
                                text = "Observation ID $id",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        text = "iNaturalist not uploaded yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun PhotoSubmitBottomBar(
    state: PhotoReviewUiState,
    onUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canUpload = state.images.isNotEmpty() &&
        !state.isSubmitting &&
        !state.isUploaded &&
        state.incompleteObservation == null
    val label = when {
        state.isSubmitting -> "Uploading…"
        state.isUploaded -> "Already uploaded"
        state.incompleteObservation != null -> "Recreate incomplete first"
        state.images.isEmpty() -> "Add photos to upload"
        else -> "Upload to iNaturalist"
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isSubmitting) {
                PhotoSubmissionProgressChecklist(progress = state.submissionProgress)
            }
            Button(
                onClick = onUpload,
                enabled = canUpload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(label)
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun PhotoSubmissionProgressChecklist(progress: SubmissionProgress?) {
    val step = (progress as? SubmissionProgress.Species)?.step
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.photo_submit_progress_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        PhotoProgressRow(
            label = stringResource(R.string.photo_submit_step_creating),
            step = step,
            rowStep = SubmissionProgress.Step.CreatingObservation,
        )
        PhotoProgressRow(
            label = stringResource(R.string.photo_submit_step_photo_primary),
            step = step,
            rowStep = SubmissionProgress.Step.UploadingPrimaryPhoto,
        )
        PhotoProgressRow(
            label = stringResource(R.string.photo_submit_step_photo_extra),
            step = step,
            rowStep = SubmissionProgress.Step.UploadingExtraPhoto,
        )
        PhotoProgressRow(
            label = stringResource(R.string.photo_submit_step_tag),
            step = step,
            rowStep = SubmissionProgress.Step.ApplyingTag,
        )
        PhotoProgressRow(
            label = stringResource(R.string.photo_submit_step_persisting),
            step = step,
            rowStep = SubmissionProgress.Step.Persisting,
        )
    }
}

private enum class PhotoProgressRowState { Pending, InProgress, Done, Failed }

@Suppress("FunctionNaming")
@Composable
private fun PhotoProgressRow(
    label: String,
    step: SubmissionProgress.Step?,
    rowStep: SubmissionProgress.Step,
) {
    val orderedSteps = photoProgressSteps()
    val rowIndex = orderedSteps.indexOf(rowStep)
    val currentIndex = orderedSteps.indexOf(step)
    val state = when {
        step == SubmissionProgress.Step.DoneOk -> PhotoProgressRowState.Done
        step == SubmissionProgress.Step.DoneFailed && rowIndex == currentIndex + 1 -> PhotoProgressRowState.Failed
        step == rowStep -> PhotoProgressRowState.InProgress
        currentIndex >= 0 && rowIndex < currentIndex -> PhotoProgressRowState.Done
        else -> PhotoProgressRowState.Pending
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            PhotoProgressRowState.InProgress -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> {
                val icon = when (state) {
                    PhotoProgressRowState.Pending -> Icons.Outlined.RadioButtonUnchecked
                    PhotoProgressRowState.Done -> Icons.Outlined.CheckCircle
                    PhotoProgressRowState.Failed -> Icons.Outlined.ErrorOutline
                    PhotoProgressRowState.InProgress -> error("unreachable")
                }
                val tint = when (state) {
                    PhotoProgressRowState.Pending -> MaterialTheme.colorScheme.outline
                    PhotoProgressRowState.Done -> MaterialTheme.colorScheme.primary
                    PhotoProgressRowState.Failed -> MaterialTheme.colorScheme.error
                    PhotoProgressRowState.InProgress -> error("unreachable")
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun photoProgressSteps(): List<SubmissionProgress.Step> = listOf(
    SubmissionProgress.Step.CreatingObservation,
    SubmissionProgress.Step.UploadingPrimaryPhoto,
    SubmissionProgress.Step.UploadingExtraPhoto,
    SubmissionProgress.Step.ApplyingTag,
    SubmissionProgress.Step.Persisting,
)

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun PhotoIncompleteObservationBanner(
    row: IncompletePhotoObservationUi,
    retrying: Boolean,
    lastError: String?,
    onView: (String) -> Unit,
    onRecreate: () -> Unit,
) {
    var confirmOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.photo_incomplete_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                stringResource(R.string.photo_incomplete_banner_subtitle),
                style = MaterialTheme.typography.bodySmall,
            )
            if (lastError != null) {
                Text(
                    lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                row.scientificName,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onView(row.url) }) {
                    Text(stringResource(R.string.btn_view))
                }
                TextButton(
                    onClick = { confirmOpen = true },
                    enabled = !retrying,
                ) {
                    Text(
                        if (retrying) {
                            stringResource(R.string.incomplete_obs_recreating)
                        } else {
                            stringResource(R.string.incomplete_obs_action_recreate)
                        },
                    )
                }
            }
        }
    }
    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text(stringResource(R.string.dialog_recreate_title)) },
            text = { Text(stringResource(R.string.dialog_recreate_body, row.scientificName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    onRecreate()
                }) { Text(stringResource(R.string.btn_recreate)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
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
        }
    }
}

@Composable
private fun AddPhotoThumbnail(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .widthIn(max = 92.dp)
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = "Add photos",
                )
            }
        }
    }
}

@Composable
private fun PhotoImageDialog(
    image: com.sound2inat.storage.PhotoDraftImageEntity,
    onClose: () -> Unit,
    onCrop: (PhotoCropRequest) -> Unit,
    onDelete: () -> Unit,
) {
    var cropScale by remember(image.id) { mutableStateOf(1f) }
    var cropOffset by remember(image.id) { mutableStateOf(Offset.Zero) }
    var cropRotationDegrees by remember(image.id) { mutableStateOf(0) }
    var cropMode by rememberSaveable(image.id) { mutableStateOf(initialCropMode(image)) }
    var cropViewportWidthPx by remember(image.id) { mutableStateOf(0) }
    var cropViewportHeightPx by remember(image.id) { mutableStateOf(0) }
    var cropInitialized by remember(image.id) { mutableStateOf(false) }
    val sourcePath = remember(image.id, image.originalPhotoPath, image.photoPath) {
        image.originalPhotoPath.takeIf { it.isNotBlank() } ?: image.photoPath
    }
    val imageBounds = remember(sourcePath) { readPhotoImageBounds(sourcePath) }
    val displayBounds = remember(imageBounds, cropRotationDegrees) {
        rotatedPhotoBounds(imageBounds, cropRotationDegrees)
    }
    val previewAspectRatio = remember(displayBounds) {
        displayBounds?.let { it.width.toFloat() / it.height.toFloat() } ?: 1f
    }
    val cropFrameSize = remember(cropViewportWidthPx, cropViewportHeightPx, cropMode) {
        cropFrameSize(
            previewSize = IntSize(cropViewportWidthPx, cropViewportHeightPx),
            mode = cropMode,
        )
    }

    fun viewportWidthPx(): Int = cropViewportWidthPx.coerceAtLeast(1)

    fun viewportHeightPx(): Int = cropViewportHeightPx.coerceAtLeast(1)

    fun frameWidthPx(): Int = cropFrameSize.width.coerceAtLeast(1)

    fun frameHeightPx(): Int = cropFrameSize.height.coerceAtLeast(1)

    fun clampOffset(offset: Offset, scale: Float): Offset {
        val bounds = displayBounds ?: return offset
        if (cropViewportWidthPx <= 0 || cropViewportHeightPx <= 0) return offset
        val width = frameWidthPx()
        val height = frameHeightPx()
        val coverScale = maxOf(
            viewportWidthPx().toFloat() / bounds.width.toFloat(),
            viewportHeightPx().toFloat() / bounds.height.toFloat(),
        )
        val totalScale = coverScale * scale
        val renderedWidth = bounds.width * totalScale
        val renderedHeight = bounds.height * totalScale
        val maxX = ((renderedWidth - width) / 2f).coerceAtLeast(0f)
        val maxY = ((renderedHeight - height) / 2f).coerceAtLeast(0f)
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY),
        )
    }

    fun initialRequestFromSavedCrop(region: CropRegion): PhotoCropRequest? {
        val bounds = imageBounds ?: return null
        if (cropViewportWidthPx <= 0 || cropViewportHeightPx <= 0 || region.size <= 0) return null
        val frameWidth = frameWidthPx()
        val frameHeight = frameHeightPx()
        val coverScale = maxOf(
            viewportWidthPx().toFloat() / bounds.width.toFloat(),
            viewportHeightPx().toFloat() / bounds.height.toFloat(),
        )
        val totalScale = frameWidth.toFloat() / region.size.toFloat()
        val scale = (totalScale / coverScale).coerceAtLeast(1f)
        val centerX = region.left + region.size / 2f
        val centerY = region.top + region.size / 2f
        val offsetX = (bounds.width / 2f - centerX) * totalScale
        val offsetY = (bounds.height / 2f - centerY) * totalScale
        return PhotoCropRequest(
            frameSizePx = frameWidth,
            frameHeightPx = frameHeight,
            viewportWidthPx = viewportWidthPx(),
            viewportHeightPx = viewportHeightPx(),
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            rotationDegrees = cropRotationDegrees,
            cropMode = PhotoCropMode.Square,
        )
    }

    fun rotateCrop(deltaDegrees: Int) {
        cropRotationDegrees = normalizeRotationDegrees(cropRotationDegrees + deltaDegrees)
        cropOffset = clampOffset(cropOffset, cropScale)
    }

    LaunchedEffect(cropMode, cropViewportWidthPx, cropViewportHeightPx, imageBounds) {
        cropOffset = clampOffset(cropOffset, cropScale)
    }

    LaunchedEffect(
        cropViewportWidthPx,
        cropViewportHeightPx,
        sourcePath,
        image.cropLeftPx,
        image.cropTopPx,
        image.cropSizePx,
        imageBounds,
        image.id,
    ) {
        val canRestoreSavedCrop = !cropInitialized &&
            cropViewportWidthPx > 0 &&
            cropViewportHeightPx > 0 &&
            imageBounds != null
        if (!canRestoreSavedCrop) {
            return@LaunchedEffect
        }
        val savedRegion = image.cropLeftPx?.let { left ->
            val top = image.cropTopPx ?: return@let null
            val size = image.cropSizePx ?: return@let null
            CropRegion(left = left, top = top, width = size, height = size)
        }
        if (savedRegion != null) {
            cropMode = PhotoCropMode.Square
            initialRequestFromSavedCrop(savedRegion)?.let { request ->
                cropScale = request.scale
                cropOffset = Offset(request.offsetX, request.offsetY)
            }
        }
        cropInitialized = true
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Crop", style = MaterialTheme.typography.titleMedium)
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    PhotoCropMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = cropMode == mode,
                            onClick = { cropMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = PhotoCropMode.entries.size,
                            ),
                            label = {
                                Text(
                                    stringResource(
                                        if (mode == PhotoCropMode.Original) {
                                            R.string.photo_crop_mode_original
                                        } else {
                                            R.string.photo_crop_mode_square
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(previewAspectRatio)
                        .onSizeChanged {
                            cropViewportWidthPx = it.width
                            cropViewportHeightPx = it.height
                        }
                        .clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                    ) {
                        AsyncImage(
                            model = File(sourcePath),
                            contentDescription = "Selected photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(
                                    image.id,
                                    cropViewportWidthPx,
                                    cropViewportHeightPx,
                                    imageBounds,
                                    cropMode,
                                ) {
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
                                    rotationZ = cropRotationDegrees.toFloat()
                                },
                        )
                        Canvas(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            val frameWidth = cropFrameSize.width.coerceAtLeast(1).toFloat()
                            val frameHeight = cropFrameSize.height.coerceAtLeast(1).toFloat()
                            val left = (size.width - frameWidth) / 2f
                            val top = (size.height - frameHeight) / 2f
                            val mask = Color.Black.copy(alpha = 0.32f)
                            drawRect(color = mask, size = Size(size.width, top))
                            drawRect(
                                color = mask,
                                topLeft = Offset(0f, top + frameHeight),
                                size = Size(size.width, size.height - top - frameHeight),
                            )
                            drawRect(color = mask, topLeft = Offset(0f, top), size = Size(left, frameHeight))
                            drawRect(
                                color = mask,
                                topLeft = Offset(left + frameWidth, top),
                                size = Size(size.width - left - frameWidth, frameHeight),
                            )
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(left, top),
                                size = Size(frameWidth, frameHeight),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
                            )
                            val thirdX = frameWidth / 3f
                            val thirdY = frameHeight / 3f
                            drawLine(
                                color = Color.White.copy(alpha = 0.6f),
                                start = Offset(left + thirdX, top),
                                end = Offset(left + thirdX, top + frameHeight),
                                strokeWidth = 2f,
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.6f),
                                start = Offset(left + thirdX * 2f, top),
                                end = Offset(left + thirdX * 2f, top + frameHeight),
                                strokeWidth = 2f,
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.6f),
                                start = Offset(left, top + thirdY),
                                end = Offset(left + frameWidth, top + thirdY),
                                strokeWidth = 2f,
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.6f),
                                start = Offset(left, top + thirdY * 2f),
                                end = Offset(left + frameWidth, top + thirdY * 2f),
                                strokeWidth = 2f,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhotoActionTile(
                        icon = Icons.AutoMirrored.Outlined.RotateLeft,
                        label = "Rotate left",
                        onClick = { rotateCrop(-90) },
                    )
                    PhotoActionTile(
                        icon = Icons.AutoMirrored.Outlined.RotateRight,
                        label = "Rotate right",
                        onClick = { rotateCrop(90) },
                    )
                    PhotoActionTile(
                        icon = Icons.Outlined.Crop,
                        label = if (cropMode == PhotoCropMode.Original) {
                            stringResource(R.string.photo_crop_apply_original)
                        } else {
                            stringResource(R.string.photo_crop_apply_square)
                        },
                        onClick = {
                            val request = PhotoCropRequest(
                                frameSizePx = frameWidthPx(),
                                frameHeightPx = frameHeightPx(),
                                viewportWidthPx = viewportWidthPx(),
                                viewportHeightPx = viewportHeightPx(),
                                scale = cropScale,
                                offsetX = cropOffset.x,
                                offsetY = cropOffset.y,
                                rotationDegrees = cropRotationDegrees,
                                cropMode = cropMode,
                            )
                            onCrop(request)
                        },
                    )
                    PhotoActionTile(
                        icon = Icons.Outlined.Delete,
                        label = "Delete",
                        onClick = onDelete,
                    )
                    PhotoActionTile(
                        icon = Icons.Outlined.Close,
                        label = "Close preview",
                        onClick = onClose,
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

internal fun initialCropMode(image: com.sound2inat.storage.PhotoDraftImageEntity): PhotoCropMode =
    if (image.cropLeftPx != null && image.cropTopPx != null && image.cropSizePx != null) {
        PhotoCropMode.Square
    } else {
        PhotoCropMode.Original
    }

internal fun cropFrameSize(previewSize: IntSize, mode: PhotoCropMode): IntSize {
    val width = previewSize.width.coerceAtLeast(1)
    val height = previewSize.height.coerceAtLeast(1)
    return when (mode) {
        PhotoCropMode.Original -> IntSize(width = width, height = height)
        PhotoCropMode.Square -> {
            val side = minOf(width, height)
            IntSize(width = side, height = side)
        }
    }
}

internal fun rotatedPhotoBounds(bounds: PhotoImageBounds?, rotationDegrees: Int): PhotoImageBounds? {
    val imageBounds = bounds ?: return null
    val normalized = normalizeRotationDegrees(rotationDegrees)
    return if (normalized % 180 == 0) {
        imageBounds
    } else {
        PhotoImageBounds(width = imageBounds.height, height = imageBounds.width)
    }
}

internal fun normalizeRotationDegrees(rotationDegrees: Int): Int =
    ((rotationDegrees % 360) + 360) % 360
