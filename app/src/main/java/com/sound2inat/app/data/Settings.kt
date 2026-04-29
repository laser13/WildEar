package com.sound2inat.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("sound2inat")

class Settings(private val ctx: Context) {
    private object K {
        val MIN_CONF = floatPreferencesKey("min_conf")
        val TOP_K = intPreferencesKey("top_k")
    }

    val minConfidenceDisplay: Flow<Float> = ctx.dataStore.data.map { it[K.MIN_CONF] ?: DEFAULT_MIN_CONF }
    val topK: Flow<Int> = ctx.dataStore.data.map { it[K.TOP_K] ?: DEFAULT_TOP_K }

    suspend fun setMinConfidenceDisplay(v: Float) { ctx.dataStore.edit { it[K.MIN_CONF] = v } }
    suspend fun setTopK(v: Int) { ctx.dataStore.edit { it[K.TOP_K] = v } }

    companion object {
        const val DEFAULT_MIN_CONF = 0.25f
        const val DEFAULT_TOP_K = 5
    }
}
