package com.sound2inat.inference

import com.sound2inat.app.data.Settings
import com.sound2inat.modelmanager.ModelDescriptor
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * Bundles the production inference seams that [com.sound2inat.app.ui.review.ReviewViewModel]
 * needs. Supplied via Hilt so tests can swap the whole bundle without touching VM
 * construction; production wiring is [DefaultInferenceUseCase].
 */
interface InferenceUseCase {
    val inference: InferenceJob
    /** YAMNet gate always disabled — use for user-triggered re-analysis. */
    val inferenceReanalysis: InferenceJob
    val perchAnalysis: PerchAnalysisJob
    /** YAMNet gate always disabled — use for user-triggered re-analysis. */
    val perchReanalysis: PerchAnalysisJob
}

/**
 * Production wiring. Holds factory-style references to the model list, manager
 * and gates; per-run state lives on the stack inside [InferenceJob.run] /
 * [PerchAnalysisJob.run] so a single instance is safe to share across drafts.
 */
class DefaultInferenceUseCase(
    private val models: List<BioacousticModel>,
    private val descriptors: List<ModelDescriptor>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val yamNetGate: YamNetGate?,
    private val birdNetMeta: BirdNetMetaModel?,
) : InferenceUseCase {

    // Perch runs via its own PerchAnalysisJob; exclude it here to avoid double-run.
    private val birdNetModels = models.filter { it.modelId != ModelIds.PERCH }

    override val inference: InferenceJob = ProductionInferenceJob(
        models = birdNetModels,
        descriptors = descriptors,
        modelManager = modelManager,
        settings = settings,
        yamNetGate = yamNetGate,
        birdNetMeta = birdNetMeta,
    )

    override val inferenceReanalysis: InferenceJob = ProductionInferenceJob(
        models = birdNetModels,
        descriptors = descriptors,
        modelManager = modelManager,
        settings = settings,
        yamNetGate = null,
        birdNetMeta = birdNetMeta,
    )

    override val perchAnalysis: PerchAnalysisJob = ProductionPerchAnalysisJob(
        models = models,
        modelManager = modelManager,
        settings = settings,
        yamNetGate = yamNetGate,
    )

    override val perchReanalysis: PerchAnalysisJob = ProductionPerchAnalysisJob(
        models = models,
        modelManager = modelManager,
        settings = settings,
        yamNetGate = null,
        parallelism = 2,
    )
}

/**
 * Production [InferenceJob]. Iterates installed [BioacousticModel]s, applies
 * BirdNET location/time priors when enabled, aggregates per-window predictions
 * and reports progress. Per-model failures are isolated so a broken model
 * (e.g. wrong output shape) does not kill the whole run.
 */
