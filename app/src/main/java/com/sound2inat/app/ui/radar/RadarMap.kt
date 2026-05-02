package com.sound2inat.app.ui.radar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Compose wrapper around OSMDroid [MapView]. Renders one marker per [pin]
 * plus an optional "you are here" marker at [userLocation]. Tapping a pin
 * calls [onPinTap] with the iNat observation URL.
 *
 * The `update` lambda clears and re-adds all overlays on each recomposition.
 * With ≤200 pins the cost is negligible.
 */
@Composable
internal fun RadarMap(
    pins: List<MapPin>,
    userLocation: GeoPoint?,
    onPinTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
        }
    }
    AndroidView(
        factory = { mapView },
        update = { map ->
            map.overlays.clear()
            userLocation?.let { gp ->
                map.controller.setCenter(gp)
                map.overlays += Marker(map).apply {
                    position = gp
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "You"
                }
            }
            for (p in pins) {
                map.overlays += Marker(map).apply {
                    position = GeoPoint(p.lat, p.lon)
                    title = p.scientificName
                    setOnMarkerClickListener { _, _ ->
                        onPinTap(p.obsUrl)
                        true
                    }
                }
            }
            map.invalidate()
        },
        modifier = modifier,
    )
}
