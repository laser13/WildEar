package com.sound2inat.app.di

import com.sound2inat.app.recording.DefaultRecordingServiceLauncher
import com.sound2inat.app.recording.RecordingServiceLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RecordingLauncherModule {
    @Binds @Singleton
    abstract fun bindLauncher(impl: DefaultRecordingServiceLauncher): RecordingServiceLauncher
}