internal class ProductionInferenceJob(
    private val models: List<BioacousticModel>,
    private val descriptors: List<ModelDescriptor>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val yamNetGate: YamNetGate?,
    private val birdNetMeta: BirdNetMetaModel?,
) : InferenceJob {

    @Suppress("TooGenericExceptionCaught", "LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")
    override suspend fun run(
        audioPath: String,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        onProgress: (Float) -> Unit,
    ): InferenceOutcome = withContext(Dispatchers.IO) {
        val descriptorsById = descriptors.associateBy { it.id }
        val ready = models.mapNotNull { m ->
            val d = descriptorsById[m.modelId] ?: return@mapNotNull null
            val state = modelManager.stateFor(d) as? ModelInstallState.Ready
                ?: return@mapNotNull null
            Triple(m, d, state)
        }
        if (ready.isEmpty()) {
            android.util.Log.w(TAG, "No model installed; skipping inference")
            return@withContext InferenceOutcome.Failure("No model installed")
        }
        val minConf = settings.minConfidenceDisplay.first()
        val minWin = settings.minWindows.first()
        val yamNetEnabled = settings.yamNetGateEnabled.first()
        val activeGate = if (yamNetEnabled) yamNetGate else null
        val aggregator = DetectionAggregator(minConfidence = minConf, minWindows = minWin)
        // Compute BirdNET location/time priors once per run; null when the meta
        // model isn't installed, the user disabled it in Settings, or coords
        // aren't known. Applied later only to BirdNET window predictions —
        // Perch has its own label space.
        val birdNetMetaEnabled = settings.birdNetMetaEnabled.first()
        val birdNetPriors = if (birdNetMetaEnabled) {
            computeBirdNetPriors(latitude, longitude, observedAtMillis)
        } else {
            null
        }
        val allPreds = ArrayList<WindowPrediction>()
        val succeeded = ArrayList<BioacousticModel>()
        val perModelErrors = ArrayList<String>()
        val total = ready.size
        // Per-model try/catch so a broken model (e.g. wrong output shape)
        // doesn't kill the entire run — the other models still produce
        // detections. The user sees a partial-success banner with the
        // failing model's error message.
        for ((idx, triple) in ready.withIndex()) {
            val (model, _, state) = triple
            try {
                android.util.Log.i(
                    TAG,
                    "Running ${model.modelId} v${model.modelVersion} " +
                        "(${idx + 1}/$total) on $audioPath",
                )
                val modelStartMs = System.currentTimeMillis()
                model.load(state.modelFile, state.labelsFile)
                val runner = InferenceRunner(
                    listOf(model),
                    yamNetGate = activeGate,
                )
                val perModel = coroutineScope {
                    val collector = launch {
                        runner.progress.collect { p ->
                            onProgress((idx + p) / total)
                        }
                    }
                    val result = runner.run(File(audioPath), latitude, longitude, observedAtMillis)
                    collector.cancel()
                    result
                }
                val rescaled = if (model.modelId == BIRDNET_MODEL_ID && birdNetPriors != null) {
                    applyBirdNetPriors(perModel, birdNetPriors)
                } else {
                    perModel
                }
                allPreds += rescaled
                succeeded += model
                android.util.Log.i(
                    "InferenceTiming",
                    "${model.modelId}: ${System.currentTimeMillis() - modelStartMs}ms total " +
                        "(load+inference, ${perModel.size} raw preds)",
                )
                android.util.Log.i(
                    TAG,
                    "${model.modelId} produced ${perModel.size} window predictions" +
                        if (rescaled.size != perModel.size) {
                            " (${perModel.size - rescaled.size} suppressed by regional priors)"
                        } else {
                            ""
                        },
                )
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Inference failed for ${model.modelId}", t)
                perModelErrors += "${model.modelId}: ${t.message ?: t::class.simpleName.orEmpty()}"
            } finally {
                runCatching { model.close() }
                    .onFailure { android.util.Log.w(TAG, "model.close() threw", it) }
            }
        }
        onProgress(1f)
        if (succeeded.isEmpty()) {
            android.util.Log.e(TAG, "All ${ready.size} model(s) failed: $perModelErrors")
            return@withContext InferenceOutcome.Failure(
                perModelErrors.joinToString(separator = "; ").ifEmpty { "Inference failed" },
            )
        }
        val ids = succeeded.joinToString(separator = "+") { it.modelId }
        val versions = succeeded.joinToString(separator = "+") { it.modelVersion }
        val rawDetections = aggregator.aggregate(allPreds)
        // Below-threshold "candidates" used to be surfaced separately as a
        // grayed-out list, but the 1% absolute floor produced too much noise
        // (BirdNET v2.4 GLOBAL spreads tiny scores across hundreds of similar
        // species). Honoring the user-set threshold strictly is cleaner.

        if (latitude != null && longitude != null) {
            settings.setLastKnownCoords(latitude, longitude)
        }

        InferenceOutcome.Success(
            modelId = ids,
            modelVersion = versions,
            detections = rawDetections,
            windows = allPreds,
        )
    }

    /**
     * Runs the BirdNET location/time meta-model once for this recording and
     * returns the per-species multiplier map, or null when we have no priors
     * to apply (model not installed, coords unknown, or any internal failure).
     * Coords fall back to [Settings.lastKnownLat]/[lastKnownLon] when the
     * recording itself has no GPS fix attached.
     */
    private suspend fun computeBirdNetPriors(
        latitude: Double?,
        longitude: Double?,
        recordedAtMillis: Long,
    ): Map<String, Float>? {
        val meta = birdNetMeta ?: return null
        val effectiveLat = latitude ?: settings.lastKnownLat.first() ?: return null
        val effectiveLon = longitude ?: settings.lastKnownLon.first() ?: return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = if (recordedAtMillis > 0L) recordedAtMillis else System.currentTimeMillis()
        val week = BirdNetMetaModel.weekOfYearFromDayOfYear(cal.get(Calendar.DAY_OF_YEAR))
        return meta.priorsByScientificName(effectiveLat, effectiveLon, week)
    }

    /**
     * Folds [priors] into [preds]: species absent from the map (multiplier 0)
     * are dropped entirely so they never reach the aggregator; species with a
     * non-unit multiplier have their confidence scaled accordingly. Pass-through
     * for species with multiplier 1.0 to avoid allocating new objects.
     */
    private fun applyBirdNetPriors(
        preds: List<WindowPrediction>,
        priors: Map<String, Float>,
    ): List<WindowPrediction> = preds.mapNotNull { wp ->
        val mult = priors[wp.taxonScientificName] ?: 0f
        when {
            mult <= 0f -> null
            mult >= 1f -> wp
            else -> wp.copy(confidence = wp.confidence * mult)
        }
    }

    private companion object {
        const val TAG = "ProductionInferenceJob"
        const val BIRDNET_MODEL_ID = ModelIds.BIRDNET
    }
}

