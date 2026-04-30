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
    }

    val minConfidenceDisplay: Flow<Float> = ctx.dataStore.data.map { it[K.MIN_CONF] ?: DEFAULT_MIN_CONF }
    val inatToken: Flow<String?> = ctx.dataStore.data.map { it[K.INAT_TOKEN]?.takeIf(String::isNotBlank) }
    val inatLogin: Flow<String?> = ctx.dataStore.data.map { it[K.INAT_LOGIN]?.takeIf(String::isNotBlank) }
    val regionalFilterEnabled: Flow<Boolean> = ctx.dataStore.data.map { it[K.REGION_FILTER_ENABLED] ?: true }
    val regionRadiusKm: Flow<Int> = ctx.dataStore.data.map { it[K.REGION_RADIUS_KM] ?: DEFAULT_REGION_RADIUS_KM }
    val lastKnownLat: Flow<Double?> = ctx.dataStore.data.map { it[K.LAST_KNOWN_LAT] }
    val lastKnownLon: Flow<Double?> = ctx.dataStore.data.map { it[K.LAST_KNOWN_LON] }
    val minWindows: Flow<Int> = ctx.dataStore.data.map { it[K.MIN_WINDOWS] ?: DEFAULT_MIN_WINDOWS }

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
    }
}
