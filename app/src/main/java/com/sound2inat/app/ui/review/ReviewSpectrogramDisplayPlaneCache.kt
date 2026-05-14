package com.sound2inat.app.ui.review

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small in-memory cache for expensive display-plane builds.
 *
 * Palette changes reuse the same display plane; only the final color mapping
 * should change. This cache keeps the normalized/smoothed plane alive so we
 * do not repeat that CPU-heavy work for every palette tweak.
 */
class ReviewSpectrogramDisplayPlaneCache {
    private val mutex = Mutex()
    private val memoryCache = object : LinkedHashMap<String, ReviewSpectrogramDisplayPlane>(MAX_CACHED_PLANES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReviewSpectrogramDisplayPlane>?): Boolean =
            size > MAX_CACHED_PLANES
    }

    suspend fun getOrCreate(
        key: String,
        draftId: String,
        audioName: String,
        build: suspend () -> ReviewSpectrogramDisplayPlane,
    ): ReviewSpectrogramDisplayPlane {
        return mutex.withLock {
            memoryCache[key]?.let {
                Log.d(
                    "ReviewVisuals",
                    "display-plane-cache-hit draft=$draftId file=$audioName rows=${it.height} cols=${it.width}",
                )
                return@withLock it
            }
            Log.d("ReviewVisuals", "display-plane-cache-miss draft=$draftId file=$audioName")
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val plane = build()
            Log.i(
                "ReviewVisuals",
                "display-plane-build draft=$draftId elapsed=${android.os.SystemClock.elapsedRealtime() - startedAt}ms rows=${plane.height} cols=${plane.width}",
            )
            memoryCache[key] = plane
            plane
        }
    }

    private companion object {
        private const val MAX_CACHED_PLANES = 4
    }
}
