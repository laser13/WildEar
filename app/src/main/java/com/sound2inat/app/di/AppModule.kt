package com.sound2inat.app.di

import android.content.Context
import androidx.room.Room
import com.sound2inat.app.data.Settings
import com.sound2inat.inat.INatSubmitter
import com.sound2inat.inat.INaturalistClient
import com.sound2inat.inat.RegionFilter
import com.sound2inat.inat.RegionLookup
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
        Room.databaseBuilder(ctx, Sound2iNatDb::class.java, "sound2inat.db")
            .addMigrations(
                Sound2iNatDb.MIGRATION_1_2,
                Sound2iNatDb.MIGRATION_2_3,
                Sound2iNatDb.MIGRATION_3_4,
            )
            .build()

    @Provides fun provideDraftDao(db: Sound2iNatDb): DraftDao = db.drafts()

    @Provides fun provideDetectionDao(db: Sound2iNatDb): DetectionDao = db.detections()

    @Provides fun provideInatObservationDao(db: Sound2iNatDb): com.sound2inat.storage.InatObservationDao =
        db.inatObservations()

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

    @Provides @Singleton
    fun provideINaturalistClient(http: OkHttpClient): INaturalistClient = INaturalistClient(http)

    @Provides @Singleton
    fun provideRegionFilter(client: INaturalistClient): RegionFilter {
        val lookup = object : RegionLookup {
            override suspend fun getPlaceId(lat: Double, lon: Double): Long? =
                client.getNearbyStandardPlace(lat, lon)
            override suspend fun checkInPlace(scientificName: String, placeId: Long): Boolean =
                client.hasObservationsInPlace(scientificName, placeId)
            override suspend fun checkNear(
                scientificName: String,
                lat: Double,
                lon: Double,
                radiusKm: Int,
            ): Boolean = client.hasObservationsNear(scientificName, lat, lon, radiusKm)
        }
        return RegionFilter(lookup)
    }

    @Provides @Singleton
    fun provideINatSubmitter(
        @ApplicationContext ctx: Context,
        client: INaturalistClient,
        drafts: DraftDao,
        inatObservations: com.sound2inat.storage.InatObservationDao,
    ): INatSubmitter = INatSubmitter(
        client = client,
        drafts = drafts,
        inatObservations = inatObservations,
        tmpRoot = ctx.cacheDir,
    )
}
