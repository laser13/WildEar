package com.sound2inat.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class FusedLocationProvider(context: Context) : LocationProvider {

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
        return suspendCancellableCoroutine<Fix?> { cont ->
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
}
