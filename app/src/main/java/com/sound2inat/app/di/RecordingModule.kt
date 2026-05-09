package com.sound2inat.app.di

import com.sound2inat.app.data.Settings
import com.sound2inat.app.recording.DefaultRecordingController
import com.sound2inat.app.recording.RecordingController
import com.sound2inat.inat.RegionFilter
import com.sound2inat.inference.LiveInferenceEngineFactory
import com.sound2inat.inference.PostRecordingProcessor
import com.sound2inat.location.LocationProvider
import com.sound2inat.recorder.Recorder
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.WavFileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RecordingModule {
    @Provides @Singleton
    fun providePostRecordingProcessor(settings: Settings): PostRecordingProcessor =
        PostRecordingProcessor(settings)

    @Provides @Singleton
    @Suppress("LongParameterList")
    fun provideRecordingController(
        applicationScope: CoroutineScope,
        recorder: Recorder,
        location: LocationProvider,
        files: WavFileStore,
        drafts: DraftRepository,
        engineFactory: LiveInferenceEngineFactory?,
        regionFilter: RegionFilter,
        settings: Settings,
        processor: PostRecordingProcessor,
    ): RecordingController = DefaultRecordingController(
        applicationScope = applicationScope,
        recorder = recorder,
        location = location,
        files = files,
        drafts = drafts,
        engineFactory = engineFactory,
        minConfidence = settings.minConfidenceDisplay,
        regionFilter = regionFilter,
        regionFilterEnabled = settings.regionalFilterEnabled,
        regionRadiusKm = settings.regionRadiusKm,
        processor = processor,
    )
}
