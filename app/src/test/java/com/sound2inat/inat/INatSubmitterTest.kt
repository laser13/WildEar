package com.sound2inat.inat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.audio.WavWriter
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.DraftWithDetections
import com.sound2inat.storage.InatObservationDao
import com.sound2inat.storage.InatObservationEntity
import com.sound2inat.storage.InatUploadStatus
import com.sound2inat.storage.Sound2iNatDb
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class INatSubmitterTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: INaturalistClient
    private lateinit var dao: InMemoryDraftDao
    private lateinit var inatDao: InMemoryInatObservationDao
    private lateinit var submitter: INatSubmitter

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        // Per-clip spectrogram uploads now hit `uploadObservationPhoto` from
        // inside submitOne — but the test cases below carefully enumerate the
        // HTTP request count for the resolve/create/sound/tag/annotate/identify
        // path. Override the photo endpoint to a no-op so MockWebServer is not
        // hit by the new spectrogram upload, and existing assertions keep
        // their meaning. INatSubmitterMultiClipTest covers the new behaviour.
        client = object : INaturalistClient(
            OkHttpClient(),
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            ioDispatcher = UnconfinedTestDispatcher(),
        ) {
            override suspend fun uploadObservationPhoto(
                token: String,
                observationId: Long,
                photoFile: File,
                mimeType: String,
            ): Long = 0L
        }
        dao = InMemoryDraftDao()
        inatDao = InMemoryInatObservationDao()
        submitter = INatSubmitter(
            client = client,
            drafts = dao,
            inatObservations = inatDao,
            tmpRoot = tmp.newFolder("cache"),
            ioDispatcher = UnconfinedTestDispatcher(),
            nowMs = { 0L },
        )
    }

    @After fun tearDown() { server.shutdown() }

    /**
     * Builds a real mono-16 WAV (the trimmer rejects anything else) and seeds
     * the fake DAO with a draft that points at it. [selected] supplies
     * scientific names for selected detections; first/last seen ms are spread
     * out across the file so per-species crops don't collapse to empty.
     */
    private fun draftWith(selected: List<String>): DraftWithDetections {
        val wav = tmp.newFile("clip.wav")
        // 4 seconds of mono PCM @ 48 kHz → 192_000 samples; trimmer needs a
        // valid 44-byte header.
        val writer = WavWriter(wav, sampleRate = 48_000, channels = 1, bitsPerSample = 16)
        writer.open()
        writer.writeShorts(ShortArray(48_000 * 4), 0, 48_000 * 4)
        writer.close()
        val draft = DraftEntity(
            id = "d-1",
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
        dao.inserted += draft
        val detections = selected.mapIndexed { i, name ->
            DetectionEntity(
                id = (i + 1).toLong(),
                draftId = "d-1",
                taxonScientificName = name,
                taxonCommonName = null,
                maxConfidence = 0.9f - i * 0.1f,
                detectedWindows = 5,
                // Stagger windows so per-species crops are non-overlapping.
                firstSeenMs = (i * 1000L).coerceAtMost(2000L),
                lastSeenMs = ((i * 1000L) + 1000L).coerceAtMost(3500L),
                isSelectedByUser = true,
            )
        }
        return DraftWithDetections(draft, detections)
    }

    /** Like [draftWith] but sets [sources] on the first detection. */
    private fun draftWithSources(name: String, sources: String): DraftWithDetections {
        val base = draftWith(listOf(name))
        val det = base.detections.first().copy(sources = sources)
        return base.copy(detections = listOf(det))
    }

    @Test fun `single species creates one observation and persists row`() = runTest {
        // resolveGenus
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}""",
            ),
        )
        // createObservation
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        // uploadSound (v2 wraps in { results: [...] })
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        // updateObservationTags (best-effort v2 metadata step)
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        // Two best-effort annotations after sound upload (Alive + Organism).
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        // addIdentification (genus-level, best-effort).
        server.enqueue(MockResponse().setBody("""{"id":21}"""))

        val result = submitter.submit("jwt", draftWith(listOf("Parus major")))
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val ok = result as INatSubmitter.Result.Ok
        assertThat(ok.urls).hasSize(1)
        assertThat(ok.primaryUrl).isEqualTo("https://www.inaturalist.org/observations/900")

        val taxaReq = server.takeRequest()
        assertThat(taxaReq.path).contains("/taxa")
        val createReq = server.takeRequest()
        assertThat(createReq.method).isEqualTo("POST")
        assertThat(createReq.path).isEqualTo("/v1/observations")
        val soundReq = server.takeRequest()
        assertThat(soundReq.path).isEqualTo("/v2/observation_sounds")
        val tagReq = server.takeRequest()
        assertThat(tagReq.method).isEqualTo("PUT")
        assertThat(tagReq.path).isEqualTo("/v2/observations/u-1")
        assertThat(tagReq.body.readUtf8()).contains("\"tag_list\":\"WildEar\"")

        // Persistence: draft marked UPLOADED + one row in inat_observations.
        assertThat(dao.inserted.first().status).isEqualTo(DraftStatus.UPLOADED)
        assertThat(inatDao.rows).hasSize(1)
        assertThat(inatDao.rows.first().taxonScientificName).isEqualTo("Parus major")
        assertThat(inatDao.rows.first().observationId).isEqualTo(900L)
        assertThat(inatDao.rows.map { it.uploadStatus })
            .containsExactly(InatUploadStatus.COMPLETE)

        // resolve + create + sound + tag + 2 annotations + identification = 7.
        assertThat(server.requestCount).isEqualTo(7)
    }

    @Test fun `tag update failure does not roll back the submission`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        server.enqueue(MockResponse().setBody("""{"id":21}"""))

        val result = submitter.submit("jwt", draftWith(listOf("Parus major")))

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(dao.inserted.first().status).isEqualTo(DraftStatus.UPLOADED)
        assertThat(inatDao.rows).hasSize(1)
        assertThat(inatDao.rows.map { it.uploadStatus })
            .containsExactly(InatUploadStatus.COMPLETE)
        assertThat(server.requestCount).isEqualTo(7)
    }

    @Test fun `multi-species creates separate observations and PUT cross-links`() = runTest {
        // 1.  resolve Parus
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"iconic_taxon_name":"Aves"}]}"""))
        // 2.  create observation for Parus
        server.enqueue(MockResponse().setBody("""{"id":700,"uuid":"u-A"}"""))
        // 3.  upload sound for Parus (v2 wraps)
        server.enqueue(MockResponse().setBody("""{"results":[{"id":555}]}"""))
        // 4. updateObservationTags for Parus.
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        // 5-6. annotations Alive + Organism for Parus.
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        // 7. addIdentification for Parus (best-effort).
        server.enqueue(MockResponse().setBody("""{"id":21}"""))
        // 8.  resolve Sylvia
        server.enqueue(MockResponse().setBody("""{"results":[{"id":2,"iconic_taxon_name":"Aves"}]}"""))
        // 9.  create observation for Sylvia
        server.enqueue(MockResponse().setBody("""{"id":701,"uuid":"u-B"}"""))
        // 10. upload sound for Sylvia (v2 wraps)
        server.enqueue(MockResponse().setBody("""{"results":[{"id":556}]}"""))
        // 11. updateObservationTags for Sylvia.
        server.enqueue(MockResponse().setBody("""{"id":10}"""))
        // 12-13. annotations Alive + Organism for Sylvia.
        server.enqueue(MockResponse().setBody("""{"id":13}"""))
        server.enqueue(MockResponse().setBody("""{"id":14}"""))
        // 14. addIdentification for Sylvia (best-effort).
        server.enqueue(MockResponse().setBody("""{"id":22}"""))
        // 15. PUT description on observation 700
        server.enqueue(MockResponse().setBody("""{"id":700}"""))
        // 16. POST observation_field_values for observation u-A (Linked Observation)
        server.enqueue(MockResponse().setBody("""{"id":31}"""))
        // 17. PUT description on observation 701
        server.enqueue(MockResponse().setBody("""{"id":701}"""))
        // 18. POST observation_field_values for observation u-B (Linked Observation)
        server.enqueue(MockResponse().setBody("""{"id":32}"""))

        val result = submitter.submit("jwt", draftWith(listOf("Parus major", "Sylvia")))
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val ok = result as INatSubmitter.Result.Ok
        assertThat(ok.urls).hasSize(2)
        assertThat(inatDao.rows).hasSize(2)
        assertThat(inatDao.rows.map { it.uploadStatus })
            .containsExactly(InatUploadStatus.COMPLETE, InatUploadStatus.COMPLETE)

        // Drain the first 14 requests (resolve/create/upload/tag + 2 annotations + identification × 2).
        repeat(14) { server.takeRequest() }
        val put1 = server.takeRequest()
        assertThat(put1.method).isEqualTo("PUT")
        assertThat(put1.path).endsWith("/observations/700")
        assertThat(put1.body.readUtf8()).contains("observations\\/701")
        server.takeRequest() // POST /observation_field_values for u-A
        val put2 = server.takeRequest()
        assertThat(put2.method).isEqualTo("PUT")
        assertThat(put2.path).endsWith("/observations/701")
        assertThat(put2.body.readUtf8()).contains("observations\\/700")
    }

    @Test fun `unresolved species is skipped but others succeed`() = runTest {
        // Parus resolves, Sylvia returns empty results.
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":700,"uuid":"u-A"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":555}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        // Annotations Alive + Organism for Parus.
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        // addIdentification for Parus (best-effort).
        server.enqueue(MockResponse().setBody("""{"id":21}"""))
        server.enqueue(MockResponse().setBody("""{"results":[]}""")) // Sylvia: no match

        val result = submitter.submit("jwt", draftWith(listOf("Parus major", "Sylvia")))
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(inatDao.rows).hasSize(1)
        // Partial-success contract (incremental submission): the Sylvia
        // failure keeps the draft in REVIEWED so the user can re-run submit
        // after fixing the issue (e.g. the species got a name correction on
        // iNat). Parus's row is persisted and the idempotency pre-check in
        // submitOne will short-circuit Parus on the retry.
        assertThat(dao.inserted.first().status).isEqualTo(DraftStatus.REVIEWED)
        assertThat(dao.inserted.first().inatLastError).contains("Sylvia")
    }

    @Test fun `all species failing leaves draft in REVIEWED with error message`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[]}""")) // no match
        val result = submitter.submit("jwt", draftWith(listOf("Parus major")))
        assertThat(result).isInstanceOf(INatSubmitter.Result.Failure::class.java)
        assertThat(dao.inserted.first().status).isEqualTo(DraftStatus.REVIEWED)
        assertThat(dao.inserted.first().inatLastError).contains("All species failed")
        assertThat(inatDao.rows).isEmpty()
    }

    @Test fun `re-submit preserves prior inat rows for species not in this run`() = runTest {
        // Pre-populate as if a prior submit landed two observations for
        // species the user did NOT pick this time around.
        inatDao.rows += listOf(
            InatObservationEntity(1, "d-1", "Old A", 1L, 100L, "https://x/100", 0L),
            InatObservationEntity(2, "d-1", "Old B", 2L, 101L, "https://x/101", 0L),
        )
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        // Annotations Alive + Organism after the (single) sound upload.
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        // addIdentification (best-effort).
        server.enqueue(MockResponse().setBody("""{"id":21}"""))
        // crossLink now includes the prior rows (3 total siblings) so it
        // issues a PUT description per row plus a POST OFV for the new one
        // (the prior rows have no uuid, so OFV is skipped for them).
        server.enqueue(MockResponse().setBody("""{"id":900}""")) // PUT description on new 900
        server.enqueue(MockResponse().setBody("""{"id":100}""")) // PUT description on prior 100
        server.enqueue(MockResponse().setBody("""{"id":101}""")) // PUT description on prior 101
        server.enqueue(MockResponse().setBody("""{"id":31}""")) // POST OFV for new u-1

        submitter.submit("jwt", draftWith(listOf("Parus major")))

        // Incremental contract: prior rows for species NOT submitted in
        // this run are carried over, not wiped. The DB now holds Old A,
        // Old B, AND the freshly-uploaded Parus major.
        assertThat(inatDao.rows.map { it.taxonScientificName })
            .containsExactly("Old A", "Old B", "Parus major")
    }

    /**
     * Idempotency contract: a prior submission attempt may have created an iNat
     * observation but crashed before persisting the local row. On retry,
     * `submitOne` must detect the existing row (via `findForDraftAndSpecies`)
     * and skip the HTTP `createObservation` for that species — otherwise the
     * user accumulates duplicate observations on iNaturalist.
     *
     * Scenario: draft has two selected species; the first already has a row
     * in `inat_observations` from a prior attempt. The second is fresh. We
     * expect:
     *   - Exactly one full HTTP create-flow (resolveGenus + createObservation
     *     + sound + tag + 2 annotations + addIdentification) for the new
     *     species.
     *   - No HTTP traffic at all for the already-persisted species.
     *   - The cross-link PUT description still runs for both (we have two
     *     siblings), and POST /observation_field_values is skipped for the
     *     reused observation (no uuid available).
     *   - Final result Ok with both URLs; the reused row stays in the DB.
     */
    @Test fun `pre-existing inat row short-circuits createObservation on retry`() = runTest {
        // Pre-populate as if a prior attempt landed the Parus observation
        // on iNat but never persisted the draft status.
        val preExisting = InatObservationEntity(
            id = 0,
            draftId = "d-1",
            taxonScientificName = "Parus major",
            taxonInatId = 1L,
            observationId = 700L,
            observationUrl = "https://www.inaturalist.org/observations/700",
            createdAtUtcMs = 0L,
        )
        inatDao.rows += preExisting.copy(id = 1L)

        // Only Sylvia needs HTTP: resolveGenus + create + sound + tag +
        // 2 annotations + addIdentification = 7 calls.
        server.enqueue(MockResponse().setBody("""{"results":[{"id":2,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":701,"uuid":"u-B"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":556}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":10}"""))
        server.enqueue(MockResponse().setBody("""{"id":13}"""))
        server.enqueue(MockResponse().setBody("""{"id":14}"""))
        server.enqueue(MockResponse().setBody("""{"id":22}"""))
        // Cross-link PUTs (2 species → 2 PUTs). The reused observation has
        // a blank uuid so POST /observation_field_values is skipped for it;
        // the new Sylvia observation gets one POST.
        server.enqueue(MockResponse().setBody("""{"id":700}""")) // PUT description on reused 700
        server.enqueue(MockResponse().setBody("""{"id":701}""")) // PUT description on new 701
        server.enqueue(MockResponse().setBody("""{"id":32}""")) // POST OFV for u-B (Sylvia only)

        val result = submitter.submit("jwt", draftWith(listOf("Parus major", "Sylvia")))

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val ok = result as INatSubmitter.Result.Ok
        assertThat(ok.urls).hasSize(2)
        assertThat(ok.urls).containsExactly(
            "https://www.inaturalist.org/observations/700",
            "https://www.inaturalist.org/observations/701",
        )

        // Total HTTP: 7 (Sylvia create-flow) + 2 (description PUTs) + 1 (OFV
        // for Sylvia only — reused Parus has no uuid). The Parus species
        // contributes ZERO HTTP requests for its create-flow.
        assertThat(server.requestCount).isEqualTo(10)

        // First HTTP request must be Sylvia's resolveGenus — the reused
        // Parus observation skipped the HTTP path entirely.
        val firstReq = server.takeRequest()
        assertThat(firstReq.path).contains("/taxa")
        assertThat(firstReq.path).contains("Sylvia")

        // Persistence: the delete+insert wipe-and-replace runs in our
        // post-success transaction, so after submit() the DB has one row
        // per current observation. Both observation IDs must be present.
        assertThat(inatDao.rows).hasSize(2)
        assertThat(inatDao.rows.map { it.observationId }).containsExactly(700L, 701L)
        // Draft is marked UPLOADED.
        assertThat(dao.inserted.first().status).isEqualTo(DraftStatus.UPLOADED)
    }

    @Test fun `sourceAudioOverride is used when trimming uploaded sound`() = runTest {
        val original = createConstantWav(tmp.newFile("original.wav"), sampleValue = 0x0000, durationMs = 4_000L)
        val processed = createConstantWav(tmp.newFile("processed.wav"), sampleValue = 0x1234, durationMs = 1_000L)
        val baseDraft = draftWith(listOf("Parus major"))
        val draft = baseDraft.copy(
            draft = baseDraft.draft.copy(audioPath = original.absolutePath),
            detections = baseDraft.detections.map { it.copy(firstSeenMs = 0L, lastSeenMs = 500L) },
        )

        server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        server.enqueue(MockResponse().setBody("""{"id":21}"""))

        submitter.submit("jwt", draft, sourceAudioOverride = processed)

        server.takeRequest() // resolve genus
        server.takeRequest() // create observation
        val soundRequest = server.takeRequest() // upload sound
        val bodyBytes = soundRequest.body.readByteArray()
        assertThat(containsSequence(bodyBytes, byteArrayOf(0x34, 0x12, 0x34, 0x12, 0x34, 0x12))).isTrue()
    }

    @Test fun `empty selection short-circuits before any HTTP call`() = runTest {
        val result = submitter.submit("jwt", draftWith(emptyList()))
        assertThat(result).isInstanceOf(INatSubmitter.Result.Failure::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `blank token short-circuits`() = runTest {
        val result = submitter.submit("", draftWith(listOf("Parus major")))
        assertThat(result).isInstanceOf(INatSubmitter.Result.Failure::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `description contains per-source model lines when sources populated`() = runTest {
        val sources = "birdnet_v2_4=0.85:3:0:9000;perch_v2=0.62:1:3000:6000"
        val draft = draftWithSources("Parus major", sources)

        server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        server.enqueue(MockResponse().setBody("""{"id":21}"""))

        submitter.submit("jwt", draft)

        // Drain taxa request, then inspect the createObservation POST body.
        server.takeRequest() // GET /taxa
        val obsRequest = server.takeRequest() // POST /observations
        val body = obsRequest.body.readUtf8()

        assertThat(body).contains("Recorded with WildEar.")
        assertThat(body).contains("BirdNET v2.4 detected 3 window(s) between 0–9 s")
        assertThat(body).contains("Perch v2 detected 1 window(s) between 3–6 s")
    }

    @Test fun `cross-link description contains base description and sibling bullet list`() = runTest {
        val sources = "birdnet_v2_4=0.85:3:0:9000"
        val base = draftWith(listOf("Parus major", "Sylvia atricapilla"))
        val dets = base.detections.mapIndexed { i, d ->
            if (i == 0) d.copy(sources = sources) else d
        }
        val draft = base.copy(detections = dets)

        // Parus major
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":700,"uuid":"u-A"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":555}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        server.enqueue(MockResponse().setBody("""{"id":21}""")) // addIdentification Parus
        // Sylvia atricapilla
        server.enqueue(MockResponse().setBody("""{"results":[{"id":2,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":701,"uuid":"u-B"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":556}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":10}"""))
        server.enqueue(MockResponse().setBody("""{"id":13}"""))
        server.enqueue(MockResponse().setBody("""{"id":14}"""))
        server.enqueue(MockResponse().setBody("""{"id":22}""")) // addIdentification Sylvia
        // PUT cross-link descriptions
        server.enqueue(MockResponse().setBody("""{"id":700}"""))
        // POST observation_field_values for u-A (Linked Observation)
        server.enqueue(MockResponse().setBody("""{"id":31}"""))
        server.enqueue(MockResponse().setBody("""{"id":701}"""))
        // POST observation_field_values for u-B (Linked Observation)
        server.enqueue(MockResponse().setBody("""{"id":32}"""))

        submitter.submit("jwt", draft)

        repeat(14) { server.takeRequest() }

        val put1Body = server.takeRequest().body.readUtf8()
        assertThat(put1Body).contains("Recorded with WildEar.")
        assertThat(put1Body).contains("Sibling observations from the same recording:")
        assertThat(put1Body).contains(" - Sylvia atricapilla →")
        server.takeRequest() // POST /observation_field_values for u-A

        val put2Body = server.takeRequest().body.readUtf8()
        assertThat(put2Body).contains("Sibling observations from the same recording:")
        assertThat(put2Body).contains(" - Parus major →")
    }

    /**
     * Per-row early-persist contract (replaces the old "bulk wipe-and-replace
     * was atomic" test). When a single species' INSERT throws mid-submission,
     * the failure is contained to that species: prior rows for the draft stay
     * intact (no deleteForDraft anymore), and sibling species that were
     * successfully persisted before the throw remain in the DB.
     *
     * Uses an in-memory Room DB and a DAO decorator that throws on its second
     * `insert` call, simulating a partial-write failure on the second species.
     */
    @Test fun `per-row persist — insert failure on one species preserves siblings and prior rows`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Sound2iNatDb::class.java,
        ).allowMainThreadQueries().build()

        // The draft "d-1" must exist in the Room DB because inat_observations has
        // a foreign-key constraint on draftId → drafts(id).
        val draft = draftWith(listOf("Parus major", "Sylvia"))
        db.drafts().insert(draft.draft)

        // Pre-seed two "old" observation rows that must survive a partial-failure re-submit.
        val oldA = InatObservationEntity(
            draftId = draft.draft.id,
            taxonScientificName = "Old A",
            taxonInatId = 1L,
            observationId = 100L,
            observationUrl = "https://x/100",
            createdAtUtcMs = 0L,
        )
        val oldB = oldA.copy(
            taxonScientificName = "Old B",
            observationId = 101L,
            observationUrl = "https://x/101",
        )
        db.inatObservations().insert(oldA)
        db.inatObservations().insert(oldB)
        assertThat(db.inatObservations().listForDraft(draft.draft.id)).hasSize(2)

        // DAO wrapper that throws on the second submitter-driven insert call
        // (i.e. when persisting the Sylvia row).
        val throwingDao = object : InatObservationDao by db.inatObservations() {
            var callCount = 0
            override fun insert(row: InatObservationEntity): Long {
                if (++callCount >= 2) error("simulated insert failure")
                return db.inatObservations().insert(row)
            }
        }

        val throwingSubmitter = INatSubmitter(
            client = client,
            drafts = db.drafts(),
            inatObservations = throwingDao,
            tmpRoot = tmp.newFolder("cache2"),
            ioDispatcher = UnconfinedTestDispatcher(),
            nowMs = { 0L },
        )

        // Parus (success path): resolve + create + sound + spectrogram (mocked
        // to no-op) + tag + 2 annotations + identification = 7 HTTP responses.
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":700,"uuid":"u-A"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":555}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        server.enqueue(MockResponse().setBody("""{"id":21}"""))
        // Sylvia: only resolve + create + sound succeed; INSERT throws right
        // after, so the remaining best-effort responses are never consumed.
        server.enqueue(MockResponse().setBody("""{"results":[{"id":2,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":701,"uuid":"u-B"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":556}]}"""))

        val result = throwingSubmitter.submit("jwt", draft)

        // Parus succeeded, Sylvia failed → partial-success Ok with the draft
        // staying REVIEWED so the user can address the failure.
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val ok = result as INatSubmitter.Result.Ok
        // Prior Old A / Old B plus the freshly-persisted Parus row → 3 URLs.
        assertThat(ok.urls).hasSize(3)

        val remaining = db.inatObservations().listForDraft(draft.draft.id)
        // Three rows in total: prior Old A, prior Old B, freshly-persisted Parus.
        // Sylvia's row was never inserted (the throw aborted it).
        assertThat(remaining.map { it.taxonScientificName })
            .containsExactly("Old A", "Old B", "Parus major")
        // All three are COMPLETE (Old A/B by default; Parus via markComplete).
        assertThat(remaining.map { it.uploadStatus })
            .containsExactly(
                InatUploadStatus.COMPLETE,
                InatUploadStatus.COMPLETE,
                InatUploadStatus.COMPLETE,
            )
        // Draft stays REVIEWED with Sylvia's error captured for the UI banner.
        val savedDraft = db.drafts().getById(draft.draft.id)!!
        assertThat(savedDraft.status).isEqualTo(DraftStatus.REVIEWED)
        assertThat(savedDraft.inatLastError).contains("Sylvia")

        db.close()
    }

    /**
     * Verifies that withRetry retries HTTP 429 (rate-limited) and succeeds on
     * the second attempt. The sound upload is the most fragile step — if it
     * gets 429 once but succeeds on retry, the overall submission must succeed.
     *
     * Sequence: resolveGenus → createObservation → uploadSound(429) →
     *   uploadSound(200) → tag + 2 annotations + addIdentification = 8 requests.
     *
     * Note: withRetry calls delay() on 429; we create a local submitter that
     * shares testScheduler with runTest so virtual time advances correctly.
     */
    @Test fun `withRetry retries 429 on uploadSound and succeeds on second attempt`() = runTest {
        // Build a submitter that shares this test's scheduler so that
        // delay() in withRetry advances virtual time instead of conflicting.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val localSubmitter = INatSubmitter(
            client = client,
            drafts = dao,
            inatObservations = inatDao,
            tmpRoot = tmp.root,
            ioDispatcher = dispatcher,
            nowMs = { 0L },
        )

        // resolveGenus
        server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
        // createObservation
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        // uploadSound — first attempt: 429 rate-limited
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Rate limited"}"""))
        // uploadSound — second attempt: success
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        // updateObservationTags
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        // Two annotations (Alive + Organism)
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        // addIdentification
        server.enqueue(MockResponse().setBody("""{"id":21}"""))

        val result = localSubmitter.submit("jwt", draftWith(listOf("Parus major")))
        advanceUntilIdle()
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val ok = result as INatSubmitter.Result.Ok
        assertThat(ok.primaryUrl).isEqualTo("https://www.inaturalist.org/observations/900")
        assertThat(inatDao.rows).hasSize(1)
        assertThat(inatDao.rows.map { it.uploadStatus })
            .containsExactly(InatUploadStatus.COMPLETE)
        // resolve + create + uploadSound×2 + tag + 2 annotations + identification = 8
        assertThat(server.requestCount).isEqualTo(8)
        // Confirm both sound upload requests hit the correct endpoint.
        repeat(2) { server.takeRequest() } // resolve + create
        val firstSound = server.takeRequest()
        assertThat(firstSound.path).isEqualTo("/v2/observation_sounds")
        val secondSound = server.takeRequest()
        assertThat(secondSound.path).isEqualTo("/v2/observation_sounds")
    }

    /**
     * Verifies that withRetry exhausts all attempts on persistent 429 and
     * the overall submission returns Failure (draft stays REVIEWED).
     */
    @Test fun `withRetry exhausts all attempts on persistent 429 and returns Failure`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val localSubmitter = INatSubmitter(
            client = client,
            drafts = dao,
            inatObservations = inatDao,
            tmpRoot = tmp.root,
            ioDispatcher = dispatcher,
            nowMs = { 0L },
        )

        // resolveGenus
        server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
        // createObservation
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        // uploadSound — all 3 attempts: 429
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Rate limited"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Rate limited"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Rate limited"}"""))
        // deleteObservation (cleanup after sound upload failure)
        server.enqueue(MockResponse().setBody("""{"id":900}"""))

        val result = localSubmitter.submit("jwt", draftWith(listOf("Parus major")))
        advanceUntilIdle()
        assertThat(result).isInstanceOf(INatSubmitter.Result.Failure::class.java)
        assertThat(dao.inserted.first().status).isEqualTo(DraftStatus.REVIEWED)
        assertThat(inatDao.rows).isEmpty()
    }

    /**
     * Cancellation in the middle of a multi-step submission (e.g. user
     * navigates away during the post-INSERT side-effect phase) must leave
     * the just-INSERTed row in [InatUploadStatus.INCOMPLETE] so the UI can
     * surface a "delete-and-recreate" recovery banner.
     *
     * The throw is injected at the first `uploadObservationPhoto` call —
     * by that point in `submitOne` we've already INSERTed the row (with
     * INCOMPLETE) but not yet called `markComplete`. The best-effort
     * `runCatchingNonCancellation` wrapper around that call must rethrow
     * `CancellationException` so structured concurrency propagates it out
     * of `submit`; the row remains INCOMPLETE.
     */
    @Test fun `cancellation after first sound upload leaves an INCOMPLETE row`() = runTest {
        val cancellingClient = object : INaturalistClient(
            OkHttpClient(),
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            ioDispatcher = UnconfinedTestDispatcher(),
        ) {
            override suspend fun uploadObservationPhoto(
                token: String,
                observationId: Long,
                photoFile: File,
                mimeType: String,
            ): Long = throw kotlinx.coroutines.CancellationException("test cancel")
        }
        val cancellingSubmitter = INatSubmitter(
            client = cancellingClient,
            drafts = dao,
            inatObservations = inatDao,
            tmpRoot = tmp.newFolder("cache-cancel"),
            ioDispatcher = UnconfinedTestDispatcher(),
            nowMs = { 0L },
        )
        // resolveGenus
        server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
        // createObservation
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        // uploadSound — the primary clip lands successfully so the row gets INSERTed.
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))

        var caught: kotlinx.coroutines.CancellationException? = null
        try {
            cancellingSubmitter.submit(token = "jwt", draft = draftWith(listOf("Parus major")))
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = e
        }
        advanceUntilIdle()

        assertThat(caught).isNotNull()
        assertThat(inatDao.rows).hasSize(1)
        val row = inatDao.rows.single()
        assertThat(row.uploadStatus).isEqualTo(InatUploadStatus.INCOMPLETE)
        assertThat(row.observationId).isEqualTo(900L)
        assertThat(row.observationUrl).isNotEmpty()
    }

    private fun createConstantWav(
        dest: File,
        sampleValue: Int,
        sampleRate: Int = 48_000,
        durationMs: Long = 1_000L,
    ): File {
        val samples = ((sampleRate.toLong() * durationMs) / 1000L).toInt()
        val writer = WavWriter(dest, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        val shorts = ShortArray(samples) { sampleValue.toShort() }
        writer.writeShorts(shorts, 0, shorts.size)
        writer.close()
        return dest
    }

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
