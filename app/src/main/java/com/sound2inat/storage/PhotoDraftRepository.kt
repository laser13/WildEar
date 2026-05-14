package com.sound2inat.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PhotoDraftRepository(
    private val draftDao: PhotoDraftDao,
    private val imageDao: PhotoDraftImageDao,
    private val fileStore: PhotoObservationFileStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val runInTransaction: (block: () -> Unit) -> Unit = { it() },
) {
    fun observeSummaries(): Flow<List<PhotoDraftSummary>> =
        draftDao.observeAll().map { drafts ->
            // TODO: Replace the per-draft image lookup with a single SQL
            // summary query before the photo album list grows large.
            drafts.map { draft ->
                val images = imageDao.listForDraft(draft.id)
                PhotoDraftSummary(
                    id = draft.id,
                    observedAtUtcMs = draft.observedAtUtcMs,
                    updatedAtUtcMs = draft.updatedAtUtcMs,
                    status = draft.status,
                    taxonScientificName = draft.taxonScientificName,
                    taxonCommonName = draft.taxonCommonName,
                    latitude = draft.latitude,
                    longitude = draft.longitude,
                    locationAccuracyMeters = draft.locationAccuracyMeters,
                    firstPhotoPath = images.firstOrNull()?.photoPath,
                    photoCount = images.size,
                    inatObservationUrl = draft.inatObservationUrl,
                    inatLastError = draft.inatLastError,
                )
            }
        }.flowOn(ioDispatcher)

    fun observeWithImages(id: String): Flow<PhotoDraftWithImages?> =
        draftDao.observeWithImages(id).flowOn(ioDispatcher)

    suspend fun getImageById(imageId: String): PhotoDraftImageEntity? = withContext(ioDispatcher) {
        imageDao.getById(imageId)
    }

    suspend fun createDraft(
        observedAtUtcMs: Long,
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Float?,
    ): String = withContext(ioDispatcher) {
        val id = idFactory()
        val now = nowMs()
        draftDao.insert(
                PhotoDraftEntity(
                id = id,
                createdAtUtcMs = now,
                updatedAtUtcMs = now,
                observedAtUtcMs = observedAtUtcMs,
                latitude = latitude,
                longitude = longitude,
                locationAccuracyMeters = accuracyMeters,
                status = PhotoDraftStatus.PENDING_REVIEW,
                taxonScientificName = null,
                taxonCommonName = null,
                taxonInatId = null,
                description = null,
                inatObservationId = null,
                inatObservationUuid = null,
                inatObservationUrl = null,
                inatLastError = null,
            ),
        )
        id
    }

    suspend fun addImage(
        draftId: String,
        photoId: String,
        imageFile: File,
        takenAtUtcMs: Long,
        width: Int?,
        height: Int?,
    ) = withContext(ioDispatcher) {
        val draft = draftDao.getById(draftId) ?: error("photo draft $draftId missing")
        val sortOrder = imageDao.listForDraft(draftId).size
        runInTransaction {
            imageDao.insert(
                PhotoDraftImageEntity(
                    id = photoId,
                    photoDraftId = draftId,
                    originalPhotoPath = imageFile.absolutePath,
                    photoPath = imageFile.absolutePath,
                    cropLeftPx = null,
                    cropTopPx = null,
                    cropSizePx = null,
                    takenAtUtcMs = takenAtUtcMs,
                    sortOrder = sortOrder,
                    width = width,
                    height = height,
                ),
            )
            draftDao.update(draft.copy(updatedAtUtcMs = nowMs()))
        }
    }

    suspend fun updateDetails(
        draftId: String,
        taxonScientificName: String?,
        taxonCommonName: String?,
        taxonInatId: Long?,
        description: String?,
    ) = withContext(ioDispatcher) {
        val draft = draftDao.getById(draftId) ?: error("photo draft $draftId missing")
        draftDao.update(
            draft.copy(
                updatedAtUtcMs = nowMs(),
                status = PhotoDraftStatus.REVIEWED,
                taxonScientificName = taxonScientificName,
                taxonCommonName = taxonCommonName,
                taxonInatId = taxonInatId,
                description = description,
            ),
        )
    }

    suspend fun deleteImage(imageId: String) = withContext(ioDispatcher) {
        val image = imageDao.getById(imageId) ?: return@withContext
        imageDao.deleteById(imageId)
        File(image.photoPath).delete()
        val original = image.originalPhotoPath
        if (original.isNotBlank() && original != image.photoPath) {
            File(original).delete()
        }
    }

    suspend fun updateImageCrop(
        imageId: String,
        originalPhotoPath: String,
        newPhotoPath: File,
        cropLeftPx: Int,
        cropTopPx: Int,
        cropSizePx: Int,
        width: Int?,
        height: Int?,
    ) = withContext(ioDispatcher) {
        val image = imageDao.getById(imageId) ?: error("photo image $imageId missing")
        val updated = image.copy(
            originalPhotoPath = originalPhotoPath,
            photoPath = newPhotoPath.absolutePath,
            cropLeftPx = cropLeftPx,
            cropTopPx = cropTopPx,
            cropSizePx = cropSizePx,
            width = width,
            height = height,
        )
        runInTransaction {
            imageDao.update(updated)
            draftDao.getById(image.photoDraftId)?.let { draft ->
                draftDao.update(draft.copy(updatedAtUtcMs = nowMs()))
            }
        }
        val previousPath = image.photoPath
        if (previousPath != newPhotoPath.absolutePath && previousPath != originalPhotoPath) {
            File(previousPath).delete()
        }
    }

    suspend fun deleteDraft(draftId: String) = withContext(ioDispatcher) {
        draftDao.deleteById(draftId)
        fileStore.deleteDraftFiles(draftId)
    }

    suspend fun markUploaded(
        draftId: String,
        observationId: Long,
        observationUuid: String,
        observationUrl: String,
    ) = withContext(ioDispatcher) {
        val draft = draftDao.getById(draftId) ?: error("photo draft $draftId missing")
        draftDao.update(
            draft.copy(
                updatedAtUtcMs = nowMs(),
                status = PhotoDraftStatus.UPLOADED,
                inatObservationId = observationId,
                inatObservationUuid = observationUuid,
                inatObservationUrl = observationUrl,
                inatLastError = null,
            ),
        )
    }

    suspend fun setUploadError(draftId: String, message: String) = withContext(ioDispatcher) {
        val draft = draftDao.getById(draftId) ?: error("photo draft $draftId missing")
        draftDao.update(draft.copy(updatedAtUtcMs = nowMs(), inatLastError = message))
    }
}
