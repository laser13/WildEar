package com.sound2inat.app.di

import android.content.Context
import androidx.room.Room
import com.sound2inat.app.data.Settings
import com.sound2inat.inference.InterpreterFactory
import com.sound2inat.inference.TfliteInterpreterFactory
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.Sound2iNatDb
import com.sound2inat.storage.WavFileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): Sound2iNatDb =
        Room.databaseBuilder(ctx, Sound2iNatDb::class.java, "sound2inat.db").build()

    @Provides fun provideDraftDao(db: Sound2iNatDb): DraftDao = db.drafts()

    @Provides fun provideDetectionDao(db: Sound2iNatDb): DetectionDao = db.detections()

    @Provides @Singleton
    fun provideWavFileStore(@ApplicationContext ctx: Context): WavFileStore =
        WavFileStore(ctx.filesDir)

    @Provides @Singleton
    fun provideDraftRepository(
        d: DraftDao,
        det: DetectionDao,
        files: WavFileStore,
    ): DraftRepository = DraftRepository(d, det, files)

    @Provides @Singleton
    fun provideSettings(@ApplicationContext ctx: Context): Settings = Settings(ctx)

    @Provides @Singleton
    fun provideHttp(): OkHttpClient = OkHttpClient()

    @Provides @Singleton
    fun provideInterpreterFactory(): InterpreterFactory = TfliteInterpreterFactory()
}
