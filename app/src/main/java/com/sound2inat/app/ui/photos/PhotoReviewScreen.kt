package com.sound2inat.app.ui.photos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.absoluteValue

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.scientificName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
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
    onCropSquare: (PhotoCropRequest) -> Unit,
    onDelete: () -> Unit,
) {
    var cropScale by remember(image.id) { mutableStateOf(1f) }
    var cropOffset by remember(image.id) { mutableStateOf(Offset.Zero) }
    var cropFrameSizePx by remember(image.id) { mutableStateOf(0) }
    var cropInitialized by remember(image.id) { mutableStateOf(false) }
    val sourcePath = remember(image.id, image.originalPhotoPath, image.photoPath) {
        image.originalPhotoPath.takeIf { it.isNotBlank() } ?: image.photoPath
    }
    val imageBounds = remember(sourcePath) { readPhotoImageBounds(sourcePath) }

    fun clampOffset(offset: Offset, scale: Float): Offset {
        val bounds = imageBounds ?: return offset
        if (cropFrameSizePx <= 0) return offset
        val coverScale = maxOf(
            cropFrameSizePx.toFloat() / bounds.width.toFloat(),
            cropFrameSizePx.toFloat() / bounds.height.toFloat(),
        )
        val totalScale = coverScale * scale
        val renderedWidth = bounds.width * totalScale
        val renderedHeight = bounds.height * totalScale
        val maxX = ((renderedWidth - cropFrameSizePx) / 2f).coerceAtLeast(0f)
        val maxY = ((renderedHeight - cropFrameSizePx) / 2f).coerceAtLeast(0f)
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY),
        )
    }

    fun initialRequestFromSavedCrop(region: CropRegion): PhotoCropRequest? {
        val bounds = imageBounds ?: return null
        if (cropFrameSizePx <= 0 || region.size <= 0) return null
        val frameSize = cropFrameSizePx.coerceAtLeast(1)
        val coverScale = maxOf(
            frameSize.toFloat() / bounds.width.toFloat(),
            frameSize.toFloat() / bounds.height.toFloat(),
        )
        val totalScale = frameSize.toFloat() / region.size.toFloat()
        val scale = (totalScale / coverScale).coerceAtLeast(1f)
        val centerX = region.left + region.size / 2f
        val centerY = region.top + region.size / 2f
        val offsetX = (bounds.width / 2f - centerX) * totalScale
        val offsetY = (bounds.height / 2f - centerY) * totalScale
        return PhotoCropRequest(
            frameSizePx = frameSize,
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
        )
    }

    LaunchedEffect(
        cropFrameSizePx,
        sourcePath,
        image.cropLeftPx,
        image.cropTopPx,
        image.cropSizePx,
        imageBounds,
        image.id,
    ) {
        if (cropInitialized || cropFrameSizePx <= 0 || imageBounds == null) return@LaunchedEffect
        val savedRegion = image.cropLeftPx?.let { left ->
            val top = image.cropTopPx ?: return@let null
            val size = image.cropSizePx ?: return@let null
            CropRegion(left = left, top = top, size = size)
        }
        if (savedRegion != null) {
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Crop to square", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The source keeps its original shape. Only the square crop frame is saved.",
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
                            model = File(sourcePath),
                            contentDescription = "Selected photo",
                            contentScale = ContentScale.Fit,
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

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(formatCropSourceLabel(image, imageBounds)) },
                    )
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Frame 1:1") },
                    )
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(image.mimeType.uppercase()) },
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhotoActionTile(
                        icon = Icons.Outlined.Crop,
                        label = "Apply crop",
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

private fun formatResolution(width: Int?, height: Int?): String {
    if (width == null || height == null) return "Unknown"
    return "$width×${height}px"
}

private fun formatCropSourceLabel(
    image: com.sound2inat.storage.PhotoDraftImageEntity,
    sourceBounds: PhotoImageBounds?,
): String {
    val resolution = sourceBounds?.let { formatResolution(it.width, it.height) }
        ?: formatResolution(image.width, image.height).takeIf { it != "Unknown" }
        ?: "Unknown"
    val ratio = sourceBounds?.let { formatAspectRatio(it.width, it.height) }
        ?: formatAspectRatio(image.width, image.height)
    return if (ratio == null) {
        "Source $resolution"
    } else {
        "Source $resolution • $ratio"
    }
}

private fun formatAspectRatio(width: Int?, height: Int?): String? {
    val w = width ?: return null
    val h = height ?: return null
    if (w <= 0 || h <= 0) return null
    val divisor = gcd(w, h).coerceAtLeast(1)
    return "${w / divisor}:${h / divisor}"
}

private fun gcd(first: Int, second: Int): Int {
    var a = first
    var b = second
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a.absoluteValue
}
