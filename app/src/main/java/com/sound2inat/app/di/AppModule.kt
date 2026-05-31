package com.sound2inat.app.di

import android.content.Context
import androidx.room.Room
import com.sound2inat.app.BuildConfig
import com.sound2inat.app.data.Settings
import com.sound2inat.app.data.SettingsLegacyInatTokenSource
import com.sound2inat.inat.INatSubmitter
import com.sound2inat.inat.INatTokenStorage
import com.sound2inat.inat.INatTokenStore
import com.sound2inat.inat.INaturalistClient
import com.sound2inat.inat.LegacyInatTokenSource
import com.sound2inat.inat.RegionFilter
import com.sound2inat.inat.RegionLookup
import com.sound2inat.inference.InterpreterFactory
import com.sound2inat.inference.TfliteInterpreterFactory
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftPhotoDao
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.PhotoDraftDao
import com.sound2inat.storage.PhotoDraftImageDao
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoFileStore
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.Sound2iNatDb
import com.sound2inat.storage.WavFileStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Long-lived scope shared across @Singleton components. Used by
     * [com.sound2inat.app.recording.DefaultRecordingController] for engine
     * tear-down jobs that must outlive the foreground RecordingService — the
     * service is destroyed as soon as it calls stopSelf(), which would
     * otherwise orphan the running coroutines that finalize a session.
     *
     * SupervisorJob so a failure in one consumer doesn't poison the rest;
     * Dispatchers.Default because the scope is not bound to IO — individual
     * launches that need IO can wrap in withContext(ioDispatcher) themselves.
     */
    @Provides @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): Sound2iNatDb =
        Room.databaseBuilder(ctx, Sound2iNatDb::class.java, "sound2inat.db")
            .addMigrations(
                Sound2iNatDb.MIGRATION_1_2,
                Sound2iNatDb.MIGRATION_2_3,
                Sound2iNatDb.MIGRATION_3_4,
                Sound2iNatDb.MIGRATION_4_5,
                Sound2iNatDb.MIGRATION_5_6,
                Sound2iNatDb.MIGRATION_6_7,
                Sound2iNatDb.MIGRATION_7_8,
                Sound2iNatDb.MIGRATION_8_9,
                Sound2iNatDb.MIGRATION_9_10,
                Sound2iNatDb.MIGRATION_10_11,
                Sound2iNatDb.MIGRATION_11_12,
                Sound2iNatDb.MIGRATION_12_13,
            )
            .build()

    @Provides fun provideDraftDao(db: Sound2iNatDb): DraftDao = db.drafts()

    @Provides fun provideDetectionDao(db: Sound2iNatDb): DetectionDao = db.detections()

    @Provides fun provideDraftPhotoDao(db: Sound2iNatDb): DraftPhotoDao = db.photos()

    @Provides fun providePhotoDraftDao(db: Sound2iNatDb): PhotoDraftDao = db.photoDrafts()

    @Provides fun providePhotoDraftImageDao(db: Sound2iNatDb): PhotoDraftImageDao = db.photoDraftImages()

    @Provides @Singleton
    fun providePhotoFileStore(@ApplicationContext ctx: Context): PhotoFileStore =
        PhotoFileStore(
            ctx.getExternalFilesDir("habitat_photos")
                ?: File(ctx.filesDir, "habitat_photos"),
        )

    @Provides @Singleton
    fun providePhotoObservationFileStore(@ApplicationContext ctx: Context): PhotoObservationFileStore =
        PhotoObservationFileStore(File(ctx.filesDir, "photo_observations"))

    @Provides @Singleton
    fun providePhotoDraftRepository(
        db: Sound2iNatDb,
        draftDao: PhotoDraftDao,
        imageDao: PhotoDraftImageDao,
        fileStore: PhotoObservationFileStore,
    ): PhotoDraftRepository = PhotoDraftRepository(
        draftDao = draftDao,
        imageDao = imageDao,
        fileStore = fileStore,
        runInTransaction = { block -> db.runInTransaction(block) },
    )

    @Provides fun provideInatObservationDao(db: Sound2iNatDb): com.sound2inat.storage.InatObservationDao =
        db.inatObservations()

    @Provides @Singleton
    fun provideWavFileStore(@ApplicationContext ctx: Context): WavFileStore =
        WavFileStore(ctx.filesDir)

    @Provides @Singleton
    fun provideDraftRepository(
        db: Sound2iNatDb,
        d: DraftDao,
        det: DetectionDao,
        files: WavFileStore,
        photos: DraftPhotoDao,
        photoStore: PhotoFileStore,
    ): DraftRepository = DraftRepository(
        drafts = d,
        detections = det,
        files = files,
        photosDao = photos,
        photoStore = photoStore,
        runInTransaction = { block -> db.runInTransaction(block) },
    )

    @Provides @Singleton
    fun provideSettings(@ApplicationContext ctx: Context): Settings = Settings(ctx)

    @Provides @Singleton
    fun provideTokenStore(impl: INatTokenStorage): INatTokenStore = impl

    @Provides @Singleton
    fun provideLegacyInatTokenSource(impl: SettingsLegacyInatTokenSource): LegacyInatTokenSource = impl

    @Provides @Singleton
    fun provideHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun provideInterpreterFactory(): InterpreterFactory = TfliteInterpreterFactory()

    @Provides @Singleton
    fun provideINaturalistClient(http: OkHttpClient): INaturalistClient =
        INaturalistClient(http, debugLogging = BuildConfig.DEBUG)

    @Provides @Singleton
    fun provideRegionFilter(client: INaturalistClient): RegionFilter {
        val lookup = object : RegionLookup {
            override suspend fun getPlaceIds(lat: Double, lon: Double): List<Long> =
                client.getNearbyCountryPlaces(lat, lon)
            override suspend fun checkInPlaces(scientificName: String, placeIds: List<Long>): Boolean =
                client.hasObservationsInPlaces(scientificName, placeIds)
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
