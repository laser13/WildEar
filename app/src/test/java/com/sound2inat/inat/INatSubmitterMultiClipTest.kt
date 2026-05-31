package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.audio.WavWriter
import com.sound2inat.inference.FragmentRange
import com.sound2inat.inference.FragmentRanges
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.DraftWithDetections
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
 * Verifies that a single species with three temporally-distinct clusters of
 * fragment ranges produces three independent sound + spectrogram uploads on
 * the same iNat observation. This is the C-task behaviour test for
 * [ClipPlanner] + [renderClipSpectrogramPng] wired through [INatSubmitter].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class INatSubmitterMultiClipTest {

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

    /**
     * Subclass of [INaturalistClient] that records every [uploadSound] /
     * [uploadObservationPhoto] call as in-memory entries instead of going
     * through the real HTTP multipart machinery. Other endpoints delegate
     * to the real implementation (and thus to [MockWebServer]).
     */
    private inner class TrackingClient(
        http: OkHttpClient,
        baseUrl: String,
        ioDispatcher: CoroutineDispatcher,
    ) : INaturalistClient(http, baseUrl, ioDispatcher = ioDispatcher) {
        val soundUploads = mutableListOf<Pair<String, File>>()
        val photoUploads = mutableListOf<Pair<Long, File>>()

        /**
         * PNG widths captured at upload time. [INatSubmitter] wipes `cropDir`
         * in its `finally` block, so the PNG files are gone by the time the
         * test asserts — we must record the width while the file is live.
         */
        val photoWidths = mutableListOf<Int>()

        override suspend fun uploadSound(
            token: String,
            observationUuid: String,
            audioFile: File,
        ): Long {
            soundUploads += observationUuid to audioFile
            return soundUploads.size.toLong()
        }

        override suspend fun uploadObservationPhoto(
            token: String,
            observationId: Long,
            photoFile: File,
            mimeType: String,
        ): Long {
            photoUploads += observationId to photoFile
            photoWidths += readPngWidth(photoFile)
            return photoUploads.size.toLong()
        }
    }

    private fun makeClient(): TrackingClient = TrackingClient(
        OkHttpClient(),
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /** Writes a real mono 16-bit PCM WAV of [durationS] seconds at 16 kHz (smaller fixtures). */
    private fun makeSilentWav(name: String, durationS: Int, sampleRate: Int = 16_000): File {
        val wav = File(tmp.root, name)
        val writer = WavWriter(wav, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        // Write in chunks so we don't allocate huge arrays at once.
        val chunk = ShortArray(sampleRate) // 1 second
        repeat(durationS) { writer.writeShorts(chunk, 0, chunk.size) }
        writer.close()
        return wav
    }

    private fun makeDraft(
        wav: File,
        durationMs: Long,
        ranges: List<FragmentRange>,
        firstSeenMs: Long,
        lastSeenMs: Long,
        draftId: String = "d-mc-1",
        taxon: String = "Parus major",
    ): DraftWithDetections {
        val draft = DraftEntity(
            id = draftId,
            audioPath = wav.absolutePath,
            recordedAtUtcMs = 1_700_000_000_000L,
            durationMs = durationMs,
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
            detectedWindows = ranges.size,
            firstSeenMs = firstSeenMs,
            lastSeenMs = lastSeenMs,
            isSelectedByUser = true,
            fragmentRanges = FragmentRanges.encode(ranges),
        )
        return DraftWithDetections(draft, listOf(det))
    }

    @Test
    fun `three temporally separated clusters produce three sound and three photo uploads on one observation`() = runTest {
        // Clusters: [10s..16s], [120s..123s], [240s..243s]. Each pair of
        // clusters is > 30 s apart so ClipPlanner splits them.
        val ranges = listOf(
            FragmentRange(10_000L, 13_000L),
            FragmentRange(13_000L, 16_000L),
            FragmentRange(120_000L, 123_000L),
            FragmentRange(240_000L, 243_000L),
        )
        // A 250-second WAV is enough headroom for the 240 s third cluster.
        val wav = makeSilentWav("multi-clip.wav", durationS = 250)
        val draftDao = InMemoryDraftDao().apply { /* draft inserted explicitly below */ }
        val inatDao = InMemoryInatObservationDao()
        val client = makeClient()
        val submitter = INatSubmitter(
            client = client,
            drafts = draftDao,
            inatObservations = inatDao,
            tmpRoot = tmp.newFolder("cache-mc"),
            ioDispatcher = UnconfinedTestDispatcher(),
            nowMs = { 0L },
        )
        val draft = makeDraft(
            wav = wav,
            durationMs = 250_000L,
            ranges = ranges,
            firstSeenMs = 10_000L,
            lastSeenMs = 243_000L,
        )
        draftDao.inserted += draft.draft

        // Enqueue the non-multipart HTTP responses (resolveGenus, createObservation,
        // updateObservationTags, 2 annotations, addIdentification). uploadSound and
        // uploadObservationPhoto are intercepted by [TrackingClient].
        server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-mc-1"}"""))
        server.enqueue(MockResponse().setBody("""{"id":9}""")) // tag update
        server.enqueue(MockResponse().setBody("""{"id":11}""")) // Alive
        server.enqueue(MockResponse().setBody("""{"id":12}""")) // Organism
        server.enqueue(MockResponse().setBody("""{"id":21}""")) // identification

        val result = submitter.submit(token = "jwt", draft = draft)

        assertThat(result).isInstanceOf(INatSubmitter.Result.Ok::class.java)
        assertThat(inatDao.rows.map { it.uploadStatus })
            .containsExactly(InatUploadStatus.COMPLETE)
        // Three clusters -> three sound uploads, all on the same observation UUID.
        assertThat(client.soundUploads).hasSize(3)
        assertThat(client.soundUploads.map { it.first }.toSet()).containsExactly("u-mc-1")
        // Three spectrogram uploads, all on the same observation id.
        assertThat(client.photoUploads).hasSize(3)
        assertThat(client.photoUploads.map { it.first }.toSet()).containsExactly(900L)
        // Submitter wipes the crop directory in its finally block, so the
        // files are not on disk anymore after submit() returns. Validate the
        // recorded filenames instead.
        client.soundUploads.forEach { (_, f) ->
            assertThat(f.name).endsWith(".wav")
        }
        client.photoUploads.forEach { (_, f) ->
            assertThat(f.name).endsWith(".png")
        }
        // The clip filenames carry the clip index so sound[i].wav pairs with photo[i].png.
        val soundIndices = client.soundUploads.map { it.second.nameWithoutExtension }
        val photoIndices = client.photoUploads.map { it.second.nameWithoutExtension }
        assertThat(soundIndices.toSet()).isEqualTo(photoIndices.toSet())
        // PNGs are smart-cropped to ≤ MAX_SPECTROGRAM_S worth of columns so iNat
        // thumbnails render legibly. At ~94 columns/sec for 10 s, expect ≤ ~1100 px.
        // (Each cluster in the fixture is one 3 s fragment, so the spectrogram
        // window is shorter than the clip itself and gets cropped to the peak.)
        client.photoWidths.forEach { width ->
            assertThat(width).isAtMost(1100)
        }
    }
}

/** Reads a PNG's IHDR width (big-endian uint32 at byte offset 16). */
private fun readPngWidth(png: File): Int {
    require(png.exists() && png.length() >= 24) { "Not a usable PNG: $png" }
    val header = png.inputStream().use { it.readNBytes(24) }
    // PNG signature is 8 bytes; IHDR length+type is 8 more; width is the next 4.
    val o = 16
    return ((header[o].toInt() and 0xFF) shl 24) or
        ((header[o + 1].toInt() and 0xFF) shl 16) or
        ((header[o + 2].toInt() and 0xFF) shl 8) or
        (header[o + 3].toInt() and 0xFF)
}
