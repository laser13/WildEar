package com.sound2inat.storage

import java.io.File

class WavFileStore(private val filesDir: File) {
    private val recordings: File = File(filesDir, "recordings").apply { mkdirs() }
    private val spectrograms: File = File(filesDir, "spectrograms").apply { mkdirs() }

    fun newRecordingFile(id: String): File = File(recordings, "$id.wav")
    fun spectrogramFile(id: String): File = File(spectrograms, "$id.png")

    fun deleteAllFor(id: String): Boolean {
        val wav = File(recordings, "$id.wav").let { if (it.exists()) it.delete() else true }
        val spec = File(spectrograms, "$id.png").let { if (it.exists()) it.delete() else true }
        return wav && spec
    }
}
