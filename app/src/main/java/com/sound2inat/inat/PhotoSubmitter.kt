package com.sound2inat.inat

import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoUploadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject

sealed interface PhotoSubmitResult {
    data class Ok(val observationUrl: String, val warnings: List<String> = emptyList()) : PhotoSubmitResult
    data class Failure(val message: String) : PhotoSubmitResult
}

open class PhotoSubmitter @Inject constructor(
    private val client: INaturalistClient,
    private val repo: PhotoDraftRepository,
) {
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    constructor(
        client: INaturalistClient,
        repo: PhotoDraftRepository,
        ioDispatcher: CoroutineDispatcher,
    ) : this(client, repo) {
        this.ioDispatcher = ioDispatcher
    }

    @Suppress("ReturnCount", "LongMethod", "TooGenericExceptionCaught")
    open suspend fun submit(
        token: String,
        draftId: String,
        onProgress: (SubmissionProgress) -> Unit = {},
    ): PhotoSubmitResult = withContext(ioDispatcher) {
        if (token.isBlank()) return@withContext PhotoSubmitResult.Failure("No iNaturalist token in Settings")
        val draftWithImages = repo.observeWithImages(draftId).first()
            ?: return@withContext PhotoSubmitResult.Failure("Photo draft not found")
        val draft = draftWithImages.draft
        val images = draftWithImages.images.sortedBy { it.sortOrder }
        if (images.isEmpty()) return@withContext PhotoSubmitResult.Failure("No photos to upload")

        val displayName = draft.taxonScientificName ?: "Photo observation"
        fun emit(step: SubmissionProgress.Step) = onProgress(
            SubmissionProgress.Species(
                speciesIndex = 1,
                totalSpecies = 1,
                taxonScientificName = displayName,
                step = step,
            ),
        )

        if (draft.uploadStatus == PhotoUploadStatus.INCOMPLETE) {
            emit(SubmissionProgress.Step.DoneFailed)
            return@withContext PhotoSubmitResult.Failure(
                "This observation has an incomplete upload on iNaturalist. " +
                    "Recreate it from the banner before submitting again.",
            )
        }

        emit(SubmissionProgress.Step.CreatingObservation)
        val created = runCatching {
            client.createObservation(
                token,
                ObservationBody(
                    observedAtIso = Instant.ofEpochMilli(draft.observedAtUtcMs).toString(),
                    latitude = draft.latitude,
                    longitude = draft.longitude,
                    positionalAccuracy = draft.locationAccuracyMeters,
                    taxonId = draft.taxonInatId,
                    description = draft.description,
                    licenseCode = "cc-by-nc",
                ),
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            val message = e.message ?: "Failed to create iNaturalist observation"
            repo.setUploadError(draftId, message)
            emit(SubmissionProgress.Step.DoneFailed)
            return@withContext PhotoSubmitResult.Failure(message)
        }

        if (created.uuid.isBlank()) {
            val message = "iNaturalist did not return an observation UUID"
            repo.setUploadError(draftId, message)
            emit(SubmissionProgress.Step.DoneFailed)
            return@withContext PhotoSubmitResult.Failure(message)
        }

        // First photo is the recovery anchor — its failure rolls back the
        // just-created iNat observation since nothing has been persisted yet.
        emit(SubmissionProgress.Step.UploadingPrimaryPhoto)
        val firstImage = images.first()
        runCatching {
            client.uploadObservationPhoto(
                token = token,
                observationId = created.id,
                photoFile = File(firstImage.photoPath),
                mimeType = firstImage.mimeType.takeIf { it.isNotBlank() } ?: "image/jpeg",
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            runCatching { client.deleteObservation(token, created.id) }
            val message = "First photo upload failed: ${e.message ?: "unknown error"}"
            repo.setUploadError(draftId, message)
            emit(SubmissionProgress.Step.DoneFailed)
            return@withContext PhotoSubmitResult.Failure(message)
        }

        // From this point on the row is recoverable via the banner.
        emit(SubmissionProgress.Step.Persisting)
        repo.markIncompleteUpload(draftId, created.id, created.uuid, created.url)

        val failures = mutableListOf<String>()
        images.drop(1).forEach { image ->
            val file = File(image.photoPath)
            emit(SubmissionProgress.Step.UploadingExtraPhoto)
            runCatchingNonCancellation {
                client.uploadObservationPhoto(
                    token = token,
                    observationId = created.id,
                    photoFile = file,
                    mimeType = image.mimeType.takeIf { it.isNotBlank() } ?: "image/jpeg",
                )
            }.onFailure { e ->
                failures += "${file.name}: ${e.message ?: "upload failed"}"
            }
        }

        val warnings = mutableListOf<String>()
        emit(SubmissionProgress.Step.ApplyingTag)
        runCatchingNonCancellation {
            client.updateObservationTags(token, created.uuid, APP_TAG)
        }.onFailure { e ->
            warnings += "Tag update failed: ${e.message ?: "unknown error"}"
        }

        repo.markPhotoUploadComplete(draftId)
        if (failures.isNotEmpty()) {
            repo.setUploadError(draftId, failures.joinToString(" | "))
        }
        emit(SubmissionProgress.Step.DoneOk)
        PhotoSubmitResult.Ok(created.url, failures + warnings)
    }

    private companion object {
        private const val APP_TAG = "WildEar"
    }
}
