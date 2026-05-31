package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReviewExportUseCaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun createSilentWav(dest: File, sampleRate: Int = 16_000, durationMs: Long = 1_000): File {
        val samples = ((sampleRate.toLong() * durationMs) / 1000L).toInt()
        val writer = WavWriter(dest, sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        writer.writeShorts(ShortArray(samples), 0, samples)
        writer.close()
        return dest
    }

    private fun row(name: String = "Turdus merula", first: Long = 0L, last: Long = 500L) = SpeciesRow(
        detectionId = 1L,
        taxonScientificName = name,
        taxonCommonName = "Common Blackbird",
        maxConfidence = 0.8f,
        detectedWindows = 1,
        firstSeenMs = first,
        lastSeenMs = last,
        isSelected = true,
    )

    @Test
    fun `prepareSpeciesClip trims a valid clip into the export dir`() {
        val src = createSilentWav(tmp.newFile("src.wav"))
        val clipsDir = tmp.newFolder("clips")
        val useCase = ReviewExportUseCase(exportClipsDir = clipsDir, draftId = "d1")
        val snapshot = ReviewUiState(
            draftId = "d1",
            audioPath = src.absolutePath,
            durationMs = 1_000L,
        )

        val clip = useCase.prepareSpeciesClip(snapshot, row())

        assertThat(clip.exists()).isTrue()
        assertThat(clip.length()).isGreaterThan(0L)
        assertThat(clip.parentFile).isEqualTo(clipsDir)
    }

    @Test
    fun `prepareSpeciesClip throws IllegalArgumentException when source is missing`() {
        val clipsDir = tmp.newFolder("clips2")
        val useCase = ReviewExportUseCase(exportClipsDir = clipsDir, draftId = "d2")
        val snapshot = ReviewUiState(
            draftId = "d2",
            audioPath = File(tmp.root, "missing.wav").absolutePath,
            durationMs = 1_000L,
        )

        var threw = false
        try {
            useCase.prepareSpeciesClip(snapshot, row())
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun `buildDisplayName produces a UTC wildear wav name`() {
        val useCase = ReviewExportUseCase(exportClipsDir = tmp.newFolder("clips3"), draftId = "d3")
        val name = useCase.buildDisplayName(label = "original", recordedAtUtcMs = 0L)
        assertThat(name).startsWith("wildear_original_1970-01-01_00-00-00")
        assertThat(name).endsWith(".wav")
    }
}
