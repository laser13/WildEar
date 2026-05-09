package com.sound2inat.recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AndroidAudioRecordSource(
    private val preferRaw: suspend () -> Boolean = { true },
) : AudioRecordSource {
    override val sampleRate = 48_000
    override val channels = 1
    override val bitsPerSample = 16

    private val format = AudioFormat.ENCODING_PCM_16BIT
    private val channelCfg = AudioFormat.CHANNEL_IN_MONO
    private val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelCfg, format)
    private val bufBytes = (minBuf * 2).coerceAtLeast(MIN_BUF_BYTES)

    private var record: AudioRecord? = null
    private var stopped = false

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        record = buildAudioRecord(sampleRate, bufBytes, preferRaw())
        record!!.startRecording()
        stopped = false
    }

    override suspend fun read(buf: ShortArray, off: Int, len: Int): Int {
        if (stopped) return 0
        val n = record?.read(buf, off, len) ?: return 0
        return if (n < 0) 0 else n
    }

    override suspend fun stop() {
        stopped = true
        record?.stop()
        record?.release()
        record = null
    }

    companion object {
        const val MIN_BUF_BYTES = 8192

        /**
         * Factory seam: replaceable in unit tests to inject fake [AudioRecord] instances.
         * Parameters: audioSource, sampleRate, bufferSizeInBytes → AudioRecord.
         */
        @SuppressLint("MissingPermission")
        internal var audioRecordFactory: (source: Int, sampleRate: Int, bufferSize: Int) -> AudioRecord =
            { source, sr, buf ->
                AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
            }

        /**
         * Tries [MediaRecorder.AudioSource.UNPROCESSED] first (skips AGC, noise suppression,
         * echo cancellation) for cleaner raw audio. Falls back to [MediaRecorder.AudioSource.MIC]
         * if construction fails or the recorder ends up in an uninitialized state.
         * When [useRaw] is false, skips UNPROCESSED and goes directly to MIC.
         */
        @SuppressLint("MissingPermission")
        internal fun buildAudioRecord(sampleRate: Int, bufferSize: Int, useRaw: Boolean = true): AudioRecord {
            if (useRaw) {
                runCatching {
                    val ar = audioRecordFactory(
                        MediaRecorder.AudioSource.UNPROCESSED,
                        sampleRate,
                        bufferSize,
                    )
                    if (ar.state == AudioRecord.STATE_INITIALIZED) return ar
                    ar.release()
                }
            }
            return audioRecordFactory(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                bufferSize,
            )
        }
    }
}
