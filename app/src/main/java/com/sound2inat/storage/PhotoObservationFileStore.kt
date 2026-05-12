package com.sound2inat.storage

import java.io.File

class PhotoObservationFileStore(private val rootDir: File) {
    init {
        rootDir.mkdirs()
    }

    fun newPhotoFile(photoDraftId: String, photoId: String): File {
        val dir = File(rootDir, photoDraftId).apply { mkdirs() }
        return File(dir, "$photoId.jpg")
    }

    fun deleteDraftFiles(photoDraftId: String) {
        File(rootDir, photoDraftId).deleteRecursively()
    }
}
