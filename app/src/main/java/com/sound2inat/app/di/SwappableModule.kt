package com.sound2inat.app.di

import android.content.Context
import com.sound2inat.inference.BioacousticModel
import com.sound2inat.inference.BirdNetTfliteModel
import com.sound2inat.inference.InterpreterFactory
import com.sound2inat.location.FusedLocationProvider
import com.sound2inat.location.LocationProvider
import com.sound2inat.modelmanager.ModelManager
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
    ): ModelManager = ModelManager(ctx.filesDir, http)

    @Provides @Singleton
    fun provideAudioSource(): AudioRecordSource = AndroidAudioRecordSource()

    @Provides
    fun provideRecorder(source: AudioRecordSource): Recorder = DefaultRecorder(source)

    @Provides @Singleton
    fun provideLocation(@ApplicationContext ctx: Context): LocationProvider =
        FusedLocationProvider(ctx)

    @Provides @Singleton
    fun provideBioacousticModel(factory: InterpreterFactory): BioacousticModel =
        BirdNetTfliteModel(factory)
}
