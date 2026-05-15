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
        val photoUploadRequestCounts = mutableListOf<Int>()
        var failPhotoUploads: Boolean = false

        override suspend fun uploadObservationPhoto(
            token: String,
            observationUuid: String,
            photoFile: File,
            mimeType: String,
        ): Long {
            photoUploadRequestCounts += server.requestCount
            uploadedPhotos += observationUuid to photoFile
            if (failPhotoUploads) throw RuntimeException("spectrogram upload failed")
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

    /** Builds a minimal real WAV file and returns a [DraftWithDetections] for [selected]. */
    private fun draftWith(selected: List<String>, draftId: String = "d-photo-1"): DraftWithDetections {
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
        val detections = selected.mapIndexed { index, taxon ->
            DetectionEntity(
                id = (index + 1).toLong(),
                draftId = draftId,
                taxonScientificName = taxon,
                taxonCommonName = null,
                maxConfidence = 0.9f - index * 0.1f,
                detectedWindows = 5,
                firstSeenMs = 500L + index * 1000L,
                lastSeenMs = 2_500L + index * 1000L,
                isSelectedByUser = true,
            )
        }
        return DraftWithDetections(draft, detections)
    }

    private fun draftWith(taxon: String, draftId: String = "d-photo-1"): DraftWithDetections =
        draftWith(listOf(taxon), draftId)

    /**
     * Enqueues the minimum set of mock responses needed for one successful species submission:
     * resolveGenus → createObservation → uploadSound → updateObservationTags → 2 annotations → addIdentification.
     */
    private fun enqueueSuccessfulSubmit(uuid: String = "u-photo-1", observationId: Long = 900L) {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":$observationId,"uuid":"$uuid"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}""")) // tag update
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

    @Test
    fun `spectrogram photo uploads once after the first successful sound upload`() = runTest {
        val trackingClient = makeTrackingClient()
        val draftDao = LocalFakeDraftDao()
        val submitter = makeSubmitter(trackingClient, draftDao, LocalFakeInatDao(), tmp.newFolder("cache-5"))

        enqueueSuccessfulSubmit(uuid = "u-spec-1", observationId = 910L)
        enqueueSuccessfulSubmit(uuid = "u-spec-2", observationId = 911L)
        server.enqueue(MockResponse().setBody("""{"id":700}"""))
        server.enqueue(MockResponse().setBody("""{"id":701}"""))
        server.enqueue(MockResponse().setBody("""{"id":702}"""))
        server.enqueue(MockResponse().setBody("""{"id":703}"""))

        val spectrogram = tmp.newFile("spectrogram.png").apply {
            writeBytes(ByteArray(128) { 0x7F.toByte() })
        }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(listOf("Parus major", "Sylvia"), draftId = "d-spec-1"),
            spectrogramPhoto = spectrogram,
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(trackingClient.uploadedPhotos).hasSize(1)
        assertThat(trackingClient.uploadedPhotos.first().first).isEqualTo("u-spec-1")
        assertThat(trackingClient.uploadedPhotos.first().second).isEqualTo(spectrogram)
        assertThat(trackingClient.photoUploadRequestCounts).containsExactly(3)
    }

    @Test
    fun `spectrogram photo failure does not fail overall submission`() = runTest {
        val trackingClient = makeTrackingClient().apply {
            failPhotoUploads = true
        }
        val submitter = makeSubmitter(trackingClient, LocalFakeDraftDao(), LocalFakeInatDao(), tmp.newFolder("cache-6"))

        enqueueSuccessfulSubmit(uuid = "u-spec-fail-1", observationId = 920L)
        enqueueSuccessfulSubmit(uuid = "u-spec-fail-2", observationId = 921L)
        server.enqueue(MockResponse().setBody("""{"id":704}"""))
        server.enqueue(MockResponse().setBody("""{"id":705}"""))
        server.enqueue(MockResponse().setBody("""{"id":706}"""))
        server.enqueue(MockResponse().setBody("""{"id":707}"""))

        val spectrogram = tmp.newFile("spectrogram-fail.png").apply {
            writeBytes(ByteArray(64) { 0x5A.toByte() })
        }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(listOf("Parus major", "Sylvia"), draftId = "d-spec-2"),
            spectrogramPhoto = spectrogram,
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(trackingClient.uploadedPhotos).hasSize(1)
        assertThat(trackingClient.uploadedPhotos.first().first).isEqualTo("u-spec-fail-1")
        assertThat(trackingClient.uploadedPhotos.first().second).isEqualTo(spectrogram)
    }

    @Test
    fun `spectrogram photo failure is not retried for later species in same batch`() = runTest {
        val trackingClient = makeTrackingClient().apply {
            failPhotoUploads = true
        }
        val submitter = makeSubmitter(trackingClient, LocalFakeDraftDao(), LocalFakeInatDao(), tmp.newFolder("cache-7"))

        enqueueSuccessfulSubmit(uuid = "u-spec-batch-1", observationId = 930L)
        enqueueSuccessfulSubmit(uuid = "u-spec-batch-2", observationId = 931L)
        server.enqueue(MockResponse().setBody("""{"id":708}"""))
        server.enqueue(MockResponse().setBody("""{"id":709}"""))
        server.enqueue(MockResponse().setBody("""{"id":710}"""))
        server.enqueue(MockResponse().setBody("""{"id":711}"""))

        val spectrogram = tmp.newFile("spectrogram-batch.png").apply {
            writeBytes(ByteArray(64) { 0x33.toByte() })
        }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(listOf("Parus major", "Sylvia"), draftId = "d-spec-3"),
            spectrogramPhoto = spectrogram,
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(trackingClient.uploadedPhotos).hasSize(1)
        assertThat(trackingClient.uploadedPhotos.first().first).isEqualTo("u-spec-batch-1")
        assertThat(trackingClient.photoUploadRequestCounts).containsExactly(3)
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
    override fun findForDraftAndSpecies(draftId: String, species: String): InatObservationEntity? =
        rows.firstOrNull { it.draftId == draftId && it.taxonScientificName == species }
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
