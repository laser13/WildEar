package com.sound2inat.inat

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
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * Tests that habitat photos are uploaded to iNaturalist per the per-species opt-in flag.
 *
 * Uses a subclass of [INaturalistClient] that intercepts [INaturalistClient.uploadObservationPhoto]
 * calls so we can assert exactly which photos were uploaded and for which observations — without
 * going near the real HTTP multipart path (which is already tested in [INaturalistClientTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class INatSubmitterPhotoTest {

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
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Subclass of [INaturalistClient] that records every [uploadObservationPhoto] call
     * instead of making real HTTP requests, so tests can assert upload counts and args.
     *
     * All other methods delegate to the real implementation via [MockWebServer].
     */
    private inner class TrackingClient(
        http: OkHttpClient,
        baseUrl: String,
        ioDispatcher: CoroutineDispatcher,
    ) : INaturalistClient(http, baseUrl, ioDispatcher = ioDispatcher) {
        /** Collected (observationUuid, photoFile) pairs in call order. */
        val uploadedPhotos = mutableListOf<Pair<String, File>>()

        override suspend fun uploadObservationPhoto(
            token: String,
            observationUuid: String,
            photoFile: File,
        ): Long {
            uploadedPhotos += observationUuid to photoFile
            return uploadedPhotos.size.toLong()
        }
    }

    private fun makeTrackingClient(): TrackingClient = TrackingClient(
        OkHttpClient(),
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun makeSubmitter(
        client: INaturalistClient,
        draftDao: LocalFakeDraftDao,
        inatDao: LocalFakeInatDao,
        cacheFolder: File,
    ) = INatSubmitter(
        client = client,
        drafts = draftDao,
        inatObservations = inatDao,
        tmpRoot = cacheFolder,
        ioDispatcher = UnconfinedTestDispatcher(),
        nowMs = { 0L },
    )

    /** Builds a minimal real WAV file and returns a [DraftWithDetections] for [taxon]. */
    private fun draftWith(taxon: String, draftId: String = "d-photo-1"): DraftWithDetections {
        val wav = tmp.newFile("$draftId-clip.wav")
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
        val det = DetectionEntity(
            id = 1L,
            draftId = draftId,
            taxonScientificName = taxon,
            taxonCommonName = null,
            maxConfidence = 0.9f,
            detectedWindows = 5,
            firstSeenMs = 500L,
            lastSeenMs = 2_500L,
            isSelectedByUser = true,
        )
        return DraftWithDetections(draft, listOf(det))
    }

    /**
     * Enqueues the minimum set of mock responses needed for one successful species submission:
     * resolveGenus → createObservation → uploadSound → 2 annotations → addIdentification.
     */
    private fun enqueueSuccessfulSubmit(uuid: String = "u-photo-1") {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"$uuid"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":11}""")) // Alive annotation
        server.enqueue(MockResponse().setBody("""{"id":12}""")) // Organism annotation
        server.enqueue(MockResponse().setBody("""{"id":21}""")) // addIdentification
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `photo uploaded for species where includeHabitatPhoto is true`() = runTest {
        val taxon = "Parus major"
        val trackingClient = makeTrackingClient()
        val draftDao = LocalFakeDraftDao()
        val submitter = makeSubmitter(trackingClient, draftDao, LocalFakeInatDao(), tmp.newFolder("cache-1"))

        enqueueSuccessfulSubmit(uuid = "u-photo-1")

        val photoFile = tmp.newFile("habitat1.jpg").apply { writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(taxon, draftId = "d-1"),
            habitatPhotos = listOf(photoFile),
            includeHabitatPhotoByTaxon = mapOf(taxon to true),
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(trackingClient.uploadedPhotos).hasSize(1)
        assertThat(trackingClient.uploadedPhotos.first().first).isEqualTo("u-photo-1")
        assertThat(trackingClient.uploadedPhotos.first().second).isEqualTo(photoFile)
    }

    @Test
    fun `photo NOT uploaded when includeHabitatPhoto is false`() = runTest {
        val taxon = "Parus major"
        val trackingClient = makeTrackingClient()
        val submitter = makeSubmitter(trackingClient, LocalFakeDraftDao(), LocalFakeInatDao(), tmp.newFolder("cache-2"))

        enqueueSuccessfulSubmit()

        val photoFile = tmp.newFile("habitat2.jpg").apply { writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(taxon, draftId = "d-2"),
            habitatPhotos = listOf(photoFile),
            includeHabitatPhotoByTaxon = mapOf(taxon to false),
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(trackingClient.uploadedPhotos).isEmpty()
    }

    @Test
    fun `photo NOT uploaded when taxon absent from includeHabitatPhotoByTaxon map`() = runTest {
        val taxon = "Parus major"
        val trackingClient = makeTrackingClient()
        val submitter = makeSubmitter(trackingClient, LocalFakeDraftDao(), LocalFakeInatDao(), tmp.newFolder("cache-3"))

        enqueueSuccessfulSubmit()

        val photoFile = tmp.newFile("habitat3.jpg").apply { writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(taxon, draftId = "d-3"),
            habitatPhotos = listOf(photoFile),
            includeHabitatPhotoByTaxon = emptyMap(),
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(trackingClient.uploadedPhotos).isEmpty()
    }

    @Test
    fun `multiple photos uploaded when flag is true`() = runTest {
        val taxon = "Parus major"
        val trackingClient = makeTrackingClient()
        val submitter = makeSubmitter(trackingClient, LocalFakeDraftDao(), LocalFakeInatDao(), tmp.newFolder("cache-4"))

        enqueueSuccessfulSubmit()

        val photo1 = tmp.newFile("habitat4a.jpg").apply { writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        val photo2 = tmp.newFile("habitat4b.jpg").apply { writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(taxon, draftId = "d-4"),
            habitatPhotos = listOf(photo1, photo2),
            includeHabitatPhotoByTaxon = mapOf(taxon to true),
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(trackingClient.uploadedPhotos).hasSize(2)
    }
}

// -------------------------------------------------------------------------
// Local fake DAOs (package-private so the file above can reference them).
// The private FakeDraftDao / FakeInatDao in INatSubmitterTest.kt are not
// accessible here — we keep these as minimal duplicates.
// -------------------------------------------------------------------------

private class LocalFakeDraftDao : DraftDao {
    val inserted: MutableList<DraftEntity> = mutableListOf()
    override fun insert(d: DraftEntity) { inserted += d }
    override fun update(d: DraftEntity) {
        val i = inserted.indexOfFirst { it.id == d.id }
        if (i >= 0) inserted[i] = d else inserted += d
    }
    override fun delete(d: DraftEntity) { inserted.removeAll { it.id == d.id } }
    override fun getById(id: String): DraftEntity? = inserted.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<DraftEntity>> = flowOf(inserted.toList())
    override fun deleteById(id: String): Int = if (inserted.removeAll { it.id == id }) 1 else 0
}

private class LocalFakeInatDao : InatObservationDao {
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
    override fun observeCountsByDraft(): Flow<List<DraftObservationCount>> =
        flowOf(
            rows.groupBy { it.draftId }
                .map { (id, list) -> DraftObservationCount(id, list.size) },
        )
}
