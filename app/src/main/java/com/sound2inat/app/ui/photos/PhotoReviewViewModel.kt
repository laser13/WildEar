package com.sound2inat.app.ui.photos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.inat.INatAuthRepository
import com.sound2inat.inat.INaturalistClient
import com.sound2inat.inat.PhotoSubmitResult
import com.sound2inat.inat.PhotoSubmitter
import com.sound2inat.storage.PhotoDraftRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhotoReviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val repo: PhotoDraftRepository,
    private val auth: INatAuthRepository,
    private val client: INaturalistClient,
    private val submitter: PhotoSubmitter,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        repo: PhotoDraftRepository,
        auth: INatAuthRepository,
        client: INaturalistClient,
        submitter: PhotoSubmitter,
    ) : this(
        savedStateHandle = savedStateHandle,
        repo = repo,
        auth = auth,
        client = client,
        submitter = submitter,
        externalScope = null,
    )

    private val draftId = checkNotNull(savedStateHandle.get<String>("photoDraftId")) {
        "photoDraftId is required"
    }
    private val scope = externalScope ?: viewModelScope
    private val _state = MutableStateFlow(PhotoReviewUiState(draftId = draftId))
    val state: StateFlow<PhotoReviewUiState> = _state

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
                                inatObservationId = null,
                                inatObservationUrl = null,
                            )
                        }
                    } else {
                        val draft = withImages.draft
                        _state.update { current ->
                            current.copy(
                                draftId = draftId,
                                isLoading = false,
                                images = withImages.images,
                                inatObservationId = draft.inatObservationId,
                                inatObservationUrl = draft.inatObservationUrl,
                                taxonScientificName = draft.taxonScientificName,
                                taxonCommonName = draft.taxonCommonName,
                                taxonInatId = draft.taxonInatId,
                                description = draft.description,
                                submitError = draft.inatLastError ?: current.submitError,
                                uploadedUrl = draft.inatObservationUrl ?: current.uploadedUrl,
                            )
                        }
                    }
                }
        }
    }

    suspend fun deleteImage(imageId: String) {
        repo.deleteImage(imageId)
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
            _state.update { it.copy(vision = PhotoVisionPanelUiState(error = "Upload to iNaturalist first")) }
            return
        }
        if (_state.value.vision.isLoading) return
        _state.update { it.copy(vision = it.vision.copy(isLoading = true, error = null, message = null)) }
        scope.launch {
            val token = auth.getValidToken().orEmpty()
            if (token.isBlank()) {
                _state.update { it.copy(vision = it.vision.copy(isLoading = false, error = "No iNaturalist token in Settings")) }
                return@launch
            }

            runCatching {
                val response = client.scoreObservationVision(token, observationId)
                val ancestorIds = PhotoVisionPlanner.collectAncestorIds(response)
                val taxonInfo = client.getTaxa(ancestorIds)
                PhotoVisionPlanner.buildLadder(response, taxonInfo)
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
            _state.update { it.copy(vision = it.vision.copy(error = "Upload to iNaturalist first")) }
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
                client.addIdentification(
                    token = token,
                    observationId = observationId,
                    taxonId = suggestion.taxonId,
                    body = "WildEar CV $label",
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
            if (shouldApplyAnnotations(suggestion)) {
                runCatching { applyAnnotationSet(token, observationId, suggestion) }
                    .onFailure { error ->
                        warnings += error.message ?: "Annotation update failed"
                    }
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
                            append("Applied $label: ${suggestion.scientificName}")
                            if (warnings.isNotEmpty()) {
                                append(" | ")
                                append(warnings.joinToString(" | "))
                            }
                        }.takeIf { text -> text.isNotBlank() },
                        ladder = it.vision.ladder,
                    ),
                )
            }
        }
    }

    fun submit() {
        if (_state.value.isSubmitting) return
        _state.update { it.copy(isSubmitting = true, submitError = null) }
        scope.launch {
            val token = auth.getValidToken().orEmpty()
            when (val result = submitter.submit(token, draftId)) {
                is PhotoSubmitResult.Ok -> {
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            uploadedUrl = result.observationUrl,
                            submitError = result.warnings.joinToString(" | ").takeIf { text -> text.isNotBlank() },
                        )
                    }
                }
                is PhotoSubmitResult.Failure -> {
                    _state.update { it.copy(isSubmitting = false, submitError = result.message) }
                }
            }
        }
    }

    private suspend fun applyAnnotationSet(
        token: String,
        observationId: Long,
        suggestion: PhotoVisionSuggestion,
    ) {
        val iconic = suggestion.iconicTaxonName.orEmpty()
        if (iconic !in ANIMAL_ICONIC_TAXA) return

        client.createAnnotation(
            token = token,
            observationUuid = observationId.toString(),
            controlledAttributeId = 17,
            controlledValueId = 18,
        )
        client.createAnnotation(
            token = token,
            observationUuid = observationId.toString(),
            controlledAttributeId = 22,
            controlledValueId = 24,
        )
        if (iconic == "Insecta") {
            client.createAnnotation(
                token = token,
                observationUuid = observationId.toString(),
                controlledAttributeId = 1,
                controlledValueId = 2,
            )
        }
    }

    private fun shouldApplyAnnotations(suggestion: PhotoVisionSuggestion): Boolean =
        suggestion.iconicTaxonName in ANIMAL_ICONIC_TAXA

    private companion object {
        val ANIMAL_ICONIC_TAXA = setOf(
            "Animalia",
            "Aves",
            "Mammalia",
            "Insecta",
            "Arachnida",
            "Reptilia",
            "Amphibia",
            "Mollusca",
            "Actinopterygii",
        )
    }
}