/**
 * Production [PerchAnalysisJob]. Resolves the Perch [BioacousticModel] from the
 * injected list, ensures the artifact is installed, and runs
 * [InferenceRunner] over the WAV. The resulting per-window predictions are
 * aggregated with the user's `minConfidenceDisplay` threshold but NOT subjected
 * to the regional filter — Perch covers taxa (frogs, insects, mammals) for
 * which the regional service has no priors yet.
 */
internal class ProductionPerchAnalysisJob(
    private val models: List<BioacousticModel>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val yamNetGate: YamNetGate?,
    /** When true, YAMNet gate is always active and skips predict on DOWNRANK (hard pre-filter). */
    private val hardGate: Boolean = false,
    private val parallelism: Int = 1,
) : PerchAnalysisJob {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun run(
        audioPath: String,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        onProgress: (Float) -> Unit,
    ): PerchAnalysisOutcome = withContext(Dispatchers.IO) {
        val perch = models.firstOrNull { it.modelId == ModelIds.PERCH }
            ?: return@withContext PerchAnalysisOutcome.NotInstalled
        val state = modelManager.stateFor(com.sound2inat.modelmanager.PerchV2.descriptor)
            as? ModelInstallState.Ready
            ?: return@withContext PerchAnalysisOutcome.NotInstalled
        try {
            val perchStartMs = System.currentTimeMillis()
            val instances = buildList {
                add(perch)
                repeat(parallelism - 1) { add(perch.newInstance()) }
            }
            // Load all instances; if any load() throws, close already-loaded ones first.
            val loaded = mutableListOf<BioacousticModel>()
            try {
                for (inst in instances) {
                    inst.load(state.modelFile, state.labelsFile)
                    loaded += inst
                }
            } catch (t: Throwable) {
                loaded.forEach { runCatching { it.close() } }
                throw t
            }
            val gate = if (hardGate || settings.yamNetGateEnabled.first()) yamNetGate else null
            val runner = InferenceRunner(
                models = loaded,
                yamNetGate = gate,
                hardGate = hardGate,
            )
            val preds = coroutineScope {
                val collector = launch {
                    runner.progress.collect { p -> onProgress(p) }
                }
                val result = runner.run(File(audioPath), latitude, longitude, observedAtMillis)
                collector.cancel()
                result
            }
            android.util.Log.i(
                "InferenceTiming",
                "Perch(parallelism=$parallelism): ${System.currentTimeMillis() - perchStartMs}ms total " +
                    "(load+inference, ${preds.size} raw preds)",
            )
            onProgress(1f)
            val minConf = settings.minConfidenceDisplay.first()
            val minWin = settings.minWindows.first()
            val aggregator = DetectionAggregator(minConfidence = minConf, minWindows = minWin)
            PerchAnalysisOutcome.Success(aggregator.aggregate(preds))
        } catch (t: Throwable) {
            android.util.Log.e("ProductionPerchAnalysisJob", "Perch run failed", t)
            PerchAnalysisOutcome.Failure(t.message ?: t::class.simpleName.orEmpty())
        }
        // InferenceRunner closes all models (sequential: try/finally; parallel: async finally).
        // No explicit perch.close() needed here.
    }
}
