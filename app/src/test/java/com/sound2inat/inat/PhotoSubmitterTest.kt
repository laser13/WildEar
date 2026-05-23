package com.sound2inat.inat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoDraftStatus
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.PhotoUploadStatus
import com.sound2inat.storage.Sound2iNatDb
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

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class PhotoSubmitterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: Sound2iNatDb
    private lateinit var repo: PhotoDraftRepository
    private lateinit var submitter: PhotoSubmitter
    private lateinit var fileStore: PhotoObservationFileStore
    private var nextId = 0

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Sound2iNatDb::class.java,
        ).allowMainThreadQueries().build()
        fileStore = PhotoObservationFileStore(tmp.root)
        repo = PhotoDraftRepository(
            draftDao = db.photoDrafts(),
            imageDao = db.photoDraftImages(),
            fileStore = fileStore,
            nowMs = { 10L },
            idFactory = { "id${++nextId}" },
            ioDispatcher = UnconfinedTestDispatcher(),
            runInTransaction = { block -> db.runInTransaction(block) },
        )
        submitter = PhotoSubmitter(
            client = INaturalistClient(
                OkHttpClient(),
                baseUrl = server.url("/v1").toString().removeSuffix("/"),
                ioDispatcher = UnconfinedTestDispatcher(),
            ),
            repo = repo,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun `uploading a photo draft creates one observation and uploads every image`() = runTest {
        val draftId = draftWithImages(2)
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        server.enqueue(MockResponse().setBody("""{"id":1,"observation_id":900}"""))
        server.enqueue(MockResponse().setBody("""{"id":2,"observation_id":900}"""))
        server.enqueue(MockResponse().setBody("""{}"""))

        val result = submitter.submit("jwt", draftId)

        assertThat(result).isInstanceOf(PhotoSubmitResult.Ok::class.java)
        val saved = db.photoDrafts().getById(draftId)!!
        assertThat(saved.inatObservationId).isEqualTo(900L)
        assertThat(saved.inatObservationUuid).isEqualTo("u-1")
        assertThat(saved.inatObservationUrl).isEqualTo("https://www.inaturalist.org/observations/900")
        assertThat(saved.uploadStatus).isEqualTo(PhotoUploadStatus.COMPLETE)
        assertThat(saved.status).isEqualTo(PhotoDraftStatus.UPLOADED)
        assertThat(server.takeRequest().path).isEqualTo("/v1/observations")
        assertThat(server.takeRequest().path).isEqualTo("/v1/observation_photos")
        assertThat(server.takeRequest().path).isEqualTo("/v1/observation_photos")
        val tagRequest = server.takeRequest()
        assertThat(tagRequest.path).isEqualTo("/v2/observations/u-1")
        assertThat(tagRequest.body.readUtf8()).contains("WildEar")
    }

    @Test
    fun `blank token fails before network`() = runTest {
        val draftId = draftWithImages(1)

        val result = submitter.submit("", draftId)

        assertThat(result).isInstanceOf(PhotoSubmitResult.Failure::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `primary photo upload failure rolls back observation and leaves no INCOMPLETE row`() = runTest {
        val draftId = draftWithImages(1)
        // createObservation
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        // first photo upload — fails
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        // delete observation cleanup
        server.enqueue(MockResponse().setResponseCode(204))

        val result = submitter.submit("jwt", draftId)

        assertThat(result).isInstanceOf(PhotoSubmitResult.Failure::class.java)
        val saved = db.photoDrafts().getById(draftId)!!
        assertThat(saved.inatLastError).contains("First photo upload failed")
        assertThat(saved.uploadStatus).isNull()
        assertThat(saved.inatObservationId).isNull()
        assertThat(server.takeRequest().path).isEqualTo("/v1/observations")
        assertThat(server.takeRequest().path).isEqualTo("/v1/observation_photos")
        assertThat(server.takeRequest().path).isEqualTo("/v1/observations/900")
    }

    @Test
    fun `incomplete row is refused on resubmit until banner-driven recreate`() = runTest {
        val draftId = draftWithImages(1)
        // Seed: simulate a previous half-finished upload.
        repo.markIncompleteUpload(
            draftId = draftId,
            observationId = 42L,
            observationUuid = "u-prev",
            observationUrl = "https://www.inaturalist.org/observations/42",
        )

        val result = submitter.submit("jwt", draftId)

        assertThat(result).isInstanceOf(PhotoSubmitResult.Failure::class.java)
        assertThat((result as PhotoSubmitResult.Failure).message).contains("incomplete upload")
        assertThat(server.requestCount).isEqualTo(0)
        // Row still INCOMPLETE — recovery action hasn't been taken yet.
        assertThat(db.photoDrafts().getById(draftId)!!.uploadStatus).isEqualTo(PhotoUploadStatus.INCOMPLETE)
    }

    @Test
    fun `cancellation after first photo upload leaves an INCOMPLETE row`() = runTest {
        val draftId = draftWithImages(2)
        // createObservation lands; first photo upload lands; second photo cancels.
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
        var photoCount = 0
        val cancellingClient = object : INaturalistClient(
            OkHttpClient(),
            baseUrl = server.url("/v1").toString().removeSuffix("/"),
            ioDispatcher = UnconfinedTestDispatcher(),
        ) {
            override suspend fun uploadObservationPhoto(
                token: String,
                observationId: Long,
                photoFile: File,
                mimeType: String,
            ): Long {
                photoCount++
                if (photoCount >= 2) {
                    throw kotlinx.coroutines.CancellationException("test cancel")
                }
                return 1L
            }
        }
        val cancellingSubmitter = PhotoSubmitter(
            client = cancellingClient,
            repo = repo,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        var caught: kotlinx.coroutines.CancellationException? = null
        try {
            cancellingSubmitter.submit("jwt", draftId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = e
        }

        assertThat(caught).isNotNull()
        val saved = db.photoDrafts().getById(draftId)!!
        // markPhotoUploadComplete must NOT have run — uploadStatus stays
        // INCOMPLETE and the draft's status stays whatever it was pre-submit.
        assertThat(saved.uploadStatus).isEqualTo(PhotoUploadStatus.INCOMPLETE)
        assertThat(saved.inatObservationId).isEqualTo(900L)
        assertThat(saved.status).isNotEqualTo(PhotoDraftStatus.UPLOADED)
    }

    private suspend fun draftWithImages(count: Int): String {
        val draftId = repo.createDraft(1L, latitude = 35.1, longitude = 33.3, accuracyMeters = 5f)
        repeat(count) { idx ->
            val file = fileStore.newPhotoFile(draftId, "p$idx").apply { writeText("jpeg") }
            repo.addImage(
                draftId = draftId,
                photoId = "p$idx",
                imageFile = file,
                takenAtUtcMs = idx.toLong(),
                width = 100,
                height = 100,
            )
        }
        return draftId
    }
}
