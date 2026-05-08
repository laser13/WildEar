package com.sound2inat.app.di

import android.content.Context
import com.sound2inat.app.data.Settings
import com.sound2inat.inference.BioacousticModel
import com.sound2inat.inference.BirdNetMetaModel
import com.sound2inat.inference.BirdNetTfliteModel
import com.sound2inat.inference.DefaultInferenceUseCase
import com.sound2inat.inference.InferenceUseCase
import com.sound2inat.inference.InterpreterFactory
import com.sound2inat.inference.LiveInferenceEngine
import com.sound2inat.inference.LiveInferenceEngineFactory
import com.sound2inat.inference.ModelIds
import com.sound2inat.inference.PerchTfliteModel
import com.sound2inat.inference.SpectralSubtractor
import com.sound2inat.inference.YamNetGate
import com.sound2inat.inference.YamNetTfliteGate
import com.sound2inat.location.FusedLocationProvider
import com.sound2inat.location.LocationProvider
import com.sound2inat.modelmanager.BirdNetMetaV24
import com.sound2inat.modelmanager.KnownModels
import com.sound2inat.modelmanager.ModelDescriptor
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.modelmanager.YamNetV1
import com.sound2inat.recorder.AndroidAudioRecordSource
import com.sound2inat.recorder.AudioRecordSource
import com.sound2inat.recorder.DefaultRecorder
import com.sound2inat.recorder.Recorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Bindings replaceable in instrumented tests via `@TestInstallIn(replaces = [SwappableModule::class])`.
 * Keep this module narrow — only producers that the e2e test needs to fake belong here.
 */
@Module
@InstallIn(SingletonComponent::class)
object SwappableModule {

    @Provides @Singleton
    fun provideModelManager(
        @ApplicationContext ctx: Context,
        http: OkHttpClient,
    ): ModelManager = ModelManager(
        ctx.filesDir,
        http,
        hiddenDescriptors = listOf(YamNetV1.descriptor, BirdNetMetaV24.descriptor),
    )

    /**
     * YAMNet biological gate. Returned as nullable so [TestSwappableModule]
     * can override with `null` to bypass YAMNet inference in instrumented tests.
     */
    @Provides @Singleton
    fun provideYamNetGate(
        factory: InterpreterFactory,
        manager: ModelManager,
    ): YamNetGate? = YamNetTfliteGate(factory, manager)

    /**
     * BirdNET location/time meta-model. Returned as nullable so test modules
     * can replace with null to bypass regional rescaling in instrumented tests.
     */
    @Provides @Singleton
    fun provideBirdNetMeta(
        factory: InterpreterFactory,
        manager: ModelManager,
    ): BirdNetMetaModel? = BirdNetMetaModel(factory, manager)

    @Provides @Singleton
    fun provideAudioSource(settings: Settings): AudioRecordSource =
        AndroidAudioRecordSource(preferRaw = { settings.audioSourceRaw.first() })

    @Provides
    fun provideRecorder(source: AudioRecordSource): Recorder = DefaultRecorder(source)

    @Provides @Singleton
    fun provideLocation(@ApplicationContext ctx: Context): LocationProvider =
        FusedLocationProvider(ctx)

    /**
     * Models the inference layer can run for a draft. Order matters only for
     * test-mode determinism — production callers pick an installed subset by
     * [BioacousticModel.modelId]. Adding a new model = appending a new
     * implementation here AND a matching [com.sound2inat.modelmanager.ModelDescriptor]
     * in [com.sound2inat.modelmanager.KnownModels].
     */
    @Provides @Singleton
    fun provideBioacousticModels(factory: InterpreterFactory): List<BioacousticModel> =
        listOf(BirdNetTfliteModel(factory), PerchTfliteModel(factory))

    /** Descriptors paired (by [BioacousticModel.modelId]) with installed models. */
    @Provides @Singleton
    fun provideModelDescriptors(): List<ModelDescriptor> = KnownModels

    /**
     * Bundle of production inference seams ([com.sound2inat.inference.InferenceJob]
     * + [com.sound2inat.inference.PerchAnalysisJob]) consumed by the Review
     * screen. Test modules override this single binding to swap the whole
     * stack without touching VM construction.
     */
    @Provides @Singleton
    @Suppress("LongParameterList")
    fun provideInferenceUseCase(
        bioModels: List<@JvmSuppressWildcards BioacousticModel>,
        descriptors: List<@JvmSuppressWildcards ModelDescriptor>,
        modelManager: ModelManager,
        settings: Settings,
        yamNetGate: YamNetGate?,
        birdNetMeta: BirdNetMetaModel?,
    ): InferenceUseCase = DefaultInferenceUseCase(
        models = bioModels,
        descriptors = descriptors,
        modelManager = modelManager,
        settings = settings,
        yamNetGate = yamNetGate,
        birdNetMeta = birdNetMeta,
    )

    /**
     * Factory that builds a [LiveInferenceEngine] bound to the recorder's sample
     * rate. Returned as nullable so test modules can override with `null` and
     * fall back to the offline pipeline. The factory's `create` is suspend
     * because it loads the BirdNET TFLite weights synchronously before the
     * engine starts emitting predictions — without this load, predict() throws
     * "Model not loaded". Returns null when the model is not installed; the
     * recorder VM treats that as a signal to use the offline path.
     */
    @Provides
    fun provideLiveInferenceEngineFactory(
        bioModels: List<@JvmSuppressWildcards BioacousticModel>,
        yamGate: YamNetGate?,
        modelManager: ModelManager,
        settings: Settings,
    ): LiveInferenceEngineFactory? = LiveInferenceEngineFactory { sampleRateHz ->
        val birdnet = bioModels.firstOrNull { it.modelId == ModelIds.BIRDNET }
            ?: return@LiveInferenceEngineFactory null
        val state = modelManager.stateFor(com.sound2inat.modelmanager.BirdNetV24.descriptor)
        val ready = state as? com.sound2inat.modelmanager.ModelInstallState.Ready
            ?: return@LiveInferenceEngineFactory null
        runCatching { birdnet.load(ready.modelFile, ready.labelsFile) }
            .getOrElse { return@LiveInferenceEngineFactory null }
        val usePreprocessing = settings.spectralSubtractionEnabled.first()
        val gate = if (settings.yamNetGateEnabled.first()) yamGate else null
        LiveInferenceEngine(
            model = birdnet,
            yamNetGate = gate,
            spectralSubtractor = SpectralSubtractor(),
            sampleRateHz = sampleRateHz,
            usePreprocessing = usePreprocessing,
        )
    }
}
