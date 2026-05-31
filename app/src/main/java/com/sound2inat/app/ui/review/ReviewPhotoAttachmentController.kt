package com.sound2inat.app.ui.review

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.sound2inat.app.ui.FILE_PROVIDER_AUTHORITY
import com.sound2inat.storage.DraftPhotoDao
import com.sound2inat.storage.DraftPhotoEntity
import com.sound2inat.storage.PhotoFileStore
import java.io.File

/**
 * Photo capture/attach concern extracted from [ReviewViewModel]. Wraps
 * [PhotoFileStore] (file creation + storage-location single source of truth),
 * [DraftPhotoDao] (persistence) and Android [FileProvider] (content URIs).
 *
 * [photoStore]/[photosDao] are null in unit tests that do not exercise photos,
 * mirroring the ViewModel's optional seams. The suspend persistence methods are
 * launched by the ViewModel on [kotlinx.coroutines.Dispatchers.IO].
 */
class ReviewPhotoAttachmentController(
    private val photoStore: PhotoFileStore?,
    private val photosDao: DraftPhotoDao?,
) {

    /**
     * Creates a new photo file and returns a content URI suitable for
     * [androidx.activity.result.contract.ActivityResultContracts.TakePicture].
     * Requires [photoStore] to be set.
     */
    fun preparePhotoCapture(context: Context, draftId: String, photoId: String): Uri {
        checkNotNull(photoStore) { "Camera not available" }
        val file = photoStore.newPhotoFile(draftId, photoId)
        return FileProvider.getUriForFile(
            context,
            FILE_PROVIDER_AUTHORITY,
            file,
        )
    }

    /**
     * Creates a new photo file and returns a content URI plus the absolute file
     * path as a [Pair]. [PhotoFileStore] is the single source of truth for the
     * storage location (including the internal-storage fallback).
     */
    fun preparePhotoCaptureWithPath(
        context: Context,
        draftId: String,
        photoId: String,
    ): Pair<Uri, String> {
        checkNotNull(photoStore) { "Camera not available" }
        val file = photoStore.newPhotoFile(draftId, photoId)
        val uri = FileProvider.getUriForFile(
            context,
            FILE_PROVIDER_AUTHORITY,
            file,
        )
        return uri to file.absolutePath
    }

    /** Persists a newly captured photo entity to the database. */
    suspend fun onPhotoTaken(draftId: String, photoId: String, photoPath: String) {
        photosDao?.insert(
            DraftPhotoEntity(
                id = photoId,
                draftId = draftId,
                photoPath = photoPath,
                takenAtMs = System.currentTimeMillis(),
            )
        )
    }

    /** Removes a photo from the database and deletes the file from disk. */
    suspend fun onPhotoDeleted(photoId: String, photoPath: String) {
        photosDao?.deleteById(photoId)
        File(photoPath).delete()
    }
}
