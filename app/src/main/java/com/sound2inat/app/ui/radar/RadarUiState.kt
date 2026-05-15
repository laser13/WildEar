package com.sound2inat.app.ui.radar

import com.sound2inat.inat.MapPin
import com.sound2inat.inat.SpeciesAggregate
import org.osmdroid.util.GeoPoint

/** What the user picked: filter selections + current location. */
data class FilterState(
    val radiusKm: Int,
    val periodDays: Int,
    val taxa: Set<String>,
    val userLocation: GeoPoint?,
)

/** Status of the location subsystem at the moment the screen is rendering. */
sealed interface LocationStatus {
    data object Loading : LocationStatus
    data class Live(val accuracyM: Float) : LocationStatus
    data object FallbackToLastKnown : LocationStatus
    data object NoLocation : LocationStatus
}

/** Compose-side state: everything `RadarScreen` reads from the VM. */
data class RadarUiState(
    val filter: FilterState,
    val species: List<SpeciesAggregate> = emptyList(),
    val pins: List<MapPin> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val locationStatus: LocationStatus = LocationStatus.Loading,
)
