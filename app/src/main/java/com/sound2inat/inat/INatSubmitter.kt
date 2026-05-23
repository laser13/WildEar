package com.sound2inat.inat

import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
import com.sound2inat.inference.SourceStats
import com.sound2inat.modelmanager.KnownModels
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.DraftWithDetections
import com.sound2inat.storage.InatObservationDao
import com.sound2inat.storage.InatObservationEntity
import com.sound2inat.storage.InatUploadStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pushes a draft to iNaturalist, materialising one observation per selected
 * species (this is iNat's data model — one observation = one organism).
 *
 * For each selected detection:
 *   1. Resolve scientific name → iNat taxon id.
 *   2. Plan upload clips via [ClipPlanner] (clustered by time gap, capped at
 *      5 clips × 60 s each) and write each clip + its PNG spectrogram into a
 *      per-species temp file set.
 *   3. POST /observations with the resolved taxon, GPS, and observed_on.
 *   4. POST /observation_sounds for each clip and POST /observation_photos
 *      for each matching spectrogram.
 *   5. PUT /observations/{uuid} on the v2 API to apply the stable app tag.
 *   6. Persist a row in `inat_observations`.
 *
 * After all observations are created:
 *   7. PUT /observations/{id} on each, writing a description that links to
 *      the sibling observations in the same recording.
 *
 * Failure model:
 *   * If a species fails to resolve, it's skipped (logged into `inatLastError`)
 *     but the rest of the submission proceeds.
 *   * If create-observation or upload-sound fails for a species, that species
 *     is dropped; siblings still get persisted.
 *   * The whole submission fails (and the draft stays REVIEWED) only if
 *     **zero** observations got created.
 *   * Cross-link PUT failures are non-fatal — the observations remain on iNat.
 */
class INatSubmitter(
    private val client: INaturalistClient,
    private val drafts: DraftDao,
    private val inatObservations: InatObservationDao,
    private val tmpRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    sealed interface Result {
        /** [primaryUrl] is the first observation created — surfaced in `drafts`. */
        data class Ok(val primaryUrl: String, val urls: List<String>) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Carries the persisted entity and the iNat-assigned UUID for a submitted observation.
     *
     * [uuid] is empty (`""`) when this represents a **reused** pre-existing row
     * (idempotency path — the original UUID was never persisted). UUID-dependent calls
     * (e.g. [INaturalistClient.createObservationFieldValue]) must skip such rows.
     */
    private data class SubmittedObs(val entity: InatObservationEntity, val uuid: String)

    @Suppress("ReturnCount", "LongMethod", "TooGenericExceptionCaught")
    suspend fun submit(
        token: String,
        draft: DraftWithDetections,
        habitatPhotos: List<File> = emptyList(),
        includeHabitatPhotoByTaxon: Map<String, Boolean> = emptyMap(),
        sourceAudioOverride: File? = null,
        onProgress: (SubmissionProgress) -> Unit = {},
    ): Result = withContext(ioDispatcher) {
        if (token.isBlank()) return@withContext Result.Failure("No iNaturalist token in Settings")
        val selected = draft.detections.filter { it.isSelectedByUser }
        if (selected.isEmpty()) return@withContext Result.Failure("No species selected")
        val srcAudio = sourceAudioOverride ?: File(draft.draft.audioPath)
        if (!srcAudio.exists()) return@withContext Result.Failure("Audio file missing on disk")

        val cropDir = File(tmpRoot, "inat_uploads").apply { mkdirs() }
        try {
            doSubmit(
                token,
                draft,
                srcAudio,
                cropDir,
                selected,
                habitatPhotos,
                includeHabitatPhotoByTaxon,
                onProgress,
            )
        } finally {
            runCatching { cropDir.deleteRecursively() }
        }
    }

    @Suppress("ReturnCount", "LongMethod", "TooGenericExceptionCaught")
    private suspend fun doSubmit(
        token: String,
        draft: DraftWithDetections,
        srcAudio: File,
        cropDir: File,
        selected: List<DetectionEntity>,
        habitatPhotos: List<File>,
        includeHabitatPhotoByTaxon: Map<String, Boolean>,
        onProgress: (SubmissionProgress) -> Unit,
    ): Result {
        val createdPairs = mutableListOf<Pair<SubmittedObs, DetectionEntity>>()
        val failures = mutableListOf<String>()

        selected.forEachIndexed { idx, det ->
            val outcome = runCatchingNonCancellation {
                submitOne(
                    token,
                    draft,
                    srcAudio,
                    cropDir,
                    det,
                    speciesIndex = idx + 1,
                    totalSpecies = selected.size,
                    habitatPhotos,
                    includeHabitatPhotoByTaxon,
                    onProgress,
                )
            }
            outcome.onSuccess { submitted ->
                if (submitted != null) {
                    android.util.Log.i(
                        LOG_TAG,
                        "Uploaded ${det.taxonScientificName} -> ${submitted.entity.observationUrl}",
                    )
                    createdPairs += submitted to det
                    onProgress(
                        SubmissionProgress.Species(
                            idx + 1,
                            selected.size,
                            det.taxonScientificName,
                            SubmissionProgress.Step.DoneOk,
                        ),
                    )
                } else {
                    android.util.Log.w(LOG_TAG, "No iNat match for ${det.taxonScientificName}")
                    failures += "${det.taxonScientificName}: no iNat match"
                    onProgress(
                        SubmissionProgress.Species(
                            idx + 1,
                            selected.size,
                            det.taxonScientificName,
                            SubmissionProgress.Step.DoneFailed,
                        ),
                    )
                }
            }
            outcome.onFailure { e ->
                android.util.Log.w(LOG_TAG, "Submit failed for ${det.taxonScientificName}", e)
                failures += "${det.taxonScientificName}: ${e.message}"
                onProgress(
                    SubmissionProgress.Species(
                        idx + 1,
                        selected.size,
                        det.taxonScientificName,
                        SubmissionProgress.Step.DoneFailed,
                    ),
                )
            }
        }

        if (createdPairs.isEmpty()) {
            val msg = "All species failed: ${failures.joinToString(" | ")}"
            drafts.update(
                draft.draft.copy(inatLastError = msg, updatedAtUtcMs = nowMs()),
            )
            return Result.Failure(msg)
        }

        onProgress(SubmissionProgress.CrossLinking)
        crossLink(token, draft, createdPairs)

        val totalSelected = selected.size
        val succeeded = createdPairs.size
        val anyFailure = failures.isNotEmpty()
        val nextStatus = if (succeeded == totalSelected && !anyFailure) {
            DraftStatus.UPLOADED
        } else {
            DraftStatus.REVIEWED
        }
        drafts.update(
            draft.draft.copy(
                status = nextStatus,
                inatLastError = if (failures.isEmpty()) null else failures.joinToString(" | "),
                updatedAtUtcMs = nowMs(),
            ),
        )

        val primary = createdPairs.first().first.entity.observationUrl
        val allCompleteUrls = inatObservations.listForDraft(draft.draft.id)
            .filter { it.uploadStatus == InatUploadStatus.COMPLETE }
            .map { it.observationUrl }
        return Result.Ok(primary, allCompleteUrls)
    }

    /**
     * Resolves, creates and uploads a single species' observation. Returns
     * a [SubmittedObs] wrapping the entity and the iNat UUID, or null if the
     * taxon name didn't match anything on iNat.
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod", "LongParameterList", "ReturnCount")
    private suspend fun submitOne(
        token: String,
        draft: DraftWithDetections,
        srcAudio: File,
        cropDir: File,
        det: DetectionEntity,
        speciesIndex: Int,
        totalSpecies: Int,
        habitatPhotos: List<File>,
        includeHabitatPhotoByTaxon: Map<String, Boolean>,
        onProgress: (SubmissionProgress) -> Unit,
    ): SubmittedObs? {
        val name = det.taxonScientificName
        fun emit(step: SubmissionProgress.Step) =
            onProgress(SubmissionProgress.Species(speciesIndex, totalSpecies, name, step))

        val existing = inatObservations.findForDraftAndSpecies(draft.draft.id, name)
        val isCompleteAndValid = existing != null &&
            existing.uploadStatus == InatUploadStatus.COMPLETE &&
            existing.observationUrl.isNotBlank() &&
            existing.observationId > 0L
        if (isCompleteAndValid) {
            android.util.Log.d(
                LOG_TAG,
                "Reusing COMPLETE iNat row ${existing!!.observationId} for $name",
            )
            emit(SubmissionProgress.Step.DoneOk)
            return SubmittedObs(existing, uuid = "")
        }
        if (existing != null && existing.uploadStatus == InatUploadStatus.INCOMPLETE) {
            android.util.Log.w(
                LOG_TAG,
                "Refusing to resubmit $name — INCOMPLETE row exists" +
                    " (observationId=${existing.observationId}). Use the banner's" +
                    " Delete-and-recreate action first.",
            )
            emit(SubmissionProgress.Step.DoneFailed)
            error("$name has an incomplete observation on iNaturalist — recreate it from the banner first")
        }
        if (existing != null) {
            // Malformed COMPLETE row (zero id / blank url) — legacy data that
            // pre-dates the INCOMPLETE column. Targeted delete only, so
            // sibling species' rows survive.
            android.util.Log.w(
                LOG_TAG,
                "Discarding malformed inat_observations row for $name" +
                    " (id=${existing.observationId}, url='${existing.observationUrl}') — will recreate",
            )
            inatObservations.deleteForDraftAndSpecies(draft.draft.id, name)
        }

        emit(SubmissionProgress.Step.ResolvingTaxon)
        val genusId = client.resolveGenus(name, token) ?: return null

        emit(SubmissionProgress.Step.CreatingObservation)
        val clips = materialiseClips(
            srcAudio = srcAudio,
            det = det,
            draftDurationMs = draft.draft.durationMs,
            cropDir = cropDir,
            paletteName = draft.draft.paletteName,
            contrastDb = draft.draft.spectrogramGainDb ?: 0f,
        )
        check(clips.isNotEmpty()) {
            "All WAV trims failed for $name — disk full or audio corrupted?"
        }
        val obsBody = ObservationBody(
            observedAtIso = formatIso(draft.draft.recordedAtUtcMs),
            latitude = draft.draft.latitude,
            longitude = draft.draft.longitude,
            positionalAccuracy = draft.draft.locationAccuracyMeters,
            taxonId = null,
            description = baseDescription(det),
            licenseCode = "cc-by-nc",
        )
        val created = withRetry { client.createObservation(token, obsBody) }
        check(created.uuid.isNotBlank()) {
            "iNat /observations did not return a uuid; cannot link sound (id=${created.id})"
        }

        emit(SubmissionProgress.Step.UploadingPrimaryAudio)
        val firstClip = clips.first()
        try {
            withRetry { client.uploadSound(token, created.uuid, firstClip.wav) }
        } catch (t: Throwable) {
            runCatching { client.deleteObservation(token, created.id) }
                .onFailure { android.util.Log.w(LOG_TAG, "Cleanup failed for ${created.id}", it) }
            throw t
        }

        // From this point on the row is recoverable.
        emit(SubmissionProgress.Step.Persisting)
        val row = InatObservationEntity(
            draftId = draft.draft.id,
            taxonScientificName = name,
            taxonInatId = genusId,
            observationId = created.id,
            observationUrl = created.url,
            createdAtUtcMs = nowMs(),
            uploadStatus = InatUploadStatus.INCOMPLETE,
        )
        val rowId = inatObservations.insert(row)

        emit(SubmissionProgress.Step.UploadingSpectrogram)
        firstClip.spectrogramPng?.let { png ->
            runCatchingNonCancellation { client.uploadObservationPhoto(token, created.id, png) }
                .onFailure {
                    android.util.Log.w(LOG_TAG, "Spectrogram photo upload failed clip 0", it)
                }
        }
        clips.drop(1).forEachIndexed { extraIdx, clip ->
            emit(SubmissionProgress.Step.UploadingExtraAudio)
            runCatchingNonCancellation { withRetry { client.uploadSound(token, created.uuid, clip.wav) } }
                .onFailure {
                    android.util.Log.w(LOG_TAG, "Extra sound upload failed clip ${extraIdx + 1}", it)
                }
            clip.spectrogramPng?.let { png ->
                emit(SubmissionProgress.Step.UploadingSpectrogram)
                runCatchingNonCancellation { client.uploadObservationPhoto(token, created.id, png) }
                    .onFailure {
                        android.util.Log.w(LOG_TAG, "Spectrogram photo upload failed clip ${extraIdx + 1}", it)
                    }
            }
        }

        emit(SubmissionProgress.Step.ApplyingTag)
        runCatchingNonCancellation { client.updateObservationTags(token, created.uuid, APP_TAG) }
            .onFailure { android.util.Log.w(LOG_TAG, "Tag update failed for ${created.id}", it) }

        emit(SubmissionProgress.Step.ApplyingAnnotations)
        for ((attr, value) in DEFAULT_ANNOTATIONS) {
            runCatchingNonCancellation { client.createAnnotation(token, created.uuid, attr, value) }
                .onFailure {
                    android.util.Log.w(LOG_TAG, "Annotation attr=$attr on ${created.id} failed", it)
                }
        }

        emit(SubmissionProgress.Step.AddingIdentification)
        runCatchingNonCancellation {
            client.addIdentification(token, created.id, genusId, identificationComment(det))
        }.onFailure {
            android.util.Log.w(LOG_TAG, "addIdentification on ${created.id} failed", it)
        }

        if (includeHabitatPhotoByTaxon[name] == true && habitatPhotos.isNotEmpty()) {
            emit(SubmissionProgress.Step.UploadingHabitatPhotos)
            for (photo in habitatPhotos.filter { it.exists() }) {
                runCatchingNonCancellation { client.uploadObservationPhoto(token, created.id, photo) }
                    .onFailure {
                        android.util.Log.w(LOG_TAG, "Photo upload failed for ${created.id}", it)
                    }
            }
        }

        inatObservations.markComplete(rowId)
        return SubmittedObs(
            entity = row.copy(id = rowId, uploadStatus = InatUploadStatus.COMPLETE),
            uuid = created.uuid,
        )
    }

    private data class ClipArtifacts(val wav: File, val spectrogramPng: File?)

    /**
     * Builds the WAV crops + per-clip spectrograms for a single species'
     * detections according to [ClipPlanner]. Individual clip failures are
     * logged and skipped; the caller is expected to handle an empty list.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun materialiseClips(
        srcAudio: File,
        det: DetectionEntity,
        draftDurationMs: Long,
        cropDir: File,
        paletteName: String?,
        contrastDb: Float,
    ): List<ClipArtifacts> = withContext(ioDispatcher) {
        val ranges = com.sound2inat.inference.FragmentRanges.decode(det.fragmentRanges)
        val clips = ClipPlanner.plan(
            fragmentRanges = ranges,
            firstSeenMs = det.firstSeenMs,
            lastSeenMs = det.lastSeenMs,
            recordingDurationMs = draftDurationMs,
        )
        // Mirror the Review UI's palette/contrast fallback so the uploaded PNG
        // matches what the user saw in the Review screen ([ReviewViewModel]
        // uses `palette ?: INK` and `gainDb ?: 0f` for the live preview).
        val palette = paletteName
            ?.let { runCatching { SpectrogramPalette.valueOf(it) }.getOrNull() }
            ?: SpectrogramPalette.INK
        clips.mapIndexedNotNull { idx, range ->
            val wavOut = File(cropDir, clipFileName(det, idx, ext = "wav"))
            runCatching { WavTrimmer.trimMono16(srcAudio.absolutePath, wavOut, range.startMs, range.endMs) }
                .getOrElse {
                    android.util.Log.w(LOG_TAG, "trim failed for ${det.taxonScientificName} clip $idx", it)
                    return@mapIndexedNotNull null
                }
            // The spectrogram is drawn only for the densest sub-window of the
            // clip (capped at MAX_SPECTROGRAM_S) so iNat thumbnails stay
            // readable. Audio upload still uses the full WAV.
            val peakAbs = ClipPlanner.peakSpectrogramWindow(range, ranges)
            val peakOffsetMs: LongRange? = if (peakAbs == range) {
                null
            } else {
                (peakAbs.startMs - range.startMs)..(peakAbs.endMs - range.startMs)
            }
            val pngOut = File(cropDir, clipFileName(det, idx, ext = "png"))
            val png = runCatching {
                renderClipSpectrogramPng(
                    clipWav = wavOut,
                    destination = pngOut,
                    peakOffsetMs = peakOffsetMs,
                    palette = palette,
                    contrastDb = contrastDb,
                )
            }
                .getOrElse {
                    android.util.Log.w(
                        LOG_TAG,
                        "spectrogram render failed for ${det.taxonScientificName} clip $idx",
                        it,
                    )
                    null
                }
            ClipArtifacts(wav = wavOut, spectrogramPng = png)
        }
    }

    private fun clipFileName(det: DetectionEntity, index: Int, ext: String): String {
        val safe = det.taxonScientificName.replace("[^A-Za-z0-9]+".toRegex(), "_")
        return "${det.draftId}__${safe}__$index.$ext"
    }

    private fun baseDescription(det: DetectionEntity): String {
        val header = "Recorded with WildEar."
        val species = "Species: ${taxonLabel(det)}."
        return "$header\n$species\n${detectionLines(det)}"
    }

    private fun detectionLines(det: DetectionEntity): String {
        val stats = SourceStats.decode(det.sources)
        return if (stats.isEmpty()) {
            "Detected ${det.detectedWindows} window(s)" +
                " between ${det.firstSeenMs / MS}–${det.lastSeenMs / MS} s," +
                " max confidence ${"%.0f".format(det.maxConfidence * PCT)}%."
        } else {
            stats.entries.sortedBy { it.key }.joinToString("\n") { (src, stat) ->
                "${sourceDisplayName(src)} detected ${stat.windows} window(s)" +
                    " between ${stat.firstSeenMs / MS}–${stat.lastSeenMs / MS} s," +
                    " max confidence ${"%.0f".format(stat.maxConf * PCT)}%."
            }
        }
    }

    private fun taxonLabel(det: DetectionEntity): String {
        val common = det.taxonCommonName?.takeIf { it.isNotBlank() }
        return if (common != null) "${det.taxonScientificName} ($common)" else det.taxonScientificName
    }

    private fun sourceDisplayName(id: String): String =
        KnownModels.firstOrNull { it.id == id }?.displayName ?: id

    private fun identificationComment(det: DetectionEntity): String {
        val stats = SourceStats.decodeConfidenceOnly(det.sources)
        val taxon = taxonLabel(det)
        val lines = if (stats.isEmpty()) {
            "${"%.0f".format(det.maxConfidence * PCT)}% confidence"
        } else {
            stats.entries.sortedBy { it.key }.joinToString("\n") { (src, conf) ->
                "${sourceDisplayName(src)}: ${"%.0f".format(conf * PCT)}%"
            }
        }
        return "$taxon\n$lines"
    }

    private suspend fun crossLink(
        token: String,
        draft: DraftWithDetections,
        createdPairs: List<Pair<SubmittedObs, DetectionEntity>>,
    ) {
        // Union: rows persisted in prior runs (kept around for back-references)
        // + the freshly-submitted pairs from this run. Prior rows are wrapped
        // in a SubmittedObs with an empty uuid — uuid-dependent calls
        // (createObservationFieldValue) skip them.
        val priorRows = inatObservations.listForDraft(draft.draft.id)
            .filter { it.uploadStatus == InatUploadStatus.COMPLETE }
        val detectionByName = draft.detections.associateBy { it.taxonScientificName }
        val newIds = createdPairs.mapTo(mutableSetOf()) { it.first.entity.observationId }
        val priorPairs = priorRows
            .filter { it.observationId !in newIds }
            .mapNotNull { row ->
                val det = detectionByName[row.taxonScientificName] ?: return@mapNotNull null
                SubmittedObs(entity = row, uuid = "") to det
            }
        val allPairs = priorPairs + createdPairs
        if (allPairs.size <= 1) return

        for ((submitted, det) in allPairs) {
            val others = allPairs.filter { it.first.entity.observationId != submitted.entity.observationId }
            val siblings = others.joinToString("\n") { (sib, _) ->
                " - ${sib.entity.taxonScientificName} → ${sib.entity.observationUrl}"
            }
            val description = baseDescription(det) +
                "\n\nSibling observations from the same recording:\n$siblings"
            runCatchingNonCancellation {
                client.updateObservationDescription(token, submitted.entity.observationId, description)
            }.onFailure { e ->
                android.util.Log.w(LOG_TAG, "Description update failed for ${submitted.entity.observationId}", e)
            }
            if (submitted.uuid.isNotBlank()) {
                val siblingUrls = others.joinToString(" ") { it.first.entity.observationUrl }
                runCatchingNonCancellation {
                    client.createObservationFieldValue(
                        token,
                        submitted.uuid,
                        LINKED_OBS_FIELD_ID,
                        siblingUrls,
                    )
                }.onFailure { e ->
                    android.util.Log.w(
                        LOG_TAG,
                        "Linked Observation field on ${submitted.entity.observationId} failed",
                        e,
                    )
                }
            }
        }
    }

    /**
     * Retries [block] up to [maxAttempts] times on transient failures:
     *   - [java.io.IOException]: exponential back-off 1 s, 2 s, 4 s, …
     *   - [INatException] with HTTP 5xx: same 1/2/4 s back-off.
     *   - [INatException] with HTTP 429 (rate-limited): longer back-off
     *     5 s, 10 s, 20 s — iNat heavily throttles `/observation_sounds`.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        block: suspend () -> T,
    ): T {
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: java.io.IOException) {
                lastException = e
                if (attempt < maxAttempts - 1) {
                    val delayMs = 1_000L shl attempt
                    delay(delayMs)
                }
            } catch (e: INatException) {
                val retriable = e.code >= 500 || e.code == 429
                if (retriable) {
                    lastException = e
                    if (attempt < maxAttempts - 1) {
                        val delayMs = if (e.code == 429) {
                            // Rate-limited: back off longer than 5xx
                            5_000L * (1L shl attempt) // 5s, 10s, 20s
                        } else {
                            1_000L shl attempt
                        }
                        delay(delayMs)
                    }
                } else {
                    throw e
                }
            }
        }
        throw lastException!!
    }

    private fun formatIso(epochMs: Long): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date(epochMs))
    }

    companion object {
        private const val MS = 1000L
        private const val PCT = 100f
        private const val LOG_TAG = "INatSubmitter"
        private const val APP_TAG = "WildEar"

        // iNaturalist observation field used to cross-link sibling observations
        // from the same recording (space-separated observation URLs).
        // Field name: "Linked Observation" (field id 7014).
        private const val LINKED_OBS_FIELD_ID = 7014
        private val DEFAULT_ANNOTATIONS: List<Pair<Int, Int>> = listOf(
            InatAnnotationIds.ATTR_ALIVE_OR_DEAD to InatAnnotationIds.VAL_ALIVE,
            InatAnnotationIds.ATTR_EVIDENCE_OF_PRESENCE to InatAnnotationIds.VAL_ORGANISM,
        )
    }
}
