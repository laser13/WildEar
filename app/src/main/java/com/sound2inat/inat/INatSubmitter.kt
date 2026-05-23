package com.sound2inat.inat

import androidx.room.withTransaction
import com.sound2inat.app.ui.spectrogram.SpectrogramPalette
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
    ): Result {
        val pendingRows = mutableListOf<InatObservationEntity>()
        val createdPairs = mutableListOf<Pair<SubmittedObs, DetectionEntity>>()
        val failures = mutableListOf<String>()

        for (det in selected) {
            val outcome = runCatching {
                submitOne(
                    token,
                    draft,
                    srcAudio,
                    cropDir,
                    det,
                    habitatPhotos,
                    includeHabitatPhotoByTaxon,
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
            // Per-species crops are consumed; the whole `cropDir` is wiped in
            // the surrounding `finally` block of [submit], so no per-iteration
            // cleanup is needed here.
        }

        if (pendingRows.isEmpty()) {
            val msg = "All species failed: ${failures.joinToString(" | ")}"
            drafts.update(
                draft.draft.copy(inatLastError = msg, updatedAtUtcMs = nowMs()),
            )
            return Result.Failure(msg)
        }

        // Cross-link pass — best-effort; failures don't unwind the upload.
        // Always run when ANY species is in scope (prior or fresh), so prior
        // sibling observations get their description PUT-updated to reference
        // newly-added species too.
        if (createdPairs.isNotEmpty()) {
            crossLink(token, draft, createdPairs)
        }

        // Atomic wipe-and-replace + status flip: delete any prior rows for
        // this draft, insert the union of (rows carried over from prior runs
        // for species NOT submitted in this run) + (rows freshly created
        // here), and update the draft's status — all in a SINGLE transaction.
        //
        // The union step is what makes the flow incrementally re-runnable:
        // wiping-and-replacing with only `pendingRows` would orphan local
        // copies of observations from a previous successful submit (they'd
        // still exist on iNat but the app would forget about them), causing
        // `pendingCount` in ReviewViewModel to re-count them as pending and
        // the UploadedBanner to lose its links. The idempotency pre-check
        // in `submitOne` covers the narrower window between createObservation
        // and persist; this union covers the cross-run window.
        //
        // Status logic: the draft only flips to UPLOADED when every selected
        // species was successfully persisted AND no per-species failure was
        // recorded. Otherwise it stays in REVIEWED so the user can re-run
        // submit to retry the missing species (the idempotency pre-check
        // skips the already-persisted ones).
        val totalSelected = selected.size
        val succeeded = pendingRows.size
        val anyFailure = failures.isNotEmpty()
        val nextStatus = if (succeeded == totalSelected && !anyFailure) {
            DraftStatus.UPLOADED
        } else {
            DraftStatus.REVIEWED
        }
        val updatedDraft = draft.draft.copy(
            status = nextStatus,
            inatLastError = if (failures.isEmpty()) null else failures.joinToString(" | "),
            updatedAtUtcMs = nowMs(),
        )
        // Build the full row set to persist: carry over any prior rows whose
        // observation isn't being re-inserted by this run, then append the
        // freshly-created rows. Re-reading priorRows here (after crossLink
        // already read them) is cheap and keeps the persist step self-
        // contained — the DAO is local and the read is cheap.
        val priorRows = inatObservations.listForDraft(draft.draft.id)
        val newIds = pendingRows.mapTo(mutableSetOf()) { it.observationId }
        val carriedOver = priorRows.filter { it.observationId !in newIds }
        val allRows = carriedOver + pendingRows
        val savedRows = persistAndMarkUploaded(draft.draft.id, allRows, updatedDraft)
        // The user's expectation when (re-)submitting is to see the URL of a
        // species they just uploaded, not a carried-over one. The freshly-
        // inserted rows appear at the tail of `savedRows` (we appended them
        // above), so take the first row from that tail as the primary URL.
        // `pendingRows` is guaranteed non-empty here (the early-return above
        // covers the no-fresh-uploads case).
        val freshlyInsertedUrls = savedRows.takeLast(pendingRows.size).map { it.observationUrl }
        val primary = freshlyInsertedUrls.first()
        return Result.Ok(primary, savedRows.map { it.observationUrl })
    }

    /**
     * Atomically deletes all prior iNat observation rows for [draftId],
     * inserts [rows] (the caller passes the FULL set to keep — carried-over
     * prior rows + freshly-created rows for this run), and flips the draft
     * to its [updatedDraft] state. When a [Sound2iNatDb] reference is
     * available all three operations run inside a single [withTransaction]
     * block; otherwise they execute sequentially (test paths with fake DAOs
     * that have no transactional guarantee).
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
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private suspend fun submitOne(
        token: String,
        draft: DraftWithDetections,
        srcAudio: File,
        cropDir: File,
        det: DetectionEntity,
        habitatPhotos: List<File> = emptyList(),
        includeHabitatPhotoByTaxon: Map<String, Boolean> = emptyMap(),
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
        if (existing != null && existing.observationUrl.isNotBlank() && existing.observationId > 0L) {
            android.util.Log.d(
                LOG_TAG,
                "Reusing existing iNat observation ${existing.observationId}" +
                    " for ${det.taxonScientificName}",
            )
            return SubmittedObs(existing, uuid = "")
        }
        if (existing != null) {
            android.util.Log.w(
                LOG_TAG,
                "Discarding malformed inat_observations row for ${det.taxonScientificName}" +
                    " (id=${existing.observationId}, url='${existing.observationUrl}') — will recreate",
            )
            // Targeted delete: do NOT use deleteForDraft here — that would
            // wipe sibling species' rows that are still valid, and Task B's
            // union-row persistence relies on those staying present until
            // persistAndMarkUploaded runs.
            inatObservations.deleteForDraftAndSpecies(draft.draft.id, det.taxonScientificName)
        }
        val genusId = client.resolveGenus(det.taxonScientificName, token) ?: return null
        val clips = materialiseClips(
            srcAudio = srcAudio,
            det = det,
            draftDurationMs = draft.draft.durationMs,
            cropDir = cropDir,
            paletteName = draft.draft.paletteName,
            contrastDb = draft.draft.spectrogramGainDb ?: 0f,
        )
        check(clips.isNotEmpty()) {
            "All WAV trims failed for ${det.taxonScientificName} — disk full or audio corrupted?"
        }

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
        // Sound upload of the FIRST clip is the most fragile step (multipart
        // on a v2 endpoint). If even this fails we delete the just-created
        // observation so the user's iNat account doesn't accumulate empty
        // records on retry.
        val firstClip = clips.first()
        try {
            withRetry { client.uploadSound(token, created.uuid, firstClip.wav) }
        } catch (t: Throwable) {
            runCatching { client.deleteObservation(token, created.id) }
                .onFailure { android.util.Log.w(LOG_TAG, "Cleanup failed for ${created.id}", it) }
            throw t
        }
        // Upload the first clip's spectrogram (best-effort).
        firstClip.spectrogramPng?.let { png ->
            runCatching { client.uploadObservationPhoto(token, created.id, png) }
                .onFailure {
                    android.util.Log.w(
                        LOG_TAG,
                        "Spectrogram photo upload failed for ${created.id} (clip 0)",
                        it,
                    )
                }
        }
        // Remaining clips: best-effort. Failures don't roll back — the
        // observation already has at least one playable sound.
        //
        // Known limitation (per-clip idempotency): if doSubmit crashes after
        // some extra clips uploaded but before persistAndMarkUploaded, a retry
        // of this species via Task B's incremental flow is skipped entirely by
        // findForDraftAndSpecies (the observation row exists). The extra clips
        // stay where they landed, no duplicates created. Per-clip Room state
        // is deferred to a follow-up.
        for ((idx, clip) in clips.withIndex()) {
            if (idx == 0) continue
            runCatching { withRetry { client.uploadSound(token, created.uuid, clip.wav) } }
                .onFailure {
                    android.util.Log.w(
                        LOG_TAG,
                        "Extra sound upload failed for ${created.id} (clip $idx)",
                        it,
                    )
                }
            clip.spectrogramPng?.let { png ->
                runCatching { client.uploadObservationPhoto(token, created.id, png) }
                    .onFailure {
                        android.util.Log.w(
                            LOG_TAG,
                            "Spectrogram photo upload failed for ${created.id} (clip $idx)",
                            it,
                        )
                    }
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
                runCatching { client.uploadObservationPhoto(token, created.id, photo) }
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
