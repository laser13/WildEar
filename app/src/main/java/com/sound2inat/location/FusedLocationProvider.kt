package com.sound2inat.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class FusedLocationProvider(
    context: Context,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : LocationProvider {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override suspend fun getCurrent(timeoutMs: Long): Fix? {
        val live = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Fix?> { cont ->
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        cont.resume(
                            loc?.let {
                                Fix(
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    accuracyMeters = it.accuracy.takeIf { a -> a > 0f },
                                    timestampMs = it.time,
                                )
                            },
                        )
                    }
                    .addOnFailureListener { cont.resume(null) }
            }
        }
        if (live != null) return live
        val stale = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Fix?> { cont ->
                client.lastLocation
                    .addOnSuccessListener { loc ->
                        cont.resume(
                            loc?.let {
                                Fix(
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    accuracyMeters = it.accuracy.takeIf { a -> a > 0f },
                                    timestampMs = it.time,
                                )
                            },
                        )
                    }
                    .addOnFailureListener { cont.resume(null) }
            }
        }
        // Reject lastLocation older than MAX_LAST_LOCATION_AGE_MS: a coordinate
        // from hours/days ago is worse than null for a wildlife observation —
        // it silently geo-tags the sighting at the wrong place.
        return stale?.takeIf { isFresh(it, nowMs()) }
    }

    companion object {
        const val LOCATION_TIMEOUT_MS = 5_000L
        const val MAX_LAST_LOCATION_AGE_MS = 5 * 60 * 1_000L // 5 minutes

        /**
         * True if [fix] was sampled no more than [MAX_LAST_LOCATION_AGE_MS] ago
         * relative to [nowMs]. Used by `getCurrent()` to reject stale lastLocation
         * fallback values (e.g. device was in airplane mode for hours).
         */
        internal fun isFresh(fix: Fix, nowMs: Long): Boolean =
            nowMs - fix.timestampMs <= MAX_LAST_LOCATION_AGE_MS
    }
}
