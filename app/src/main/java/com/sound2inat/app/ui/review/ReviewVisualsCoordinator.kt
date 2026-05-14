package com.sound2inat.app.ui.review

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates expensive review-visual builds across pager pages.
 *
 * A single draft build can be shared by multiple callers, and only one build
 * runs at a time so pager preloading does not thrash disk and CPU.
 */
@Singleton
class ReviewVisualsCoordinator @Inject constructor(
    private val appScope: CoroutineScope,
) {
    private val stateMutex = Mutex()
    private val buildMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Visuals>>()
    private val memoryCache = object : LinkedHashMap<String, Visuals>(MAX_CACHED_VISUALS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Visuals>?): Boolean =
            size > MAX_CACHED_VISUALS
    }

    suspend fun getOrBuild(key: String, build: suspend () -> Visuals): Visuals {
        var cached: Visuals? = null
        var deferred: Deferred<Visuals>? = null
        stateMutex.withLock {
            memoryCache[key]?.let {
                cached = it
                return@withLock
            }
            inFlight[key]?.let {
                deferred = it
                return@withLock
            }
            val newDeferred = appScope.async(Dispatchers.Default) {
                try {
                    val result = buildMutex.withLock { build() }
                    stateMutex.withLock {
                        memoryCache[key] = result
                        inFlight.remove(key)
                    }
                    result
                } catch (t: Throwable) {
                    stateMutex.withLock {
                        inFlight.remove(key)
                    }
                    throw t
                }
            }
            inFlight[key] = newDeferred
            deferred = newDeferred
        }
        cached?.let { return it }
        return deferred!!.await()
    }

    private companion object {
        private const val MAX_CACHED_VISUALS = 4
    }
}
