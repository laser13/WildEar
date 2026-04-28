package com.sound2inat.recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AndroidAudioRecordSource : AudioRecordSource {
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
        record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelCfg, format, bufBytes)
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
    }
}
