package com.sound2inat.inference

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * Slices a mono 16-bit WAV into fixed windows hopped by [hopSeconds], optionally
 * applies a YAMNet biological gate, and calls each model per window that passes the gate.
 *
 * Sequential path ([models].size == 1): one [WavWindowReader] streams windows on demand.
 * Parallel path ([models].size > 1): each model opens its OWN [WavWindowReader] (separate
 * fd, identical file) and processes its assigned frame range; results merged and sorted
 * by windowStartMs.
 *
 * Memory note (Task B1): the previous implementation called [WavReader.readMono16]
 * which materialised the whole WAV as `ByteArray + ShortArray + FloatArray`, peaking at
 * roughly 4 × dataSize bytes. For a one-hour 48 kHz/16-bit/mono recording this is ~1.3 GB
 * and OOMs on Android (heap 256–512 MB on low/mid-tier devices). The streaming path keeps
 * one window worth of audio in memory (≈ 288 KB for a 3 s window at 48 kHz).
 *
 * [run] closes every model it receives (sequential: try/finally on models[0];
 * parallel: async finally blocks per model). TFLite implementations are safe to
 * double-close (null-guard on interpreter), so callers with their own finally-block
 * close do not need to change.
 */
class InferenceRunner(
    private val models: List<BioacousticModel>,
    private val hopSeconds: Float = 1f,
    private val yamNetGate: YamNetGate? = null,
    /**
     * When true, a DOWNRANK gate decision skips [BioacousticModel.predict] entirely.
     * When false (default), DOWNRANK is a soft post-filter that drops results only
     * when no prediction exceeds [GATE_HIGH_CONFIDENCE_OVERRIDE].
     */
    val hardGate: Boolean = false,
) {
    init { require(models.isNotEmpty()) { "models must not be empty" } }

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    suspend fun run(
        wavFile: File,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
    ): List<WindowPrediction> {
        _progress.value = 0f
        val model = models.first()
        val targetRate = model.expectedSampleRateHz

        val plan = WavWindowReader.open(wavFile).use { reader ->
            buildPlan(reader, targetRate, model.windowMs)
        }
        Log.d(
            "InferenceTiming",
            "${model.modelId}: streaming plan — nativeRate=${plan.nativeRate} " +
                "totalNative=${plan.totalNativeSamples} resampledLen=${plan.resampledLen} " +
                "frames=${plan.frames} win=${plan.win} hop=${plan.hop} targetRate=$targetRate",
        )

        if (plan.frames == 0) {
            models.forEach { runCatching { it.close() } }
            runCatching { yamNetGate?.close() }
            _progress.value = 1f
            return emptyList()
        }

        return if (models.size == 1) {
            runSequential(wavFile, plan, targetRate, model, latitude, longitude, observedAtMillis)
        } else {
            runParallel(wavFile, plan, targetRate, latitude, longitude, observedAtMillis)
        }
    }

    /**
     * Builds slicing parameters consistent with the legacy full-file path: the
     * resampled-domain length is computed via the same formula [Resampler.resample]
     * would have produced for the entire native buffer, then `win`/`hop` and
     * `frames` are derived on top.
     */
    private fun buildPlan(
        reader: WavWindowReader,
        targetRate: Int,
        modelWindowMs: Long,
    ): SlicePlan {
        val windowSeconds = modelWindowMs / MS_PER_SECOND
        val win = (windowSeconds * targetRate).toInt()
        val hop = (hopSeconds * targetRate).toInt()
        require(win > 0 && hop > 0) { "Invalid window/hop: win=$win hop=$hop" }

        val nativeRate = reader.sampleRate
        val totalNative = reader.totalSamples
        val resampledLen = if (nativeRate == targetRate || totalNative < 2) {
            totalNative
        } else {
            // Mirror Resampler.resample's outLen formula exactly:
            // outLen = floor((input.size - 1) / ratio) + 1, ratio = inRate / outRate
            val ratio = nativeRate.toDouble() / targetRate.toDouble()
            ((totalNative - 1).toDouble() / ratio).toInt() + 1
        }
        val frames = if (resampledLen < win) 0 else 1 + (resampledLen - win) / hop
        return SlicePlan(
            nativeRate = nativeRate,
            totalNativeSamples = totalNative,
            resampledLen = resampledLen,
            win = win,
            hop = hop,
            frames = frames,
        )
    }

    private suspend fun runSequential(
        wavFile: File,
        plan: SlicePlan,
        targetRate: Int,
        model: BioacousticModel,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
    ): List<WindowPrediction> {
        val out = ArrayList<WindowPrediction>(plan.frames * EXPECTED_PREDICTIONS_PER_FRAME)
        val loopStart = System.currentTimeMillis()
        try {
            WavWindowReader.open(wavFile).use { reader ->
                processSequentialFrames(reader, plan, targetRate, model, latitude, longitude, observedAtMillis, out, loopStart)
            }
        } finally {
            model.close()
        }
        runCatching { yamNetGate?.close() }
            .onFailure { Log.w("InferenceRunner", "yamNetGate.close() threw", it) }
        Log.d(
            "InferenceTiming",
            "${model.modelId}: loop done — ${plan.frames} windows in " +
                "${System.currentTimeMillis() - loopStart}ms",
        )
        return out
    }

    @Suppress("LongParameterList")
    private suspend fun processSequentialFrames(
        reader: WavWindowReader,
        plan: SlicePlan,
        targetRate: Int,
        model: BioacousticModel,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        out: ArrayList<WindowPrediction>,
        loopStart: Long,
    ) {
        val ratio = ratioFor(plan, targetRate)
        var batchStart = loopStart
        for (f in 0 until plan.frames) {
            val s = f * plan.hop
            val window = readResampledWindow(reader, ratio, s, plan.win)
            val gateResult = yamNetGate?.classify(window, targetRate)
            if (hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
                _progress.value = (f + 1).toFloat() / plan.frames
                continue
            }
            val startMs = (s.toLong() * MS_PER_SECOND_LONG) / targetRate
            val endMs = ((s + plan.win).toLong() * MS_PER_SECOND_LONG) / targetRate
            val predictions = model.predict(
                pcmFloat32 = window,
                sampleRateHz = targetRate,
                latitude = latitude,
                longitude = longitude,
                observedAtMillis = observedAtMillis,
                windowStartMs = startMs,
                windowEndMs = endMs,
            )
            _progress.value = (f + 1).toFloat() / plan.frames
            if (f == 0 || (f + 1) % BATCH_LOG_INTERVAL == 0 || f == plan.frames - 1) {
                val now = System.currentTimeMillis()
                Log.d(
                    "InferenceTiming",
                    "${model.modelId}: window ${f + 1}/${plan.frames} " +
                        "${now - batchStart}ms batch / ${now - loopStart}ms total",
                )
                batchStart = now
            }
            if (!hardGate && gateResult.suppressesPredictions(predictions)) continue
            out += predictions
        }
    }

    private fun ratioFor(plan: SlicePlan, targetRate: Int): Double =
        if (plan.nativeRate == targetRate) 1.0 else plan.nativeRate.toDouble() / targetRate.toDouble()

    private suspend fun runParallel(
        wavFile: File,
        plan: SlicePlan,
        targetRate: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
    ): List<WindowPrediction> {
        val counter = AtomicInteger(0)
        val results = coroutineScope {
            models.indices.map { i ->
                val chunkStart = i * plan.frames / models.size
                val chunkEnd = if (i == models.size - 1) {
                    plan.frames
                } else {
                    (i + 1) * plan.frames / models.size
                }
                async {
                    processParallelChunk(
                        wavFile, plan, targetRate, modelIndex = i,
                        chunkStart = chunkStart, chunkEnd = chunkEnd,
                        latitude = latitude, longitude = longitude,
                        observedAtMillis = observedAtMillis, counter = counter,
                    )
                }
            }.awaitAll()
        }
        _progress.value = 1f
        runCatching { yamNetGate?.close() }
            .onFailure { Log.w("InferenceRunner", "yamNetGate.close() threw", it) }
        return results.flatten().sortedBy { it.startMs }
    }

    @Suppress("LongParameterList")
    private suspend fun processParallelChunk(
        wavFile: File,
        plan: SlicePlan,
        targetRate: Int,
        modelIndex: Int,
        chunkStart: Int,
        chunkEnd: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        counter: AtomicInteger,
    ): List<WindowPrediction> {
        val chunkOut = ArrayList<WindowPrediction>()
        val model = models[modelIndex]
        try {
            WavWindowReader.open(wavFile).use { reader ->
                processParallelFrames(
                    reader, plan, targetRate, model,
                    chunkStart, chunkEnd,
                    latitude, longitude, observedAtMillis,
                    counter, chunkOut,
                )
            }
        } finally {
            model.close()
        }
        return chunkOut
    }

    @Suppress("LongParameterList")
    private suspend fun processParallelFrames(
        reader: WavWindowReader,
        plan: SlicePlan,
        targetRate: Int,
        model: BioacousticModel,
        chunkStart: Int,
        chunkEnd: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        counter: AtomicInteger,
        chunkOut: ArrayList<WindowPrediction>,
    ) {
        val ratio = ratioFor(plan, targetRate)
        for (f in chunkStart until chunkEnd) {
            val s = f * plan.hop
            val window = readResampledWindow(reader, ratio, s, plan.win)
            val gateResult = yamNetGate?.classify(window, targetRate)
            if (hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
                val newProgress = counter.incrementAndGet().toFloat() / plan.frames
                _progress.update { maxOf(it, newProgress) }
                continue
            }
            val startMs = (s.toLong() * MS_PER_SECOND_LONG) / targetRate
            val endMs = ((s + plan.win).toLong() * MS_PER_SECOND_LONG) / targetRate
            val predictions = model.predict(
                pcmFloat32 = window,
                sampleRateHz = targetRate,
                latitude = latitude,
                longitude = longitude,
                observedAtMillis = observedAtMillis,
                windowStartMs = startMs,
                windowEndMs = endMs,
            )
            val newProgress = counter.incrementAndGet().toFloat() / plan.frames
            _progress.update { maxOf(it, newProgress) }
            if (!hardGate && gateResult.suppressesPredictions(predictions)) continue
            chunkOut += predictions
        }
    }

    private fun readResampledWindow(
        reader: WavWindowReader,
        ratio: Double,
        resampledStart: Int,
        win: Int,
    ): FloatArray = StreamingResampler.readResampledWindow(reader, ratio, resampledStart, win)

    private data class SlicePlan(
        val nativeRate: Int,
        val totalNativeSamples: Int,
        val resampledLen: Int,
        val win: Int,
        val hop: Int,
        val frames: Int,
    )

    private companion object {
        const val MS_PER_SECOND = 1_000f
        const val MS_PER_SECOND_LONG = 1_000L
        const val BATCH_LOG_INTERVAL = 50
        const val EXPECTED_PREDICTIONS_PER_FRAME = 5
    }
}

