package com.sound2inat.app.ui.review

import com.sound2inat.inat.WavTrimmer
import java.io.File

/**
 * Pure export/share logic extracted from [ReviewViewModel]. Operates on a
 * [ReviewUiState] snapshot plus callbacks so the ViewModel keeps sole
 * ownership of `_state`. Holds no mutable state of its own.
 *
 * The four entry points ([shareFullRecording], [saveFullRecording],
 * [shareSpeciesClip], [saveSpeciesClip]) share the [runExport] template that
 * replaces the four duplicated `setExportingAction → try/catch → emitEffect →
 * clear` blocks that previously lived inline in the ViewModel.
 */
class ReviewExportUseCase(
    private val exportClipsDir: File,
    private val draftId: String,
) {

    /**
     * Shared template for the four export actions. The ViewModel supplies the
     * state-mutation callbacks; this class supplies the try/catch/finally
     * skeleton and the file work in [block]. [block] runs on the caller's
     * (IO) coroutine context.
     *
     * The two SAVE call sites pass [genericErrorMessage] = "Could not save audio"
     * to preserve the original's two different error messages.
     */
    suspend fun runExport(
        action: ExportingAction,
        genericErrorMessage: String = "Could not share audio",
        setExportingAction: (ExportingAction) -> Unit,
        emitEffect: (ReviewExportEffect) -> Unit,
        clearExportingAction: () -> Unit,
        block: suspend () -> ReviewExportEffect,
    ) {
        setExportingAction(action)
        try {
            emitEffect(block())
        } catch (_: UnsupportedOperationException) {
            emitEffect(
                ReviewExportEffect.ShowSnackbar(
                    "Saving to Downloads is not supported on this Android version"
                )
            )
        } catch (_: IllegalArgumentException) {
            emitEffect(ReviewExportEffect.ShowSnackbar("Audio file is missing"))
        } catch (_: Exception) {
            emitEffect(ReviewExportEffect.ShowSnackbar(genericErrorMessage))
        } finally {
            clearExportingAction()
        }
    }

    /** Validates the full-recording file and returns the share effect. */
    fun shareFullRecording(snapshot: ReviewUiState): ReviewExportEffect {
        val path = snapshot.audioPath
        val file = if (path != null) File(path) else null
        require(file != null && file.exists() && file.isFile && file.length() > 0L)
        return ReviewExportEffect.ShareAudioFile(file, buildFullRecordingShareText(snapshot))
    }

    /** Validates the full-recording file, saves it via [save], returns a snackbar. */
    suspend fun saveFullRecording(
        snapshot: ReviewUiState,
        save: suspend (file: File, displayName: String) -> Unit,
    ): ReviewExportEffect {
        val path = snapshot.audioPath
        val file = if (path != null) File(path) else null
        require(file != null && file.exists() && file.isFile && file.length() > 0L)
        save(file, buildDisplayName("original", snapshot.recordedAtUtcMs))
        return ReviewExportEffect.ShowSnackbar("Audio saved to Downloads")
    }

    /** Trims the species clip and returns the share effect. */
    fun shareSpeciesClip(snapshot: ReviewUiState, row: SpeciesRow): ReviewExportEffect {
        val clip = prepareSpeciesClip(snapshot, row)
        return ReviewExportEffect.ShareAudioFile(clip, buildClipShareText(snapshot, row))
    }

    /** Trims the species clip, saves it via [save], returns a snackbar. */
    suspend fun saveSpeciesClip(
        snapshot: ReviewUiState,
        row: SpeciesRow,
        save: suspend (file: File, displayName: String) -> Unit,
    ): ReviewExportEffect {
        val clip = prepareSpeciesClip(snapshot, row)
        val safe = row.taxonScientificName.replace("[^A-Za-z0-9]+".toRegex(), "_")
        save(clip, buildDisplayName("original_clip_$safe", snapshot.recordedAtUtcMs))
        return ReviewExportEffect.ShowSnackbar("Audio saved to Downloads")
    }

    fun prepareSpeciesClip(snapshot: ReviewUiState, row: SpeciesRow): File {
        val srcPath = requireNotNull(snapshot.audioPath) { "No audio path available" }
        val srcFile = File(srcPath)
        require(srcFile.exists() && srcFile.isFile && srcFile.length() > 0L) {
            "Source audio missing or empty"
        }
        val durationMs = snapshot.durationMs
        require(durationMs > 0L) { "Recording duration not yet loaded" }
        val startMs = maxOf(0L, row.firstSeenMs - CLIP_PADDING_MS)
        val endMs = minOf(durationMs, row.lastSeenMs + CLIP_PADDING_MS)
        require(endMs > startMs) { "Clip range is empty: $startMs..$endMs" }
        val safe = row.taxonScientificName.replace("[^A-Za-z0-9]+".toRegex(), "_")
        val clipFile = File(exportClipsDir, "${draftId}__${safe}__${row.firstSeenMs}_${row.lastSeenMs}.wav")
        if (clipFile.exists() && clipFile.length() > 0L) return clipFile
        exportClipsDir.mkdirs()
        val tmp = File(exportClipsDir, "${clipFile.name}.tmp")
        try {
            WavTrimmer.trimMono16(srcPath, tmp, startMs, endMs)
            if (!tmp.renameTo(clipFile)) {
                // renameTo can fail across filesystems; fall back to copy+delete
                tmp.copyTo(clipFile, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return clipFile
    }

    fun buildDisplayName(label: String, recordedAtUtcMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return "wildear_${label}_${sdf.format(java.util.Date(recordedAtUtcMs))}.wav"
    }

    private fun buildFullRecordingShareText(snapshot: ReviewUiState): String {
        val sb = StringBuilder("WildEar · Original recording — ")
        sb.append(formatShareDateTime(snapshot.recordedAtUtcMs))
        formatShareCoords(snapshot.latitude, snapshot.longitude)?.let {
            sb.append("\nLocation: ").append(it)
        }
        val sorted = snapshot.species.sortedByDescending { it.maxConfidence }
        if (sorted.isNotEmpty()) {
            sb.append("\n\nDetected:")
            sorted.forEach { row -> sb.append("\n• ").append(formatSpeciesLine(row)) }
        }
        return sb.toString()
    }

    private fun buildClipShareText(snapshot: ReviewUiState, row: SpeciesRow): String {
        val sb = StringBuilder("WildEar · Original clip — ")
        sb.append(formatSpeciesLine(row))
        sb.append("\n").append(formatShareDateTime(snapshot.recordedAtUtcMs))
        formatShareCoords(snapshot.latitude, snapshot.longitude)?.let {
            sb.append("\nLocation: ").append(it)
        }
        return sb.toString()
    }

    private fun formatSpeciesLine(row: SpeciesRow): String {
        val name = if (row.taxonCommonName != null) {
            "${row.taxonCommonName} (${row.taxonScientificName})"
        } else {
            row.taxonScientificName
        }
        return "$name — ${"%.0f".format(row.maxConfidence * 100)}%"
    }

    private fun formatShareDateTime(epochMs: Long): String {
        val sdf = java.text.SimpleDateFormat("d MMM yyyy, HH:mm 'UTC'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return sdf.format(java.util.Date(epochMs))
    }

    private fun formatShareCoords(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        val latStr = "${"%.4f".format(Math.abs(lat))}°${if (lat >= 0) "N" else "S"}"
        val lonStr = "${"%.4f".format(Math.abs(lon))}°${if (lon >= 0) "E" else "W"}"
        return "$latStr, $lonStr"
    }

    private companion object {
        private const val CLIP_PADDING_MS = 1_000L
    }
}
