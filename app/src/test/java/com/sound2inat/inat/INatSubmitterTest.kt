package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.DraftWithDetections
import com.sound2inat.storage.InatObservationDao
import com.sound2inat.storage.InatObservationEntity
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

    @Test fun `single species creates one observation and persists row`() = runTest {
        // resolveTaxon
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}""",
            ),
        )
        // createObservation
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        // uploadSound (v2 wraps in { results: [...] })
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        // Two best-effort annotations after sound upload (Alive + Organism).
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))

        val result = submitter.submit("jwt", draftWith(listOf("Parus major")))
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val ok = result as INatSubmitter.Result.Ok
        assertThat(ok.urls).hasSize(1)
        assertThat(ok.primaryUrl).isEqualTo("https://www.inaturalist.org/observations/u-1")

        // Persistence: draft marked UPLOADED + one row in inat_observations.
        assertThat(dao.inserted.first().status).isEqualTo(DraftStatus.UPLOADED)
        assertThat(inatDao.rows).hasSize(1)
        assertThat(inatDao.rows.first().taxonScientificName).isEqualTo("Parus major")
        assertThat(inatDao.rows.first().observationId).isEqualTo(900L)

        // resolve + create + sound + 2 annotations = 5; no PUT for a single obs.
        assertThat(server.requestCount).isEqualTo(5)
    }

    @Test fun `multi-species creates separate observations and PUT cross-links`() = runTest {
        // 1.  resolve Parus
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"iconic_taxon_name":"Aves"}]}"""))
        // 2.  create observation for Parus
        server.enqueue(MockResponse().setBody("""{"id":700,"uuid":"u-A"}"""))
        // 3.  upload sound for Parus (v2 wraps)
        server.enqueue(MockResponse().setBody("""{"results":[{"id":555}]}"""))
        // 4-5. annotations Alive + Organism for Parus.
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
        // 6.  resolve Sylvia
        server.enqueue(MockResponse().setBody("""{"results":[{"id":2,"iconic_taxon_name":"Aves"}]}"""))
        // 7.  create observation for Sylvia
        server.enqueue(MockResponse().setBody("""{"id":701,"uuid":"u-B"}"""))
        // 8.  upload sound for Sylvia (v2 wraps)
        server.enqueue(MockResponse().setBody("""{"results":[{"id":556}]}"""))
        // 9-10. annotations Alive + Organism for Sylvia.
        server.enqueue(MockResponse().setBody("""{"id":13}"""))
        server.enqueue(MockResponse().setBody("""{"id":14}"""))
        // 11. PUT description on observation 700
        server.enqueue(MockResponse().setBody("""{"id":700}"""))
        // 12. PUT description on observation 701
        server.enqueue(MockResponse().setBody("""{"id":701}"""))

        val result = submitter.submit("jwt", draftWith(listOf("Parus major", "Sylvia")))
        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val ok = result as INatSubmitter.Result.Ok
        assertThat(ok.urls).hasSize(2)
        assertThat(inatDao.rows).hasSize(2)

        // Drain the first ten requests (resolve/create/upload + 2 annotations × 2).
        repeat(10) { server.takeRequest() }
        val put1 = server.takeRequest()
        assertThat(put1.method).isEqualTo("PUT")
        assertThat(put1.path).endsWith("/observations/700")
        assertThat(put1.body.readUtf8()).contains("u-B")
        val put2 = server.takeRequest()
        assertThat(put2.method).isEqualTo("PUT")
        assertThat(put2.path).endsWith("/observations/701")
        assertThat(put2.body.readUtf8()).contains("u-A")
    }

    @Test fun `unresolved species is skipped but others succeed`() = runTest {
        // Parus resolves, Sylvia returns empty results.
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":700,"uuid":"u-A"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":555}]}"""))
        // Annotations Alive + Organism for Parus.
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))
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
        // Annotations Alive + Organism after the (single) sound upload.
        server.enqueue(MockResponse().setBody("""{"id":11}"""))
        server.enqueue(MockResponse().setBody("""{"id":12}"""))

        submitter.submit("jwt", draftWith(listOf("Parus major")))

        // Old rows wiped, only the new one remains.
        assertThat(inatDao.rows).hasSize(1)
        assertThat(inatDao.rows.first().taxonScientificName).isEqualTo("Parus major")
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
