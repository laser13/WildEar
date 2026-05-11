package com.sound2inat.inat

import androidx.room.withTransaction
import com.sound2inat.inference.SourceStats
import com.sound2inat.modelmanager.KnownModels
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
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
 *   5. Persist a row in `inat_observations`.
 *
 * After all observations are created:
 *   6. PUT /observations/{id} on each, writing a description that links to
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

    /** Carries both the persisted entity and the observation UUID returned by iNat. */
    private data class SubmittedObs(val entity: InatObservationEntity, val uuid: String)

    @Suppress("ReturnCount", "LongMethod", "TooGenericExceptionCaught")
    suspend fun submit(
        token: String,
        draft: DraftWithDetections,
        habitatPhotos: List<File> = emptyList(),
        includeHabitatPhotoByTaxon: Map<String, Boolean> = emptyMap(),
    ): Result = withContext(ioDispatcher) {
        if (token.isBlank()) return@withContext Result.Failure("No iNaturalist token in Settings")
        val selected = draft.detections.filter { it.isSelectedByUser }
        if (selected.isEmpty()) return@withContext Result.Failure("No species selected")
        val srcAudio = File(draft.draft.audioPath)
        if (!srcAudio.exists()) return@withContext Result.Failure("Audio file missing on disk")

        val cropDir = File(tmpRoot, "inat_uploads").apply { mkdirs() }
        val pendingRows = mutableListOf<InatObservationEntity>()
        val createdPairs = mutableListOf<Pair<SubmittedObs, DetectionEntity>>()
        val failures = mutableListOf<String>()

        for (det in selected) {
            val outcome = runCatching {
                submitOne(token, draft, srcAudio, cropDir, det, habitatPhotos, includeHabitatPhotoByTaxon)
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
            return@withContext Result.Failure(msg)
        }

        // Cross-link pass — best-effort; failures don't unwind the upload.
        if (createdPairs.size > 1) {
            crossLink(token, createdPairs)
        }

        // Atomic wipe-and-replace: delete any prior rows for this draft, then
        // insert the freshly created observations in a single transaction so a
        // mid-insert failure never leaves the table in a torn state.
        val createdRows = persistObservations(draft.draft.id, pendingRows)

        val primary = createdRows.first()
        drafts.update(
            draft.draft.copy(
                status = DraftStatus.UPLOADED,
                inatLastError = if (failures.isEmpty()) null else failures.joinToString(" | "),
                updatedAtUtcMs = nowMs(),
            ),
        )
        Result.Ok(primary.observationUrl, createdRows.map { it.observationUrl })
    }

    /**
     * Atomically deletes all prior iNat observation rows for [draftId] and
     * inserts [rows]. When a [Sound2iNatDb] reference is available the two
     * operations run inside a single [withTransaction] block; otherwise they
     * execute sequentially (test paths with fake DAOs).
     */
    private suspend fun persistObservations(
        draftId: String,
        rows: List<InatObservationEntity>,
    ): List<InatObservationEntity> {
        fun doInsert(): List<InatObservationEntity> {
            inatObservations.deleteForDraft(draftId)
            return rows.map { row ->
                val savedId = inatObservations.insert(row)
                row.copy(id = savedId)
            }
        }
        return if (db != null) db.withTransaction { doInsert() } else doInsert()
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
    ): SubmittedObs? {
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
            }
            val siblingUrls = others.joinToString(" ") { it.first.entity.observationUrl }
            runCatching {
                client.createObservationFieldValue(
                    token,
                    submitted.uuid,
                    LINKED_OBS_FIELD_ID,
                    siblingUrls,
                )
            }.onFailure {
                android.util.Log.w(
                    LOG_TAG,
                    "Linked Observation field on ${submitted.entity.observationId} failed",
                    it,
                )
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

        // iNaturalist controlled-vocabulary IDs. Source: iNaturalist Helper
        // Chrome extension (`scripts/vision.js`). The pairs we apply to every
        // WildEar observation:
        //   17 → 18 = "Alive or Dead" → "Alive"
        //   22 → 24 = "Evidence of Presence" → "Organism"
        private const val ATTR_ALIVE_OR_DEAD = 17
        private const val VALUE_ALIVE = 18
        private const val ATTR_EVIDENCE = 22
        private const val VALUE_ORGANISM = 24

        // iNaturalist observation field used to cross-link sibling observations
        // from the same recording (space-separated observation URLs).
        // Field name: "Linked Observation" (field id 7014).
        private const val LINKED_OBS_FIELD_ID = 7014
        private val DEFAULT_ANNOTATIONS: List<Pair<Int, Int>> = listOf(
            ATTR_ALIVE_OR_DEAD to VALUE_ALIVE,
            ATTR_EVIDENCE to VALUE_ORGANISM,
        )
    }
}
