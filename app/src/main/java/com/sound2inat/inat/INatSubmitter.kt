package com.sound2inat.inat

import androidx.room.withTransaction
import com.sound2inat.inference.SourceStats
import com.sound2inat.modelmanager.KnownModels
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.DraftWithDetections
import com.sound2inat.storage.InatObservationDao
import com.sound2inat.storage.InatObservationEntity
import com.sound2inat.storage.Sound2iNatDb
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
 *   2. Slice the WAV around that species' detected windows
 *      (`firstSeenMs..lastSeenMs` ± [PADDING_MS]) into a per-species temp file.
 *   3. POST /observations with the resolved taxon, GPS, and observed_on.
 *   4. POST /observation_sounds attaching the per-species clip.
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
    private val db: Sound2iNatDb? = null,
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
        spectrogramPhoto: File? = null,
        sourceAudioOverride: File? = null,
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
                spectrogramPhoto
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
        spectrogramPhoto: File?,
    ): Result {
        val pendingRows = mutableListOf<InatObservationEntity>()
        val createdPairs = mutableListOf<Pair<SubmittedObs, DetectionEntity>>()
        val failures = mutableListOf<String>()
        var spectrogramPhotoUploaded = false

        for (det in selected) {
            val attachSpectrogramPhoto = !spectrogramPhotoUploaded &&
                spectrogramPhoto != null &&
                spectrogramPhoto.exists() &&
                spectrogramPhoto.length() > 0L
            val outcome = runCatching {
                submitOne(
                    token,
                    draft,
                    srcAudio,
                    cropDir,
                    det,
                    habitatPhotos,
                    includeHabitatPhotoByTaxon,
                    spectrogramPhoto,
                    attachSpectrogramPhoto,
                    onSpectrogramPhotoAttempt = {
                        spectrogramPhotoUploaded = true
                    },
                )
            }
            outcome.onSuccess { submitted ->
                if (submitted != null) {
                    android.util.Log.i(
                        LOG_TAG,
                        "Uploaded ${det.taxonScientificName} -> ${submitted.entity.observationUrl}",
                    )
                    pendingRows += submitted.entity
                    createdPairs += submitted to det
                } else {
                    android.util.Log.w(LOG_TAG, "No iNat match for ${det.taxonScientificName}")
                    failures += "${det.taxonScientificName}: no iNat match"
                }
            }
            outcome.onFailure { e ->
                // Full stack into logcat; only a clamped, HTML-stripped
                // message bubbles up to the UI via INatException.
                android.util.Log.w(LOG_TAG, "Submit failed for ${det.taxonScientificName}", e)
                failures += "${det.taxonScientificName}: ${e.message}"
            }
            // Per-species crop is consumed; clean up so we don't pile MB
            // of WAVs in cache between sessions.
            runCatching { File(cropDir, cropFileName(draft.draft.id, det)).delete() }
        }

        if (pendingRows.isEmpty()) {
            val msg = "All species failed: ${failures.joinToString(" | ")}"
            drafts.update(
                draft.draft.copy(inatLastError = msg, updatedAtUtcMs = nowMs()),
            )
            return Result.Failure(msg)
        }

        // Cross-link pass — best-effort; failures don't unwind the upload.
        if (createdPairs.size > 1) {
            crossLink(token, createdPairs)
        }

        // Atomic wipe-and-replace + status flip: delete any prior rows for
        // this draft, insert the freshly created observations, and update the
        // draft's status in a SINGLE transaction. Previously the status flip
        // ran outside the transaction — if the process died between the
        // persist and the update, iNat had observations but the draft was
        // stuck in REVIEWED, so a retry would re-POST /observations and
        // create duplicates on iNaturalist. Now both writes commit together
        // or roll back together; the idempotency pre-check in `submitOne`
        // covers the narrower window between createObservation and persist.
        val updatedDraft = draft.draft.copy(
            status = DraftStatus.UPLOADED,
            inatLastError = if (failures.isEmpty()) null else failures.joinToString(" | "),
            updatedAtUtcMs = nowMs(),
        )
        val createdRows = persistAndMarkUploaded(draft.draft.id, pendingRows, updatedDraft)
        val primary = createdRows.first()
        return Result.Ok(primary.observationUrl, createdRows.map { it.observationUrl })
    }

    /**
     * Atomically deletes all prior iNat observation rows for [draftId],
     * inserts [rows], and flips the draft to its [updatedDraft] state
     * (typically `UPLOADED`). When a [Sound2iNatDb] reference is available
     * all three operations run inside a single [withTransaction] block;
     * otherwise they execute sequentially (test paths with fake DAOs that
     * have no transactional guarantee).
     */
    private suspend fun persistAndMarkUploaded(
        draftId: String,
        rows: List<InatObservationEntity>,
        updatedDraft: DraftEntity,
    ): List<InatObservationEntity> {
        fun doPersist(): List<InatObservationEntity> {
            inatObservations.deleteForDraft(draftId)
            val saved = rows.map { row ->
                val savedId = inatObservations.insert(row)
                row.copy(id = savedId)
            }
            drafts.update(updatedDraft)
            return saved
        }
        return if (db != null) db.withTransaction { doPersist() } else doPersist()
    }

    /**
     * Resolves, creates and uploads a single species' observation. Returns
     * a [SubmittedObs] wrapping the entity and the iNat UUID, or null if the
     * taxon name didn't match anything on iNat.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun submitOne(
        token: String,
        draft: DraftWithDetections,
        srcAudio: File,
        cropDir: File,
        det: DetectionEntity,
        habitatPhotos: List<File> = emptyList(),
        includeHabitatPhotoByTaxon: Map<String, Boolean> = emptyMap(),
        spectrogramPhoto: File? = null,
        uploadSpectrogramPhoto: Boolean = false,
        onSpectrogramPhotoAttempt: () -> Unit = {},
    ): SubmittedObs? {
        // Idempotency pre-check: if a prior submission attempt already created
        // an observation for this (draftId, species) on iNat but crashed before
        // persisting the corresponding inat_observations row, reuse the saved
        // entity instead of POSTing /observations a second time. The empty
        // uuid is a sentinel — we never persisted the original UUID, so
        // crossLink must skip uuid-dependent calls for reused entities.
        val existing = inatObservations.findForDraftAndSpecies(
            draft.draft.id,
            det.taxonScientificName,
        )
        if (existing != null) {
            android.util.Log.d(
                LOG_TAG,
                "Reusing existing iNat observation ${existing.observationId}" +
                    " for ${det.taxonScientificName}",
            )
            return SubmittedObs(existing, uuid = "")
        }
        val genusId = client.resolveGenus(det.taxonScientificName, token) ?: return null
        val cropFile = File(cropDir, cropFileName(draft.draft.id, det))
        cropPerSpecies(srcAudio, det, draft.draft.durationMs, cropFile)
        val obsBody = ObservationBody(
            observedAtIso = formatIso(draft.draft.recordedAtUtcMs),
            latitude = draft.draft.latitude,
            longitude = draft.draft.longitude,
            positionalAccuracy = draft.draft.locationAccuracyMeters,
            // taxonId is intentionally null here: we first create the observation,
            // then add a separate identification for each selected species.
            taxonId = null,
            description = baseDescription(det),
            licenseCode = "cc-by-nc",
        )
        val created = withRetry { client.createObservation(token, obsBody) }
        check(created.uuid.isNotBlank()) {
            "iNat /observations did not return a uuid; cannot link sound (id=${created.id})"
        }
        // Sound upload is the most fragile step (multipart on a v2 endpoint).
        // If it fails we delete the just-created observation so the user's
        // iNat account doesn't accumulate empty records on retry.
        try {
            withRetry { client.uploadSound(token, created.uuid, cropFile) }
        } catch (t: Throwable) {
            runCatching { client.deleteObservation(token, created.id) }
                .onFailure { android.util.Log.w(LOG_TAG, "Cleanup failed for ${created.id}", it) }
            throw t
        }
        if (uploadSpectrogramPhoto && spectrogramPhoto != null) {
            try {
                runCatching { client.uploadObservationPhoto(token, created.uuid, spectrogramPhoto) }
                    .onFailure {
                        android.util.Log.w(
                            LOG_TAG,
                            "Spectrogram photo upload failed for ${created.id}",
                            it,
                        )
                    }
            } finally {
                onSpectrogramPhotoAttempt()
            }
        }
        runCatching {
            client.updateObservationTags(token, created.uuid, APP_TAG)
        }.onFailure {
            android.util.Log.w(LOG_TAG, "Tag update failed for ${created.id}", it)
        }
        // Best-effort iNaturalist annotations: every recorded vocalisation is
        // by definition a living organism, so we set "Alive or Dead = Alive"
        // and "Evidence of Presence = Organism" without any species-level
        // gating. A 4xx here doesn't roll back the upload — annotations are
        // metadata polish, not core data.
        for ((attr, value) in DEFAULT_ANNOTATIONS) {
            runCatching { client.createAnnotation(token, created.uuid, attr, value) }
                .onFailure {
                    android.util.Log.w(
                        LOG_TAG,
                        "Annotation attr=$attr value=$value on ${created.id} failed",
                        it,
                    )
                }
        }
        runCatching {
            client.addIdentification(token, created.id, genusId, identificationComment(det))
        }.onFailure {
            android.util.Log.w(LOG_TAG, "addIdentification on ${created.id} failed", it)
        }
        // Best-effort habitat photo upload — failure doesn't roll back the observation.
        if (includeHabitatPhotoByTaxon[det.taxonScientificName] == true) {
            for (photo in habitatPhotos.filter { it.exists() }) {
                runCatching { client.uploadObservationPhoto(token, created.uuid, photo) }
                    .onFailure {
                        android.util.Log.w(LOG_TAG, "Photo upload failed for ${created.id}", it)
                    }
            }
        }
        val entity = InatObservationEntity(
            draftId = draft.draft.id,
            taxonScientificName = det.taxonScientificName,
            taxonInatId = genusId,
            observationId = created.id,
            observationUrl = created.url,
            createdAtUtcMs = nowMs(),
        )
        return SubmittedObs(entity, created.uuid)
    }

    private fun cropPerSpecies(
        src: File,
        det: DetectionEntity,
        draftDurationMs: Long,
        dst: File,
    ) {
        // BirdNET window length is 3 s; with PADDING_MS=1000 ms we end up with
        // a clip around 5 s minimum, which is plenty of context for a reviewer.
        val start = (det.firstSeenMs - PADDING_MS).coerceAtLeast(0L)
        val end = (det.lastSeenMs + PADDING_MS).coerceAtMost(draftDurationMs)
        WavTrimmer.trimMono16(src.absolutePath, dst, start, end)
    }

    private fun cropFileName(draftId: String, det: DetectionEntity): String {
        // Stable but uncollidable across species in the same draft.
        val safe = det.taxonScientificName.replace("[^A-Za-z0-9]+".toRegex(), "_")
        return "${draftId}__$safe.wav"
    }

    private fun baseDescription(det: DetectionEntity): String {
        val stats = SourceStats.decode(det.sources)
        val header = "Recorded with WildEar."
        return if (stats.isEmpty()) {
            "$header\nDetected ${det.detectedWindows} window(s)" +
                " between ${det.firstSeenMs / MS}–${det.lastSeenMs / MS} s," +
                " max confidence ${"%.0f".format(det.maxConfidence * PCT)}%."
        } else {
            val lines = stats.entries.sortedBy { it.key }.joinToString("\n") { (src, stat) ->
                val name = sourceDisplayName(src)
                "$name detected ${stat.windows} window(s)" +
                    " between ${stat.firstSeenMs / MS}–${stat.lastSeenMs / MS} s," +
                    " max confidence ${"%.0f".format(stat.maxConf * PCT)}%."
            }
            "$header\n$lines"
        }
    }

    private fun sourceDisplayName(id: String): String =
        KnownModels.firstOrNull { it.id == id }?.displayName ?: id

    private fun identificationComment(det: DetectionEntity): String {
        val stats = SourceStats.decodeConfidenceOnly(det.sources)
        return if (stats.isEmpty()) {
            "Detected ${det.taxonScientificName} (${"%.0f".format(det.maxConfidence * PCT)}% confidence)"
        } else {
            stats.entries.sortedBy { it.key }.joinToString("; ") { (src, conf) ->
                "${sourceDisplayName(src)}: ${det.taxonScientificName} (${"%.0f".format(conf * PCT)}%)"
            }
        }
    }

    private suspend fun crossLink(
        token: String,
        pairs: List<Pair<SubmittedObs, DetectionEntity>>,
    ) {
        for ((submitted, det) in pairs) {
            val others = pairs.filter { it.first.entity.observationId != submitted.entity.observationId }
            val siblings = others.joinToString("\n") { (sib, _) ->
                " - ${sib.entity.taxonScientificName} → ${sib.entity.observationUrl}"
            }
            val description = baseDescription(det) +
                "\n\nSibling observations from the same recording:\n$siblings"
            runCatching {
                client.updateObservationDescription(token, submitted.entity.observationId, description)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w(LOG_TAG, "Description update failed for ${submitted.entity.observationId}", e)
            }
            if (submitted.uuid.isNotBlank()) {
                val siblingUrls = others.joinToString(" ") { it.first.entity.observationUrl }
                runCatching {
                    client.createObservationFieldValue(
                        token,
                        submitted.uuid,
                        LINKED_OBS_FIELD_ID,
                        siblingUrls,
                    )
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
     * Retries [block] up to [maxAttempts] times on transient failures ([java.io.IOException]
     * or [INatException] with HTTP 5xx). Uses exponential back-off: 1 s, 2 s, 4 s, …
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> withRetry(
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
                if (e.code >= 500) {
                    lastException = e
                    if (attempt < maxAttempts - 1) {
                        val delayMs = 1_000L shl attempt
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
        private const val PADDING_MS = 1_000L
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
