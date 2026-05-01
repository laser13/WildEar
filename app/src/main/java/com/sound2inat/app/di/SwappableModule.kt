package com.sound2inat.app.di

import android.content.Context
import com.sound2inat.inference.BioacousticModel
import com.sound2inat.inference.BirdNetMetaModel
import com.sound2inat.inference.BirdNetTfliteModel
import com.sound2inat.inference.InterpreterFactory
import com.sound2inat.inference.LiveInferenceEngine
import com.sound2inat.inference.LiveInferenceEngineFactory
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
    fun provideAudioSource(): AudioRecordSource = AndroidAudioRecordSource()

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
     * Factory that builds a [LiveInferenceEngine] bound to the recorder's sample
     * rate. Returned as nullable so test modules can override with `null` and
     * fall back to the offline [com.sound2inat.inference.ProductionInferenceJob]
     * pipeline. Production binding requires BirdNET v2.4 to be installed; it
     * errors at construction time when callers ask for the engine without it.
     */
    @Provides
    fun provideLiveInferenceEngineFactory(
        bioModels: List<@JvmSuppressWildcards BioacousticModel>,
        yamGate: YamNetGate?,
    ): LiveInferenceEngineFactory? = LiveInferenceEngineFactory { sampleRateHz ->
        val birdnet = bioModels.firstOrNull { it.modelId == "birdnet_v2_4" }
            ?: error("BirdNET model not bound — install before recording")
        LiveInferenceEngine(
            model = birdnet,
            yamNetGate = yamGate,
            spectralSubtractor = SpectralSubtractor(),
            sampleRateHz = sampleRateHz,
        )
    }
}
