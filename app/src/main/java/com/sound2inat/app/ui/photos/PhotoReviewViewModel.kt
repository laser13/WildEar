package com.sound2inat.app.ui.photos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.inat.INatAuthRepository
import com.sound2inat.inat.INaturalistClient
import com.sound2inat.inat.ObservationDetail
import com.sound2inat.inat.PhotoAnnotationUseCase
import com.sound2inat.inat.PhotoSubmitResult
import com.sound2inat.inat.PhotoSubmitter
import com.sound2inat.inat.PhotoVisionPlanner
import com.sound2inat.inat.PhotoVisionSuggestion
import com.sound2inat.inat.PhotoVisionTarget
import com.sound2inat.inat.PhotoVisionUseCase
import com.sound2inat.storage.PhotoDraftEntity
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PhotoReviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val repo: PhotoDraftRepository,
    private val fileStore: PhotoObservationFileStore,
    private val auth: INatAuthRepository,
    private val client: INaturalistClient,
    private val submitter: PhotoSubmitter,
    private val cropper: PhotoImageCropper,
    private val visionUseCase: PhotoVisionUseCase,
    private val annotationUseCase: PhotoAnnotationUseCase,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        repo: PhotoDraftRepository,
        fileStore: PhotoObservationFileStore,
        auth: INatAuthRepository,
        client: INaturalistClient,
        submitter: PhotoSubmitter,
        cropper: PhotoImageCropper,
        visionUseCase: PhotoVisionUseCase,
        annotationUseCase: PhotoAnnotationUseCase,
    ) : this(
        savedStateHandle = savedStateHandle,
        repo = repo,
        fileStore = fileStore,
        auth = auth,
        client = client,
        submitter = submitter,
        cropper = cropper,
        visionUseCase = visionUseCase,
        annotationUseCase = annotationUseCase,
        externalScope = null,
    )

    private val draftId = checkNotNull(savedStateHandle.get<String>("photoDraftId")) {
        "photoDraftId is required"
    }
    private val scope = externalScope ?: viewModelScope
    private var lastAutoSyncObservationId: Long? = null
    private val _state = MutableStateFlow(PhotoReviewUiState(draftId = draftId))
    val state: StateFlow<PhotoReviewUiState> = _state.asStateFlow()

    init {
        scope.launch {
            repo.observeWithImages(draftId)
                .catch { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
                .collect { withImages ->
                    if (withImages == null) {
                        _state.update { current ->
                            current.copy(
                                draftId = draftId,
                                isLoading = false,
                                images = emptyList(),
                                observedAtUtcMs = null,
                                latitude = null,
                                longitude = null,
                                locationAccuracyMeters = null,
                                inatObservationId = null,
                                inatObservationUuid = null,
                                inatObservationUrl = null,
                                observationDetail = null,
                                isSyncingObservation = false,
                                syncError = null,
                            )
                        }
                    } else {
                        val draft = withImages.draft
                        _state.update { current ->
                            current.copy(
                                draftId = draftId,
                                isLoading = false,
                                images = withImages.images,
                                observedAtUtcMs = draft.observedAtUtcMs,
                                latitude = draft.latitude,
                                longitude = draft.longitude,
                                locationAccuracyMeters = draft.locationAccuracyMeters,
                                inatObservationId = draft.inatObservationId,
                                inatObservationUuid = draft.inatObservationUuid,
                                inatObservationUrl = draft.inatObservationUrl,
                                taxonScientificName = draft.taxonScientificName,
                                taxonCommonName = draft.taxonCommonName,
                                taxonInatId = draft.taxonInatId,
                                description = draft.description,
                                submitError = draft.inatLastError ?: current.submitError,
                                uploadedUrl = draft.inatObservationUrl ?: current.uploadedUrl,
                                observationDetail = current.observationDetail,
                                isSyncingObservation = current.isSyncingObservation,
                                syncError = current.syncError,
                            )
                        }
                    }
                }
        }
        scope.launch {
            repo.observeIncomplete(draftId)
                .catch { e -> _state.update { it.copy(retryIncompleteError = e.message) } }
                .collect { draft ->
                    _state.update { current ->
                        current.copy(
                            incompleteObservation = draft?.toIncompleteUi(),
                            retryIncompleteError = if (draft == null) null else current.retryIncompleteError,
                        )
                    }
                }
        }
        // Trigger auto-sync only when inatObservationId actually changes,
        // not on every state emission (B5 fix).
        scope.launch {
            _state
                .distinctUntilChangedBy { it.inatObservationId }
                .collect { syncObservationDetails() }
        }
    }

    suspend fun deleteImage(imageId: String) {
        repo.deleteImage(imageId)
    }

    suspend fun cropImageSquare(imageId: String) {
        val image = repo.getImageById(imageId) ?: return
        val current = File(image.photoPath)
        val original = ensureOriginalPhoto(image.id, image.photoDraftId, image.originalPhotoPath, current)
        val newPhotoFile = fileStore.newPhotoFile(image.photoDraftId, "${image.id}-${UUID.randomUUID()}")
        val info = cropper.cropCenterSquare(original, newPhotoFile)
        repo.updateImageCrop(
            imageId = imageId,
            originalPhotoPath = original.absolutePath,
            newPhotoPath = newPhotoFile,
            cropLeftPx = info.cropRegion.left,
            cropTopPx = info.cropRegion.top,
            cropSizePx = info.cropRegion.size,
            width = info.width,
            height = info.height,
        )
    }

    suspend fun cropImage(
        imageId: String,
        request: PhotoCropRequest,
    ) {
        val image = repo.getImageById(imageId) ?: return
        val current = File(image.photoPath)
        val original = ensureOriginalPhoto(image.id, image.photoDraftId, image.originalPhotoPath, current)
        val newPhotoFile = fileStore.newPhotoFile(image.photoDraftId, "${image.id}-${UUID.randomUUID()}")
        val info = cropper.cropFromViewport(original, newPhotoFile, request)
        repo.updateImageCrop(
            imageId = imageId,
            originalPhotoPath = original.absolutePath,
            newPhotoPath = newPhotoFile,
            cropLeftPx = info.cropRegion.left,
            cropTopPx = info.cropRegion.top,
            cropSizePx = info.cropRegion.size,
            width = info.width,
            height = info.height,
        )
    }

    suspend fun deleteAlbum() {
        repo.deleteDraft(draftId)
    }

    suspend fun saveDetails(
        taxonScientificName: String?,
        taxonCommonName: String?,
        taxonInatId: Long?,
        description: String?,
    ) {
        repo.updateDetails(
            draftId = draftId,
            taxonScientificName = taxonScientificName?.trim()?.takeIf { it.isNotEmpty() },
            taxonCommonName = taxonCommonName?.trim()?.takeIf { it.isNotEmpty() },
            taxonInatId = taxonInatId,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun loadVisionSuggestions() {
        val observationId = _state.value.inatObservationId ?: run {
            _state.update {
                it.copy(
                    vision = PhotoVisionPanelUiState(
                        error = "Upload this photo observation before requesting iNaturalist suggestions.",
                    ),
                )
            }
            return
        }
        if (_state.value.vision.isLoading) return
        _state.update { it.copy(vision = it.vision.copy(isLoading = true, error = null, message = null)) }
        scope.launch {
            val token = auth.getValidToken().orEmpty()
            if (token.isBlank()) {
                _state.update {
                    it.copy(
                        vision = it.vision.copy(isLoading = false, error = "No iNaturalist token in Settings")
                    )
                }
                return@launch
            }

            runCatching {
                visionUseCase.scoreSuggestions(token, observationId)
            }.onSuccess { ladder ->
                _state.update { it.copy(vision = PhotoVisionPanelUiState(isLoading = false, ladder = ladder)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        vision = PhotoVisionPanelUiState(
                            isLoading = false,
                            error = error.message ?: "Vision unavailable",
                        ),
                    )
                }
            }
        }
    }

    fun syncObservationDetails(force: Boolean = false) {
        val observationId = _state.value.resolvedObservationId ?: return
        if (!force && lastAutoSyncObservationId == observationId) return
        if (_state.value.isSyncingObservation) return

        lastAutoSyncObservationId = observationId
        _state.update { it.copy(isSyncingObservation = true, syncError = null) }
        scope.launch {
            runCatching {
                client.getObservation(observationId.toString())
            }.onSuccess { detail ->
                _state.update {
                    it.copy(
                        isSyncingObservation = false,
                        syncError = null,
                        observationDetail = detail.toUiState(),
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isSyncingObservation = false,
                        syncError = error.message ?: "Could not sync iNaturalist details",
                    )
                }
            }
        }
    }

    fun clearVisionSuggestions() {
        _state.update { it.copy(vision = PhotoVisionPanelUiState()) }
    }

    fun applyVision(target: PhotoVisionTarget) {
        val ladder = _state.value.vision.ladder ?: return
        val suggestion = PhotoVisionPlanner.chooseSuggestion(ladder, target) ?: run {
            _state.update { it.copy(vision = it.vision.copy(error = "No ${target.name.lowercase()} suggestion available")) }
            return
        }
        applyVisionSuggestion(suggestion, target.name.lowercase())
    }

    fun applyVisionSuggestion(suggestion: PhotoVisionSuggestion, label: String = suggestion.rank) {
        val observationId = _state.value.inatObservationId ?: run {
            _state.update {
                it.copy(
                    vision = it.vision.copy(
                        error = "Upload this photo observation before requesting iNaturalist suggestions.",
                    ),
                )
            }
            return
        }
        val observationUuid = _state.value.inatObservationUuid?.takeIf { it.isNotBlank() } ?: run {
            _state.update { it.copy(vision = it.vision.copy(error = "Missing iNaturalist observation UUID")) }
            return
        }
        if (_state.value.vision.isLoading) return
        _state.update { it.copy(vision = it.vision.copy(isLoading = true, error = null, message = null)) }

        scope.launch {
            val token = auth.getValidToken().orEmpty()
            if (token.isBlank()) {
                _state.update {
                    it.copy(
                        vision = it.vision.copy(
                            isLoading = false,
                            error = "No iNaturalist token in Settings",
                        ),
                    )
                }
                return@launch
            }

            runCatching {
                annotationUseCase.addIdentification(
                    token = token,
                    observationId = observationId,
                    suggestion = suggestion,
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        vision = it.vision.copy(
                            isLoading = false,
                            error = error.message ?: "Could not apply iNaturalist identification",
                        ),
                    )
                }
                return@launch
            }

            val warnings = mutableListOf<String>()
            runCatching { annotationUseCase.applyAnnotations(token, observationUuid, suggestion) }
                .onFailure { error ->
                    warnings += error.message ?: "Annotation update failed"
                }

            repo.updateDetails(
                draftId = draftId,
                taxonScientificName = suggestion.scientificName,
                taxonCommonName = suggestion.commonName,
                taxonInatId = suggestion.taxonId,
                description = _state.value.description,
            )

            _state.update {
                it.copy(
                    vision = PhotoVisionPanelUiState(
                        isLoading = false,
                        message = buildString {
                            append("Added $label: ${suggestion.scientificName}")
                            if (warnings.isNotEmpty()) {
                                append(" | ")
                                append(warnings.joinToString(" | "))
                            }
                        }.takeIf { text -> text.isNotBlank() },
                        ladder = it.vision.ladder,
                    ),
                )
            }
            syncObservationDetails(force = true)
        }
    }

    fun submit() {
        if (_state.value.isSubmitting) return
        _state.update { it.copy(isSubmitting = true, submitError = null, submissionProgress = null) }
        scope.launch {
            val token = auth.getValidToken().orEmpty()
            when (
                val result = submitter.submit(token, draftId) { progress ->
                    _state.update { it.copy(submissionProgress = progress) }
                }
            ) {
                is PhotoSubmitResult.Ok -> {
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            uploadedUrl = result.observationUrl,
                            submitError = result.warnings.joinToString(" | ").takeIf { text -> text.isNotBlank() },
                            submissionProgress = null,
                        )
                    }
                }
                is PhotoSubmitResult.Failure -> {
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            submitError = result.message,
                            submissionProgress = null,
                        )
                    }
                }
            }
        }
    }

    fun retryIncomplete() {
        val target = _state.value.incompleteObservation ?: return
        if (_state.value.retryingIncomplete) return
        _state.update { it.copy(retryingIncomplete = true, retryIncompleteError = null) }
        scope.launch {
            try {
                val token = auth.getValidToken().orEmpty()
                if (token.isBlank()) error("No iNaturalist token in Settings")
                client.deleteObservation(token, target.observationId)
                repo.clearIncompleteUpload(draftId)
                _state.update {
                    it.copy(
                        retryingIncomplete = false,
                        retryIncompleteError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        retryingIncomplete = false,
                        retryIncompleteError = e.message ?: "Could not recreate observation",
                    )
                }
            }
        }
    }

    private suspend fun ensureOriginalPhoto(
        imageId: String,
        draftId: String,
        originalPhotoPath: String,
        currentFile: File,
    ): File = withContext(Dispatchers.IO) {
        val currentOriginal = File(originalPhotoPath)
        if (currentOriginal.exists() && currentOriginal.absolutePath != currentFile.absolutePath) {
            return@withContext currentOriginal
        }
        val backup = fileStore.originalPhotoFile(draftId, imageId)
        if (!backup.exists()) {
            currentFile.copyTo(backup, overwrite = true)
        }
        backup
    }

    private fun ObservationDetail.toUiState(): PhotoObservationDetailUiState =
        PhotoObservationDetailUiState(
            qualityGrade = qualityGrade,
            agreeingIdCount = agreeingIdCount,
            commentsCount = commentsCount,
            comments = comments,
            taxonScientificName = taxonName,
            taxonCommonName = taxonCommonName,
        )

    private fun PhotoDraftEntity.toIncompleteUi(): IncompletePhotoObservationUi? {
        val id = inatObservationId ?: return null
        val url = inatObservationUrl ?: return null
        return IncompletePhotoObservationUi(
            observationId = id,
            scientificName = taxonScientificName ?: "Photo observation",
            url = url,
        )
    }
}
