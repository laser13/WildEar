package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.DraftWithDetections
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
        /** Collected (observationId, photoFile) pairs in call order. */
        val uploadedPhotos = mutableListOf<Pair<Long, File>>()
        val photoUploadRequestCounts = mutableListOf<Int>()
        var failPhotoUploads: Boolean = false

        override suspend fun uploadObservationPhoto(
            token: String,
            observationId: Long,
            photoFile: File,
            mimeType: String,
        ): Long {
            photoUploadRequestCounts += server.requestCount
            uploadedPhotos += observationId to photoFile
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
        val draftDao = InMemoryDraftDao()
        val submitter = makeSubmitter(trackingClient, draftDao, InMemoryInatObservationDao(), tmp.newFolder("cache-1"))

        enqueueSuccessfulSubmit(uuid = "u-photo-1")

        val photoFile = tmp.newFile("habitat1.jpg").apply { writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(taxon, draftId = "d-1"),
            habitatPhotos = listOf(photoFile),
            includeHabitatPhotoByTaxon = mapOf(taxon to true),
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        // Submitter now uploads one per-clip spectrogram + the habitat photo.
        val habitatUploads = trackingClient.uploadedPhotos.filter { it.second == photoFile }
        assertThat(habitatUploads).hasSize(1)
        assertThat(habitatUploads.first().first).isEqualTo(900L)
    }

    @Test
    fun `habitat photo NOT uploaded when includeHabitatPhoto is false`() = runTest {
        val taxon = "Parus major"
        val trackingClient = makeTrackingClient()
        val submitter = makeSubmitter(
            trackingClient,
            InMemoryDraftDao(),
            InMemoryInatObservationDao(),
            tmp.newFolder("cache-2"),
        )

        enqueueSuccessfulSubmit()

        val photoFile = tmp.newFile("habitat2.jpg").apply { writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(taxon, draftId = "d-2"),
            habitatPhotos = listOf(photoFile),
            includeHabitatPhotoByTaxon = mapOf(taxon to false),
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val habitatUploads = trackingClient.uploadedPhotos.filter { it.second == photoFile }
        assertThat(habitatUploads).isEmpty()
    }

    @Test
    fun `habitat photo NOT uploaded when taxon absent from includeHabitatPhotoByTaxon map`() = runTest {
        val taxon = "Parus major"
        val trackingClient = makeTrackingClient()
        val submitter = makeSubmitter(
            trackingClient,
            InMemoryDraftDao(),
            InMemoryInatObservationDao(),
            tmp.newFolder("cache-3"),
        )

        enqueueSuccessfulSubmit()

        val photoFile = tmp.newFile("habitat3.jpg").apply { writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(taxon, draftId = "d-3"),
            habitatPhotos = listOf(photoFile),
            includeHabitatPhotoByTaxon = emptyMap(),
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        val habitatUploads = trackingClient.uploadedPhotos.filter { it.second == photoFile }
        assertThat(habitatUploads).isEmpty()
    }

    @Test
    fun `multiple habitat photos uploaded when flag is true`() = runTest {
        val taxon = "Parus major"
        val trackingClient = makeTrackingClient()
        val submitter = makeSubmitter(
            trackingClient,
            InMemoryDraftDao(),
            InMemoryInatObservationDao(),
            tmp.newFolder("cache-4"),
        )

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
        val habitatUploads = trackingClient.uploadedPhotos
            .filter { it.second == photo1 || it.second == photo2 }
        assertThat(habitatUploads).hasSize(2)
    }

    @Test
    fun `per-clip spectrogram photo uploads for every species in the batch`() = runTest {
        val trackingClient = makeTrackingClient()
        val draftDao = InMemoryDraftDao()
        val submitter = makeSubmitter(trackingClient, draftDao, InMemoryInatObservationDao(), tmp.newFolder("cache-5"))

        enqueueSuccessfulSubmit(uuid = "u-spec-1", observationId = 910L)
        enqueueSuccessfulSubmit(uuid = "u-spec-2", observationId = 911L)

        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(listOf("Parus major", "Sylvia"), draftId = "d-spec-1"),
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        // One spectrogram per clip, one clip per species (legacy fragmentRanges
        // empty -> single clip). Two species => two photo uploads.
        assertThat(trackingClient.uploadedPhotos).hasSize(2)
        val perObservationIds = trackingClient.uploadedPhotos.map { it.first }.toSet()
        assertThat(perObservationIds).containsExactly(910L, 911L)
    }

    @Test
    fun `per-clip spectrogram failure does not fail overall submission`() = runTest {
        val trackingClient = makeTrackingClient().apply {
            failPhotoUploads = true
        }
        val submitter = makeSubmitter(
            trackingClient,
            InMemoryDraftDao(),
            InMemoryInatObservationDao(),
            tmp.newFolder("cache-6"),
        )

        enqueueSuccessfulSubmit(uuid = "u-spec-fail-1", observationId = 920L)
        enqueueSuccessfulSubmit(uuid = "u-spec-fail-2", observationId = 921L)

        val result = submitter.submit(
            token = "jwt",
            draft = draftWith(listOf("Parus major", "Sylvia"), draftId = "d-spec-2"),
        )

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        // Per-clip spectrogram uploads are best-effort; even when every photo
        // upload throws, the submission completes successfully.
        assertThat(trackingClient.uploadedPhotos).hasSize(2)
        val perObservationIds = trackingClient.uploadedPhotos.map { it.first }.toSet()
        assertThat(perObservationIds).containsExactly(920L, 921L)
    }
}
