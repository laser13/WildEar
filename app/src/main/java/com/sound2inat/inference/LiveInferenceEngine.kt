package com.sound2inat.inference

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Streaming live inference: buffers float audio blocks, slices into
 * [windowSamples]-long windows hopped by [hopSamples], applies HPF +
 * [SpectralSubtractor] + [YamNetGate] + [model] per window. Emits raw
 * [WindowPrediction] with no confidence threshold filtering — downstream
 * `DetectionAggregator` handles thresholds.
 *
 * Backpressure: a [Channel] with capacity [queueCapacity] and DROP_OLDEST
 * overflow buffers windows for the worker. If inference falls behind real
 * time, oldest queued windows are dropped — recording is never stalled.
 * [backlog] reports current queue depth (consumers can show a UI hint).
 *
 * Lifecycle: [start] launches the worker on the supplied scope. [feed]
 * appends audio blocks (non-blocking). [stop] closes the queue, waits up to
 * `DRAIN_TIMEOUT_MS` for the worker to drain pending windows, then cancels.
 *
 * NOT thread-safe wrt [feed] — call from a single producer (the recorder pump).
 */
class LiveInferenceEngine(
    private val model: BioacousticModel,
    private val yamNetGate: YamNetGate?,
    private val spectralSubtractor: SpectralSubtractor?,
    private val sampleRateHz: Int = 48_000,
    private val windowSamples: Int = sampleRateHz * 3,
    private val hopSamples: Int = sampleRateHz * 3 / 2,
    private val applyHighPass: Boolean = true,
    private val queueCapacity: Int = 8,
) {
    private val _predictions = MutableSharedFlow<WindowPrediction>(extraBufferCapacity = 64)
    val predictions: SharedFlow<WindowPrediction> = _predictions.asSharedFlow()

    private val _backlog = MutableStateFlow(0)
    val backlog: StateFlow<Int> = _backlog.asStateFlow()

    private val queue = Channel<Window>(capacity = queueCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var workerJob: Job? = null

    // Ring large enough to hold one full window plus one full hop without overrun.
    private val ring = FloatArray(windowSamples + hopSamples)
    private var ringFilled = 0       // valid samples in [0, ringFilled)
    private var consumed = 0L        // total samples that have left the head of the ring
    private var nextEmitAt = 0L      // sample index of next window's start
    @Volatile private var stopped = false

    fun start(scope: CoroutineScope) {
        if (workerJob != null) return
        workerJob = scope.launch(Dispatchers.Default) { worker() }
    }

    /** Append a chunk of audio (float, normalised). Non-blocking. */
    fun feed(block: FloatArray) {
        if (stopped) return
        var idx = 0
        while (idx < block.size) {
            if (ringFilled == ring.size) {
                // Defensive: should not happen because tryEmitWindows slides ring after each emission.
                System.arraycopy(ring, hopSamples, ring, 0, ring.size - hopSamples)
                ringFilled -= hopSamples
                consumed += hopSamples
            }
            val take = (block.size - idx).coerceAtMost(ring.size - ringFilled)
            System.arraycopy(block, idx, ring, ringFilled, take)
            ringFilled += take
            idx += take
            tryEmitWindows()
        }
    }

    private fun tryEmitWindows() {
        while (consumed + ringFilled >= nextEmitAt + windowSamples) {
            val startInRing = (nextEmitAt - consumed).toInt()
            require(startInRing >= 0 && startInRing + windowSamples <= ringFilled) {
                "window slice out of range: start=$startInRing fill=$ringFilled"
            }
            val window = FloatArray(windowSamples)
            System.arraycopy(ring, startInRing, window, 0, windowSamples)
            val startMs = nextEmitAt * 1_000L / sampleRateHz
            val endMs = (nextEmitAt + windowSamples) * 1_000L / sampleRateHz
            val sent = queue.trySend(Window(window, startMs, endMs)).isSuccess
            if (sent) _backlog.value = (_backlog.value + 1).coerceAtMost(queueCapacity)
            nextEmitAt += hopSamples

            // Slide the ring so future audio can fit. Keep only samples >= nextEmitAt.
            val keepFromAbsolute = nextEmitAt
            val keepFromRing = (keepFromAbsolute - consumed).toInt().coerceAtLeast(0)
            val remaining = (ringFilled - keepFromRing).coerceAtLeast(0)
            if (keepFromRing > 0 && remaining > 0) {
                System.arraycopy(ring, keepFromRing, ring, 0, remaining)
            }
            consumed += keepFromRing
            ringFilled = remaining
        }
    }

    private suspend fun worker() {
        for (window in queue) {
            _backlog.value = (_backlog.value - 1).coerceAtLeast(0)
            if (_backlog.value > BACKLOG_WARN) {
                Log.w(TAG, "Inference behind real time: backlog=${_backlog.value}")
            }
            runWindow(window)
            yield()
        }
    }

    private suspend fun runWindow(w: Window) {
        var samples = w.samples
        if (applyHighPass) samples = highPassFilter(samples, sampleRateHz)
        spectralSubtractor?.let { samples = it.process(samples) }
        if (yamNetGate?.isBiological(samples, sampleRateHz) == false) return
        val preds = model.predict(
            pcmFloat32 = samples,
            sampleRateHz = sampleRateHz,
            latitude = null, longitude = null, observedAtMillis = 0L,
            windowStartMs = w.startMs, windowEndMs = w.endMs,
        )
        for (p in preds) _predictions.tryEmit(p)
    }

    /** Stops accepting new windows, drains queue (≤ [DRAIN_TIMEOUT_MS]), cancels worker. */
    suspend fun stop() {
        if (stopped) return
        stopped = true
        queue.close()
        withTimeoutOrNull(DRAIN_TIMEOUT_MS) { workerJob?.join() }
        workerJob?.cancel()
        workerJob = null
    }

    private data class Window(val samples: FloatArray, val startMs: Long, val endMs: Long)

    companion object {
        private const val TAG = "LiveInferenceEngine"
        private const val BACKLOG_WARN = 3
        private const val DRAIN_TIMEOUT_MS = 5_000L
    }
}
