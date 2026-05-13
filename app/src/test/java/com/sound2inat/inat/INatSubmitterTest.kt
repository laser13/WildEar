package com.sound2inat.inat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftObservationCount
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.DraftWithDetections
import com.sound2inat.storage.InatObservationDao
import com.sound2inat.storage.InatObservationEntity
import com.sound2inat.storage.Sound2iNatDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class INatSubmitterTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: INaturalistClient
    private lateinit var dao: FakeDraftDao
    private lateinit var inatDao: FakeInatDao
    private lateinit var submitter: INatSubmitter

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = INaturalistClient(
            OkHttpClient(),
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        dao = FakeDraftDao()
        inatDao = FakeInatDao()
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
        // The Sylvia failure is reported in inatLastError but the draft still
        // moved to UPLOADED because Parus succeeded.
        assertThat(dao.inserted.first().status).isEqualTo(DraftStatus.UPLOADED)
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

    @Test fun `re-submit wipes prior inat rows for the same draft`() = runTest {
        // Pre-populate as if a prior submit landed two observations.
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

        submitter.submit("jwt", draftWith(listOf("Parus major")))

        // Old rows wiped, only the new one remains.
        assertThat(inatDao.rows).hasSize(1)
        assertThat(inatDao.rows.first().taxonScientificName).isEqualTo("Parus major")
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
        assertThat(body).contains("Perch v2 (Google) detected 1 window(s) between 3–6 s")
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
     * Verifies that when [Sound2iNatDb] is injected, the delete+insertAll is
     * wrapped in a single transaction: if insert throws mid-way the prior rows
     * must be rolled back and preserved.
     *
     * Uses an in-memory Room DB and a DAO decorator that throws on the second
     * insert, simulating a partial-write failure.
     */
    @Test fun `persistObservations is atomic — insert failure preserves prior rows`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Sound2iNatDb::class.java,
        ).allowMainThreadQueries().build()

        // The draft "d-1" must exist in the Room DB because inat_observations has
        // a foreign-key constraint on draftId → drafts(id).
        val draft = draftWith(listOf("Parus major", "Sylvia"))
        db.drafts().insert(draft.draft)

        // Pre-seed two "old" observation rows that must survive a failed re-submit.
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

        // DAO wrapper that throws on the second insert call.
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
            db = db,
        )

        // Two HTTP species so two inserts are attempted.
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":700,"uuid":"u-A"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":555}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}"""))
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        server.enqueue(MockResponse().setBody("""{"id":21}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":2,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":701,"uuid":"u-B"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":556}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":10}"""))
        server.enqueue(MockResponse().setBody("""{"id":13}"""))
        server.enqueue(MockResponse().setBody("""{"id":14}"""))
        server.enqueue(MockResponse().setBody("""{"id":22}"""))

        val result = runCatching {
            throwingSubmitter.submit("jwt", draft)
        }

        // The transaction must roll back, preserving the original two rows.
        assertThat(result.isFailure).isTrue()
        val remaining = db.inatObservations().listForDraft(draft.draft.id)
        assertThat(remaining).hasSize(2)
        assertThat(remaining.map { it.taxonScientificName }).containsExactly("Old A", "Old B")

        db.close()
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

private class FakeDraftDao : DraftDao {
    val inserted: MutableList<DraftEntity> = mutableListOf()
    override fun insert(d: DraftEntity) { inserted += d }
    override fun update(d: DraftEntity) {
        val i = inserted.indexOfFirst { it.id == d.id }
        if (i >= 0) inserted[i] = d
    }
    override fun delete(d: DraftEntity) { inserted.removeAll { it.id == d.id } }
    override fun getById(id: String): DraftEntity? = inserted.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<DraftEntity>> = flowOf(inserted.toList())
    override fun deleteById(id: String): Int = if (inserted.removeAll { it.id == id }) 1 else 0
    override fun updateStatusConditional(
        id: String,
        newStatus: DraftStatus,
        expectedStatus: DraftStatus,
    ): Int {
        val i = inserted.indexOfFirst { it.id == id && it.status == expectedStatus }
        if (i < 0) return 0
        inserted[i] = inserted[i].copy(status = newStatus)
        return 1
    }
}

private class FakeInatDao : InatObservationDao {
    val rows: MutableList<InatObservationEntity> = mutableListOf()
    private var nextId = 1L
    override fun insert(row: InatObservationEntity): Long {
        val id = nextId++
        rows += row.copy(id = id)
        return id
    }
    override fun listForDraft(draftId: String): List<InatObservationEntity> =
        rows.filter { it.draftId == draftId }
    override fun observeForDraft(draftId: String): Flow<List<InatObservationEntity>> =
        flowOf(listForDraft(draftId))
    override fun deleteForDraft(draftId: String): Int =
        if (rows.removeAll { it.draftId == draftId }) 1 else 0
    override fun observeCountsByDraft(): Flow<List<com.sound2inat.storage.DraftObservationCount>> =
        flowOf(
            rows.groupBy { it.draftId }
                .map { (id, list) -> com.sound2inat.storage.DraftObservationCount(id, list.size) },
        )
}