/**
 * Reads a mono 16-bit PCM WAV produced by `recorder.WavWriter` (clean 44-byte
 * header, fmt chunk size 16, PCM data chunk immediately after). Not a general
 * RIFF parser — extra chunks (e.g. LIST/INFO) are NOT supported.
 */
internal object WavReader {
    fun readMono16(file: File): Pair<ShortArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44).also { raf.readFully(it) }
            require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
                "Not a WAV file"
            }
            val ch = WavHeaderParser.readLeUint16(header, 22)
            val sr = WavHeaderParser.readLeUint32(header, 24).toInt()
            val bits = WavHeaderParser.readLeUint16(header, 34)
            require(ch == 1 && bits == 16) { "Mono 16-bit PCM only (got ch=$ch bits=$bits)" }
            require(String(header, 36, 4) == "data") {
                "WAV 'data' chunk not at offset 36 — unsupported chunk layout"
            }
            val dataSize: Long = WavHeaderParser.readLeUint32(header, 40)
            require(dataSize in 0L..Int.MAX_VALUE.toLong()) {
                "WAV dataSize out of safe range: $dataSize bytes"
            }
            val raw = ByteArray(dataSize.toInt())
            raf.readFully(raw)
            // Build ShortArray after fully reading raw bytes so both arrays do
            // not need to coexist in heap. The local `raw` reference becomes
            // unreachable after the lambda exits and can be collected early.
            val sampleCount = dataSize.toInt() / 2
            val samples = ShortArray(sampleCount) { i ->
                val lo = raw[2 * i].toInt() and 0xFF
                val hi = raw[2 * i + 1].toInt()
                ((hi shl 8) or lo).toShort()
            }
            return samples to sr
        }
    }
}
