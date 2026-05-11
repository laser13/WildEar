package com.sound2inat.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("sound2inat")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class Settings(private val ctx: Context) {
    private object K {
        val MIN_CONF = floatPreferencesKey("min_conf")
        val INAT_TOKEN = stringPreferencesKey("inat_token")
        val INAT_LOGIN = stringPreferencesKey("inat_login")
        val REGION_FILTER_ENABLED = booleanPreferencesKey("region_filter_enabled")
        val REGION_RADIUS_KM = intPreferencesKey("region_radius_km")
        val LAST_KNOWN_LAT = doublePreferencesKey("last_known_lat")
        val LAST_KNOWN_LON = doublePreferencesKey("last_known_lon")
        val MIN_WINDOWS = intPreferencesKey("min_windows")

        // spectral_subtraction_enabled key intentionally removed — denoising disabled
        val YAMNET_GATE_ENABLED = booleanPreferencesKey("yamnet_gate_enabled")
        val BIRDNET_META_ENABLED = booleanPreferencesKey("birdnet_meta_enabled")
        val RADAR_RADIUS_KM = intPreferencesKey("radar_radius_km")
        val RADAR_PERIOD_DAYS = intPreferencesKey("radar_period_days")
        val RADAR_TAXA = stringPreferencesKey("radar_taxa")
        val ALLOW_DELETE_UPLOADED = booleanPreferencesKey("allow_delete_uploaded")
        val AUDIO_SOURCE_RAW = booleanPreferencesKey("audio_source_raw")
        val NORMALIZE_AUDIO = booleanPreferencesKey("normalize_audio")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val minConfidenceDisplay: Flow<Float> = ctx.dataStore.data.map { it[K.MIN_CONF] ?: DEFAULT_MIN_CONF }
    val inatToken: Flow<String?> = ctx.dataStore.data.map { it[K.INAT_TOKEN]?.takeIf(String::isNotBlank) }
    val inatLogin: Flow<String?> = ctx.dataStore.data.map { it[K.INAT_LOGIN]?.takeIf(String::isNotBlank) }
    val regionalFilterEnabled: Flow<Boolean> = ctx.dataStore.data.map { it[K.REGION_FILTER_ENABLED] ?: true }
    val regionRadiusKm: Flow<Int> = ctx.dataStore.data.map { it[K.REGION_RADIUS_KM] ?: DEFAULT_REGION_RADIUS_KM }
    val lastKnownLat: Flow<Double?> = ctx.dataStore.data.map { it[K.LAST_KNOWN_LAT] }
    val lastKnownLon: Flow<Double?> = ctx.dataStore.data.map { it[K.LAST_KNOWN_LON] }
    val minWindows: Flow<Int> = ctx.dataStore.data.map { it[K.MIN_WINDOWS] ?: DEFAULT_MIN_WINDOWS }
    val spectralSubtractionEnabled: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    val yamNetGateEnabled: Flow<Boolean> =
        ctx.dataStore.data.map { it[K.YAMNET_GATE_ENABLED] ?: true }
    val birdNetMetaEnabled: Flow<Boolean> =
        ctx.dataStore.data.map { it[K.BIRDNET_META_ENABLED] ?: true }
    val radarRadiusKm: Flow<Int> =
        ctx.dataStore.data.map { it[K.RADAR_RADIUS_KM] ?: DEFAULT_RADAR_RADIUS_KM }
    val radarPeriodDays: Flow<Int> =
        ctx.dataStore.data.map { it[K.RADAR_PERIOD_DAYS] ?: DEFAULT_RADAR_PERIOD_DAYS }
    val allowDeleteUploaded: Flow<Boolean> =
        ctx.dataStore.data.map { it[K.ALLOW_DELETE_UPLOADED] ?: false }
    val audioSourceRaw: Flow<Boolean> =
        ctx.dataStore.data.map { it[K.AUDIO_SOURCE_RAW] ?: false }

    /** Consumed by PostRecordingProcessor — applied after recording stops. */
    val normalizeAudio: Flow<Boolean> =
        ctx.dataStore.data.map { it[K.NORMALIZE_AUDIO] ?: true }
    val themeMode: Flow<ThemeMode> = ctx.dataStore.data.map {
        when (it[K.THEME_MODE]) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    val radarTaxa: Flow<Set<String>> = ctx.dataStore.data.map {
        it[K.RADAR_TAXA]?.takeIf(String::isNotEmpty)?.split(',')?.toSet() ?: emptySet()
    }

    suspend fun setMinConfidenceDisplay(v: Float) { ctx.dataStore.edit { it[K.MIN_CONF] = v } }
    suspend fun setInatToken(v: String?) {
        ctx.dataStore.edit {
            if (v.isNullOrBlank()) it.remove(K.INAT_TOKEN) else it[K.INAT_TOKEN] = v
        }
    }
    suspend fun setInatLogin(v: String?) {
        ctx.dataStore.edit {
            if (v.isNullOrBlank()) it.remove(K.INAT_LOGIN) else it[K.INAT_LOGIN] = v
        }
    }
    suspend fun setRegionalFilterEnabled(v: Boolean) { ctx.dataStore.edit { it[K.REGION_FILTER_ENABLED] = v } }
    suspend fun setRegionRadiusKm(v: Int) { ctx.dataStore.edit { it[K.REGION_RADIUS_KM] = v } }
    suspend fun setMinWindows(v: Int) { ctx.dataStore.edit { it[K.MIN_WINDOWS] = v } }
    suspend fun setYamNetGateEnabled(v: Boolean) {
        ctx.dataStore.edit { it[K.YAMNET_GATE_ENABLED] = v }
    }
    suspend fun setBirdNetMetaEnabled(v: Boolean) {
        ctx.dataStore.edit { it[K.BIRDNET_META_ENABLED] = v }
    }
    suspend fun setRadarRadiusKm(v: Int) {
        ctx.dataStore.edit { it[K.RADAR_RADIUS_KM] = v }
    }
    suspend fun setRadarPeriodDays(v: Int) {
        ctx.dataStore.edit { it[K.RADAR_PERIOD_DAYS] = v }
    }
    suspend fun setAllowDeleteUploaded(v: Boolean) {
        ctx.dataStore.edit { it[K.ALLOW_DELETE_UPLOADED] = v }
    }
    suspend fun setAudioSourceRaw(v: Boolean) { ctx.dataStore.edit { it[K.AUDIO_SOURCE_RAW] = v } }
    suspend fun setNormalizeAudio(v: Boolean) { ctx.dataStore.edit { it[K.NORMALIZE_AUDIO] = v } }
    suspend fun setThemeMode(v: ThemeMode) {
        ctx.dataStore.edit { it[K.THEME_MODE] = v.name.lowercase() }
    }

    suspend fun setRadarTaxa(v: Set<String>) {
        // Comma is the on-disk delimiter; reject any taxon id that contains
        // one so a future caller can't accidentally split a single value
        // across two entries on roundtrip. iNat's iconic_taxa values are
        // single-word slugs ("Aves", "Mammalia", …) so this is purely
        // defensive against a misuse downstream.
        require(v.none { ',' in it }) { "Taxon ids must not contain commas: $v" }
        ctx.dataStore.edit {
            if (v.isEmpty()) it.remove(K.RADAR_TAXA) else it[K.RADAR_TAXA] = v.joinToString(",")
        }
    }
    suspend fun setLastKnownCoords(lat: Double, lon: Double) {
        ctx.dataStore.edit {
            it[K.LAST_KNOWN_LAT] = lat
            it[K.LAST_KNOWN_LON] = lon
        }
    }

    companion object {
        const val DEFAULT_MIN_CONF = 0.25f
        const val DEFAULT_REGION_RADIUS_KM = 200
        const val DEFAULT_MIN_WINDOWS = 2
        const val DEFAULT_RADAR_RADIUS_KM = 5
        const val DEFAULT_RADAR_PERIOD_DAYS = 7
    }
}
