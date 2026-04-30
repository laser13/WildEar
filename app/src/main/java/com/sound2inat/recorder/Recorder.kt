package com.sound2inat.recorder

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

interface Recorder {
    val rmsLevel: StateFlow<Float>

    /**
     * Rolling history of the last [HISTORY_SIZE] RMS samples (oldest first).
     * Lets the UI render a live waveform/VU bar without re-deriving anything.
     * Reset to an empty array on each [start].
     */
    val rmsHistory: StateFlow<FloatArray>

    suspend fun start(target: File)
    suspend fun stop(): RecordingResult
    fun cancel()

    companion object {
        const val HISTORY_SIZE = 200
    }
}

interface AudioRecordSource {
    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    suspend fun start()
    suspend fun read(buf: ShortArray, off: Int, len: Int): Int
    suspend fun stop()
}

interface Clock {
    fun nowMs(): Long
}

class SystemClock : Clock {
    override fun nowMs() = System.currentTimeMillis()
}

class DefaultRecorder(
    private val source: AudioRecordSource,
    private val clock: Clock = SystemClock(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val externalScope: CoroutineScope? = null,
) : Recorder {
    private val _rms = MutableStateFlow(0f)
    override val rmsLevel: StateFlow<Float> = _rms

    private val _rmsHistory = MutableStateFlow(FloatArray(0))
    override val rmsHistory: StateFlow<FloatArray> = _rmsHistory

    private var writer: WavWriter? = null
    private var target: File? = null
    private var startMs = 0L
    private var job: Job? = null
    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + ioDispatcher)

    override suspend fun start(target: File) {
        this.target = target
        writer = WavWriter(target, source.sampleRate, source.channels, source.bitsPerSample).also { it.open() }
        startMs = clock.nowMs()
        _rmsHistory.value = FloatArray(0)
        source.start()
        job = scope.launch { pump() }
    }

    private suspend fun pump() {
        val buf = ShortArray(BUFFER_FRAMES)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n <= 0) break
            writer?.writeShorts(buf, 0, n)
            val rms = computeRms(buf, n)
            _rms.value = rms
            pushRms(rms)
        }
    }

    private fun pushRms(value: Float) {
        val cur = _rmsHistory.value
        val cap = Recorder.HISTORY_SIZE
        val next = if (cur.size < cap) {
            FloatArray(cur.size + 1).also {
                System.arraycopy(cur, 0, it, 0, cur.size)
                it[cur.size] = value
            }
        } else {
            FloatArray(cap).also {
                System.arraycopy(cur, 1, it, 0, cap - 1)
                it[cap - 1] = value
            }
        }
        _rmsHistory.value = next
    }

    private fun computeRms(buf: ShortArray, len: Int): Float {
        var acc = 0.0
        for (i in 0 until len) {
            val v = buf[i] / Short.MAX_VALUE.toFloat()
            acc += (v * v).toDouble()
        }
        return sqrt(acc / len).toFloat()
    }

    override suspend fun stop(): RecordingResult = withContext(ioDispatcher) {
        source.stop()
        job?.cancel()
        job = null
        writer?.close()
        writer = null
        val durationMs = clock.nowMs() - startMs
        RecordingResult(target!!.absolutePath, durationMs, source.sampleRate, source.channels)
    }

    override fun cancel() {
        job?.cancel()
        job = null
        runCatching { writer?.close() }
        writer = null
        target?.delete()
        target = null
    }

    companion object {
        const val BUFFER_FRAMES = 4096
    }
}
