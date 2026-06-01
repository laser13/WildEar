package com.sound2inat.storage

import java.io.File

class PhotoFileStore(private val photosDir: File) {
    init { photosDir.mkdirs() }

    fun newPhotoFile(draftId: String, photoId: String): File {
        val dir = File(photosDir, draftId).apply { mkdirs() }
        return File(dir, "$photoId.jpg")
    }

    fun deletePhotosFor(draftId: String) {
        File(photosDir, draftId).deleteRecursively()
    }
}
