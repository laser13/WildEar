package com.sound2inat.app.di

import com.sound2inat.app.data.Settings
import com.sound2inat.app.recording.DefaultRecordingController
import com.sound2inat.app.recording.RecordingController
import com.sound2inat.inference.LiveInferenceEngineFactory
import com.sound2inat.location.LocationProvider
import com.sound2inat.recorder.Recorder
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.WavFileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RecordingModule {
    @Provides @Singleton
    fun provideRecordingController(
        recorder: Recorder,
        location: LocationProvider,
        files: WavFileStore,
        drafts: DraftRepository,
        engineFactory: LiveInferenceEngineFactory?,
        settings: Settings,
    ): RecordingController = DefaultRecordingController(
        recorder = recorder,
        location = location,
        files = files,
        drafts = drafts,
        engineFactory = engineFactory,
        minConfidence = settings.minConfidenceDisplay,
    )
}
