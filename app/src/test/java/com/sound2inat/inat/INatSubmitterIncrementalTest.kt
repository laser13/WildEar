package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.DraftWithDetections
import com.sound2inat.storage.InatObservationEntity
import com.sound2inat.storage.InatUploadStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies incremental, re-runnable iNaturalist submission:
 *
 *   * a partial upload (some species failed) leaves the draft in REVIEWED so
 *     the user can retry,
 *   * a second submit() picks up only the species that lack a row in
 *     `inat_observations` and PUT-updates the descriptions of ALL prior
 *     observations of the draft (not just the ones created in this run) so
 *     the sibling list stays consistent.
 *
 * Uses a [TrackingClient] subclass of [INaturalistClient] to:
 *   * script a sound-upload failure for the Nth observation in a single call,
 *   * record every observationId that received a description PUT,
 *
 * keeping the test independent of [MockWebServer] queue ordering for those
 * assertions. All other endpoints go through real HTTP via MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class INatSubmitterIncrementalTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -------------------------------------------------------------------------
    // Helpers (mirror INatSubmitter*Test.kt style)
    // -------------------------------------------------------------------------

    /**
     * Subclass of [INaturalistClient] that:
     *   - throws on [uploadSound] when the running call index matches
     *     [scriptedSoundUploadFailureIndex] (0-based across the whole submit
     *     run); other calls delegate to the real HTTP path,
     *   - records every [updateObservationDescription] call so tests can
     *     assert which observations had their descriptions PUT.
     */
    private inner class TrackingClient(
        http: OkHttpClient,
        baseUrl: String,
        ioDispatcher: CoroutineDispatcher,
    ) : INaturalistClient(http, baseUrl, ioDispatcher = ioDispatcher) {
        val descriptionUpdates = mutableListOf<DescriptionUpdate>()
        private var soundUploadCallCount = 0
        private var scriptedSoundUploadFailureIndex: Int? = null

        fun scriptSoundUploadFailure(forObservationIndex: Int) {
            scriptedSoundUploadFailureIndex = forObservationIndex
        }

        override suspend fun uploadSound(
            token: String,
            observationUuid: String,
            audioFile: File,
        ): Long {
            val callIndex = soundUploadCallCount++
            if (callIndex == scriptedSoundUploadFailureIndex) {
                throw INatException(code = 500, message = "Scripted sound upload failure")
            }
            return super.uploadSound(token, observationUuid, audioFile)
        }

        override suspend fun updateObservationDescription(
            token: String,
            observationId: Long,
            description: String,
        ) {
            descriptionUpdates += DescriptionUpdate(observationId, description)
            // We deliberately do NOT call super here — the existing test fakes
            // would need a queued MockWebServer response per PUT, and we want
            // the test to be independent of that queue. The submitter itself
            // wraps this call in runCatching so a non-throwing override is fine.
        }

        /**
         * Per-clip spectrogram uploads now fire from inside submitOne; this
         * test focuses on incremental retry semantics, not on photo uploads.
         * Short-circuit the photo endpoint so the existing MockWebServer
         * response queues stay correct.
         */
        override suspend fun uploadObservationPhoto(
            token: String,
            observationId: Long,
            photoFile: File,
            mimeType: String,
        ): Long = 0L
    }

    private fun makeClient(): TrackingClient = TrackingClient(
        OkHttpClient(),
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun makeSubmitter(
        client: INaturalistClient,
        draftDao: InMemoryDraftDao,
        inatDao: InMemoryInatObservationDao,
        cacheFolder: File,
    ) = INatSubmitter(
        client = client,
        drafts = draftDao,
        inatObservations = inatDao,
        tmpRoot = cacheFolder,
        ioDispatcher = UnconfinedTestDispatcher(),
        nowMs = { 0L },
    )

    /**
     * Builds a draft with one [DetectionEntity] per scientific name in
     * [speciesNames], all marked selected by the user, plus a real 4-second
     * mono16 WAV on disk (the trimmer rejects anything else).
     */
    private fun makeDraftWithDetections(
        speciesNames: List<String>,
        draftId: String = "d-inc-1",
    ): DraftWithDetections {
        // Use File(tmp.root, …) instead of tmp.newFile() so repeated calls
        // for the same draftId (e.g. simulating a second submit() run on the
        // same draft) don't trip TemporaryFolder's "file already exists" check.
        val wav = File(tmp.root, "$draftId-clip.wav")
        val writer = WavWriter(wav, sampleRate = 48_000, channels = 1, bitsPerSample = 16)
        writer.open()
        writer.writeShorts(ShortArray(48_000 * 4), 0, 48_000 * 4)
        writer.close()
        val draft = DraftEntity(
            id = draftId,
            audioPath = wav.absolutePath,
            recordedAtUtcMs = 1_700_000_000_000L,
            durationMs = 4_000L,
            latitude = 35.16,
            longitude = 33.36,
            locationAccuracyMeters = 5f,
            status = DraftStatus.REVIEWED,
            modelId = "birdnet_v2_4",
            modelVersion = "2.4",
            createdAtUtcMs = 0L,
            updatedAtUtcMs = 0L,
        )
        val detections = speciesNames.mapIndexed { i, name ->
            DetectionEntity(
                id = (i + 1).toLong(),
                draftId = draftId,
                taxonScientificName = name,
                taxonCommonName = null,
                maxConfidence = 0.9f - i * 0.05f,
                detectedWindows = 5,
                firstSeenMs = (i * 1000L).coerceAtMost(2000L),
                lastSeenMs = ((i * 1000L) + 1000L).coerceAtMost(3500L),
                isSelectedByUser = true,
            )
        }
        return DraftWithDetections(draft, detections)
    }

    /** Enqueues the 7 MockResponses for one full successful species submission. */
    private fun enqueueSuccess(uuid: String, observationId: Long, taxonId: Long = 12345L) {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":$taxonId,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":$observationId,"uuid":"$uuid"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        server.enqueue(MockResponse().setBody("""{"id":21}"""))
    }

    /**
     * Enqueues responses for a species whose sound upload is going to fail
     * (scripted by [TrackingClient.scriptSoundUploadFailure]). The submitter
     * resolves → creates → attempts uploadSound (which throws via the tracker,
     * NOT MockWebServer) → cleanup DELETE. So we queue:
     *   - resolveGenus
     *   - createObservation
     *   - deleteObservation cleanup
     */
    private fun enqueueSoundUploadFailure(uuid: String, observationId: Long, taxonId: Long = 12346L) {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":$taxonId,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":$observationId,"uuid":"$uuid"}"""))
        server.enqueue(MockResponse().setBody("""{"id":$observationId}"""))
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `partial upload keeps draft REVIEWED so user can retry`() = runTest {
        val draft = makeDraftWithDetections(
            speciesNames = listOf("Parus major", "Corvus corone"),
        )
        val client = makeClient().apply { scriptSoundUploadFailure(forObservationIndex = 1) }
        val drafts = InMemoryDraftDao().apply { inserted += draft.draft }
        val inatObs = InMemoryInatObservationDao()
        val submitter = makeSubmitter(client, drafts, inatObs, tmp.newFolder("cache-inc-1"))

        // Species 1 (index 0) succeeds — needs full 7 responses.
        enqueueSuccess(uuid = "u-1", observationId = 900L, taxonId = 1L)
        // Species 2 (index 1) — sound upload fails, so resolveGenus + create + delete cleanup.
        enqueueSoundUploadFailure(uuid = "u-2", observationId = 901L, taxonId = 2L)

        val result = submitter.submit(token = "jwt", draft = draft)

        // First species succeeded → result is Ok with one URL.
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val ok = result as INatSubmitter.Result.Ok
        assertThat(ok.urls).hasSize(1)
        assertThat(inatObs.rows.map { it.uploadStatus })
            .containsExactly(InatUploadStatus.COMPLETE)

        // Draft must NOT be UPLOADED — the user has a pending species to retry.
        val saved = drafts.inserted.first()
        assertThat(saved.status).isEqualTo(DraftStatus.REVIEWED)
        // inatLastError captures the failure for the UI banner.
        assertThat(saved.inatLastError).isNotNull()
        assertThat(saved.inatLastError).contains("Corvus corone")
    }

    @Test
    fun `second run with only new selection still cross-links pre-existing siblings`() = runTest {
        // Scenario: the user submitted two species in run 1. In run 2 they
        // ONLY select a freshly-detected third species (the older two are
        // unticked, so the submitter never processes them in this run).
        // Despite that, the cross-link pass must still update the description
        // of the older observations so they reference the new sibling, and
        // the new observation must reference both older siblings.
        val firstRunDraft = makeDraftWithDetections(
            speciesNames = listOf("Parus major", "Corvus corone"),
        )
        val client = makeClient()
        val drafts = InMemoryDraftDao().apply { inserted += firstRunDraft.draft }
        val inatObs = InMemoryInatObservationDao()
        val submitter = makeSubmitter(client, drafts, inatObs, tmp.newFolder("cache-inc-2"))

        // Pre-populate prior iNat rows as if a successful first run had landed
        // them. We do this directly (without going through MockWebServer) so
        // the second run's HTTP queue stays uncluttered and assertions on
        // description updates are easy to read.
        inatObs.rows += listOf(
            InatObservationEntity(
                id = 1L,
                draftId = firstRunDraft.draft.id,
                taxonScientificName = "Parus major",
                taxonInatId = 1L,
                observationId = 900L,
                observationUrl = "https://www.inaturalist.org/observations/900",
                createdAtUtcMs = 0L,
            ),
            InatObservationEntity(
                id = 2L,
                draftId = firstRunDraft.draft.id,
                taxonScientificName = "Corvus corone",
                taxonInatId = 2L,
                observationId = 901L,
                observationUrl = "https://www.inaturalist.org/observations/901",
                createdAtUtcMs = 0L,
            ),
        )

        // Second run: user only selects the newly detected Apus apus.
        // The two older detections appear in the draft but are NOT selected.
        val draftId = firstRunDraft.draft.id
        val wav = File(tmp.root, "$draftId-clip.wav")
        val updatedDraft = firstRunDraft.copy(
            detections = listOf(
                firstRunDraft.detections[0].copy(isSelectedByUser = false),
                firstRunDraft.detections[1].copy(isSelectedByUser = false),
                DetectionEntity(
                    id = 3L,
                    draftId = draftId,
                    taxonScientificName = "Apus apus",
                    taxonCommonName = null,
                    maxConfidence = 0.7f,
                    detectedWindows = 4,
                    firstSeenMs = 1500L,
                    lastSeenMs = 2500L,
                    isSelectedByUser = true,
                ),
            ),
        )
        // Sanity: ensure the WAV exists (firstRunDraft built it above).
        check(wav.exists()) { "wav fixture must exist for second-run trim" }

        // Only Apus apus needs the full 7 HTTP responses.
        enqueueSuccess(uuid = "u-3", observationId = 902L, taxonId = 3L)
        // Apus's crossLink will POST observation_field_values (uuid present);
        // we queue one response. The pre-existing observations have blank
        // uuids in the priorPairs path, so no OFV is posted for them.
        server.enqueue(MockResponse().setBody("""{"id":33}"""))

        val result = submitter.submit(token = "jwt", draft = updatedDraft)
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)

        // All three rows must persist after the second run: the carried-over
        // older rows (900, 901) PLUS the freshly-uploaded 902. If
        // persistAndMarkUploaded wiped the prior rows instead of unioning
        // them with carriedOver, this would shrink to just [902] — that's
        // the regression this assertion guards against.
        val rowsByObs = inatObs.rows.map { it.observationId }
        assertThat(rowsByObs).containsExactly(900L, 901L, 902L)

        // All THREE observations must have had their descriptions PUT during
        // the second run's crossLink — the freshly-created 902 AND the two
        // previously-persisted observations 900 and 901. With the legacy
        // crossLink (operating only on this run's createdPairs of size 1)
        // no description updates would happen at all.
        val updatedIds = client.descriptionUpdates.map { it.observationId }.toSet()
        assertThat(updatedIds).containsExactly(900L, 901L, 902L)
    }

    @Test
    fun `malformed row for one species does not wipe sibling rows`() = runTest {
        // Two species already on iNat for the same draft: Parus major has a
        // malformed row (blank URL, zero id) that the idempotency check must
        // discard, and Apus apus has a valid row. The submitter must wipe only
        // the malformed row — not the sibling — so Task B's union-row
        // persistence still sees the carried-over valid sibling.
        val draftWithDetections = makeDraftWithDetections(
            speciesNames = listOf("Parus major"),
            draftId = "d-malformed-1",
        )
        val client = makeClient()
        val drafts = InMemoryDraftDao().apply { inserted += draftWithDetections.draft }
        val inatObs = InMemoryInatObservationDao().apply {
            rows += InatObservationEntity(
                id = 1L,
                draftId = draftWithDetections.draft.id,
                taxonScientificName = "Parus major",
                taxonInatId = 1L,
                observationId = 0L, // malformed: zero id
                observationUrl = "", // malformed: blank url
                createdAtUtcMs = 0L,
            )
            rows += InatObservationEntity(
                id = 2L,
                draftId = draftWithDetections.draft.id,
                taxonScientificName = "Apus apus",
                taxonInatId = 2L,
                observationId = 901L,
                observationUrl = "https://www.inaturalist.org/observations/901",
                createdAtUtcMs = 0L,
            )
        }
        val submitter = makeSubmitter(
            client = client,
            draftDao = drafts,
            inatDao = inatObs,
            cacheFolder = tmp.newFolder("cache-malformed"),
        )

        // Parus major: full resolve/create/sound/tag/annotations/identification path.
        enqueueSuccess(uuid = "u-malformed", observationId = 902L, taxonId = 1L)

        val result = submitter.submit(token = "jwt", draft = draftWithDetections)
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)

        // After the run: malformed Parus major row replaced by a fresh one with
        // observationId=902, and the sibling Apus apus row must still be present.
        val remainingSpecies = inatObs.rows.map { it.taxonScientificName }
        assertThat(remainingSpecies).contains("Apus apus")
        // Sanity: the malformed row (observationId=0) is gone, the fresh
        // observation (902) and the carried-over Apus row (901) survive.
        val remainingObservationIds = inatObs.rows.map { it.observationId }.toSet()
        assertThat(remainingObservationIds).containsExactly(902L, 901L)
    }
}

private data class DescriptionUpdate(val observationId: Long, val description: String)
