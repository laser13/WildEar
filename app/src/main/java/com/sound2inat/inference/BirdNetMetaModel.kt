package com.sound2inat.inference

import com.sound2inat.modelmanager.BirdNetMetaV24
import com.sound2inat.modelmanager.LabelsFormat
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.cos

/**
 * BirdNET v2.4 location/time meta-model wrapper. Given a recording's lat/lon
 * and the calendar week-of-year, produces a per-species multiplier in
 * `{0.0, 0.5, 0.8, 1.0}` that callers fold into BirdNET's audio confidence.
 *
 * The meta-model itself emits a continuous probability per species; we
 * discretize via the same threshold ladder whoBIRD uses (0.001 / 0.008 /
 * 0.01) so a species with prior near zero is suppressed entirely while a
 * locally-common one passes through unchanged.
 *
 * Fail-open: any error (model not installed, broken interpreter, label/output
 * size mismatch) returns `null` so the calling pipeline keeps the audio-only
 * confidence rather than silently zeroing every species.
 */
class BirdNetMetaModel(
    private val factory: InterpreterFactory,
    private val modelManager: ModelManager,
) {

    private val mutex = Mutex()
    private var interpreter: InterpreterApi? = null
    private var labels: List<Label> = emptyList()

    /**
     * @return map keyed by scientific name with the multiplier to apply to
     *  that species' audio confidence; species absent from the map should be
     *  treated as multiplier 0 (fully suppressed). Returns `null` when the
     *  meta-model isn't available — caller should skip the regional rescale.
     */
    suspend fun priorsByScientificName(
        latitude: Double,
        longitude: Double,
        weekOfYear: Int,
    ): Map<String, Float>? = runCatching {
        computePriors(latitude, longitude, weekOfYear)
    }.getOrNull()

    private suspend fun computePriors(
        latitude: Double,
        longitude: Double,
        weekOfYear: Int,
    ): Map<String, Float>? {
        ensureLoaded()
        val interp = interpreter ?: return null
        if (labels.isEmpty()) return null

        val weekMeta = (cos(weekOfYear * WEEK_RADIANS) + 1.0).toFloat()
        val input = arrayOf(floatArrayOf(latitude.toFloat(), longitude.toFloat(), weekMeta))
        val output = arrayOf(FloatArray(labels.size))
        interp.run(input, output)
        val rawPriors = output[0]

        val result = HashMap<String, Float>(labels.size)
        for (i in labels.indices) {
            val multiplier = applyThreshold(rawPriors[i])
            if (multiplier > 0f) {
                result[labels[i].scientificName] = multiplier
            }
        }
        return result
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        if (interpreter != null) return@withLock
        val state = modelManager.stateFor(BirdNetMetaV24.descriptor) as? ModelInstallState.Ready
            ?: return@withLock
        labels = Labels.load(state.labelsFile, LabelsFormat.BirdNetUnderscore)
        interpreter = factory.create(state.modelFile, threads = 1)
    }

    private fun applyThreshold(prob: Float): Float = when {
        prob >= THRESHOLD_HIGH -> MULTIPLIER_HIGH
        prob >= THRESHOLD_MID -> MULTIPLIER_MID
        prob >= THRESHOLD_LOW -> MULTIPLIER_LOW
        else -> 0f
    }

    companion object {
        // BirdNET-Analyzer "year" is 48 weeks; weekMeta = cos(week * 7.5°) + 1.
        // 7.5° in radians.
        private const val WEEK_DEGREES = 7.5
        private val WEEK_RADIANS = WEEK_DEGREES * PI / 180.0

        private const val THRESHOLD_HIGH = 0.01f
        private const val THRESHOLD_MID = 0.008f
        private const val THRESHOLD_LOW = 0.001f

        private const val MULTIPLIER_HIGH = 1.0f
        private const val MULTIPLIER_MID = 0.8f
        private const val MULTIPLIER_LOW = 0.5f

        /** Maps a BirdNET week-of-year (1–48). [dayOfYear] should be 1–366. */
        fun weekOfYearFromDayOfYear(dayOfYear: Int): Int =
            kotlin.math.ceil(dayOfYear * 48.0 / 366.0).toInt().coerceIn(1, 48)
    }
}
