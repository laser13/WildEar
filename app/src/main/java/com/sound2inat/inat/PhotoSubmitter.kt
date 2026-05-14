package com.sound2inat.inat

import com.sound2inat.storage.PhotoDraftRepository
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

    open suspend fun submit(token: String, draftId: String): PhotoSubmitResult = withContext(ioDispatcher) {
        if (token.isBlank()) return@withContext PhotoSubmitResult.Failure("No iNaturalist token in Settings")
        val draftWithImages = repo.observeWithImages(draftId).first()
            ?: return@withContext PhotoSubmitResult.Failure("Photo draft not found")
        val draft = draftWithImages.draft
        val images = draftWithImages.images.sortedBy { it.sortOrder }
        if (images.isEmpty()) return@withContext PhotoSubmitResult.Failure("No photos to upload")

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
            val message = e.message ?: "Failed to create iNaturalist observation"
            repo.setUploadError(draftId, message)
            return@withContext PhotoSubmitResult.Failure(message)
        }

        if (created.uuid.isBlank()) {
            val message = "iNaturalist did not return an observation UUID"
            repo.setUploadError(draftId, message)
            return@withContext PhotoSubmitResult.Failure(message)
        }

        val failures = mutableListOf<String>()
        var uploadedCount = 0
        images.forEach { image ->
            val file = File(image.photoPath)
            runCatching {
                client.uploadObservationPhoto(token, created.uuid, file)
            }.onSuccess {
                uploadedCount++
            }.onFailure { e ->
                failures += "${file.name}: ${e.message ?: "upload failed"}"
            }
        }

        val warnings = mutableListOf<String>()
        runCatching {
            client.updateObservationTags(token, created.uuid, APP_TAG)
        }.onFailure { e ->
            warnings += "Tag update failed: ${e.message ?: "unknown error"}"
        }

        if (uploadedCount == 0) {
            runCatching { client.deleteObservation(token, created.id) }
            val message = "All photo uploads failed: ${failures.joinToString(" | ")}"
            repo.setUploadError(draftId, message)
            return@withContext PhotoSubmitResult.Failure(message)
        }

        repo.markUploaded(draftId, created.id, created.uuid, created.url)
        if (failures.isNotEmpty()) {
            repo.setUploadError(draftId, failures.joinToString(" | "))
        }
        PhotoSubmitResult.Ok(created.url, failures + warnings)
    }

    private companion object {
        private const val APP_TAG = "WildEar"
    }
}
