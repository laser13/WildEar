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
    private val memoryCache = object : LinkedHashMap<String, ReviewSpectrogramDisplayPlane>(
        MAX_CACHED_PLANES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ReviewSpectrogramDisplayPlane>?
        ): Boolean =
            size > MAX_CACHED_PLANES
    }

    suspend fun getOrCreate(
        key: String,
        draftId: String,
        audioName: String,
        build: suspend () -> ReviewSpectrogramDisplayPlane,
    ): ReviewSpectrogramDisplayPlane {
        // Fast path: cache hit under lock (cheap).
        mutex.withLock {
            memoryCache[key]?.let {
                Log.d(
                    "ReviewVisuals",
                    "display-plane-cache-hit draft=$draftId file=$audioName rows=${it.height} cols=${it.width}",
                )
                return it
            }
        }
        // Slow path: build outside the lock so concurrent requests for OTHER
        // keys do not block on us.
        Log.d("ReviewVisuals", "display-plane-cache-miss draft=$draftId file=$audioName")
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val plane = build()
        Log.i(
            "ReviewVisuals",
            "display-plane-build draft=$draftId elapsed=${android.os.SystemClock.elapsedRealtime() - startedAt}ms rows=${plane.height} cols=${plane.width}",
        )
        // Insert; if a parallel caller for the same key already won, keep theirs.
        return mutex.withLock {
            val stored = memoryCache.getOrPut(key) { plane }
            if (stored !== plane) {
                Log.d(
                    "ReviewVisuals",
                    "display-plane-build-wasted draft=$draftId file=$audioName (lost race)",
                )
            }
            stored
        }
    }

    private companion object {
        // Each plane holds W*H floats (often 2000x200 → ~1.6 MB) plus its derived
        // bitmap downstream. Keeping more than two recent variants alive when the
        // user is tweaking palette/range chips bloats the heap fast.
        private const val MAX_CACHED_PLANES = 2
    }
}
