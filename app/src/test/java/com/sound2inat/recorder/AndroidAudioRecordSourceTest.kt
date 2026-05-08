package com.sound2inat.recorder

import android.media.AudioRecord
import android.media.MediaRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidAudioRecordSourceTest {

    /** Saves the original factory so we can restore it after each test. */
    private val originalFactory = AndroidAudioRecordSource.audioRecordFactory

    @After
    fun tearDown() {
        AndroidAudioRecordSource.audioRecordFactory = originalFactory
    }

    @Test
    fun `falls back to MIC when UNPROCESSED throws`() {
        val micRecord = mockk<AudioRecord>(relaxed = true) {
            every { state } returns AudioRecord.STATE_INITIALIZED
        }

        AndroidAudioRecordSource.audioRecordFactory = { source, sampleRate, bufferSize ->
            when (source) {
                MediaRecorder.AudioSource.UNPROCESSED ->
                    throw RuntimeException("UNPROCESSED not supported on this device")
                MediaRecorder.AudioSource.MIC -> micRecord
                else -> throw IllegalArgumentException("Unexpected audio source: $source")
            }
        }

        val result = AndroidAudioRecordSource.buildAudioRecord(48_000, 8192)

        assertNotNull(result)
        assert(result === micRecord) { "Expected MIC-based AudioRecord to be returned" }
    }

    @Test
    fun `falls back to MIC when UNPROCESSED returns uninitialized state`() {
        val uninitializedRecord = mockk<AudioRecord>(relaxed = true) {
            every { state } returns AudioRecord.STATE_UNINITIALIZED
        }
        val micRecord = mockk<AudioRecord>(relaxed = true) {
            every { state } returns AudioRecord.STATE_INITIALIZED
        }

        AndroidAudioRecordSource.audioRecordFactory = { source, _, _ ->
            when (source) {
                MediaRecorder.AudioSource.UNPROCESSED -> uninitializedRecord
                MediaRecorder.AudioSource.MIC -> micRecord
                else -> throw IllegalArgumentException("Unexpected audio source: $source")
            }
        }

        val result = AndroidAudioRecordSource.buildAudioRecord(48_000, 8192)

        assertNotNull(result)
        assert(result === micRecord) { "Expected MIC-based AudioRecord when UNPROCESSED is uninitialized" }
        // Verify the uninitialized record was released before fallback
        verify { uninitializedRecord.release() }
    }

    @Test
    fun `uses UNPROCESSED when it initializes successfully`() {
        val unprocessedRecord = mockk<AudioRecord>(relaxed = true) {
            every { state } returns AudioRecord.STATE_INITIALIZED
        }

        AndroidAudioRecordSource.audioRecordFactory = { source, _, _ ->
            when (source) {
                MediaRecorder.AudioSource.UNPROCESSED -> unprocessedRecord
                else -> throw AssertionError("Should not fall back to MIC — UNPROCESSED succeeded")
            }
        }

        val result = AndroidAudioRecordSource.buildAudioRecord(48_000, 8192)

        assertNotNull(result)
        assert(result === unprocessedRecord) { "Expected UNPROCESSED AudioRecord to be returned" }
    }

    @Test
    fun `uses only MIC when useRaw is false`() {
        val micRecord = mockk<AudioRecord>(relaxed = true) {
            every { state } returns AudioRecord.STATE_INITIALIZED
        }
        val calledSources = mutableListOf<Int>()

        AndroidAudioRecordSource.audioRecordFactory = { source, _, _ ->
            calledSources += source
            when (source) {
                MediaRecorder.AudioSource.MIC -> micRecord
                else -> throw AssertionError("UNPROCESSED must not be requested when useRaw=false")
            }
        }

        val result = AndroidAudioRecordSource.buildAudioRecord(48_000, 8192, useRaw = false)

        assertNotNull(result)
        assert(result === micRecord) { "Expected MIC-based AudioRecord when useRaw=false" }
        assert(calledSources == listOf(MediaRecorder.AudioSource.MIC)) {
            "Factory should be called exactly once with MIC; got: $calledSources"
        }
    }
}
