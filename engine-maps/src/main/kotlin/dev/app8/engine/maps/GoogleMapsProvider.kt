package dev.app8.engine.maps

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.ln
import kotlin.math.min

/**
 * [App8MapProvider] on Google Maps (maps-compose). Pins are default markers in the
 * annotation color; `polyline` and `showDirections` draw as polylines (directions as a
 * straight geodesic segment: road routing needs the Directions API); gestures follow the
 * DSL flags; the camera follows `center`/`span` and reports user moves as region changes.
 *
 * Requires a Maps API key in the host manifest (`com.google.android.geo.API_KEY`). The
 * user-location dot shows only when the host already holds a location permission.
 */
public class GoogleMapsProvider : App8MapProvider {
    @Composable
    override fun Map(state: App8MapState, modifier: Modifier) {
        BoxWithConstraints(modifier) {
            val widthDp = maxWidth.value.toDouble()
            val heightDp = maxHeight.value.toDouble()
            val camera = rememberCameraPositionState()
            val center = state.center
            LaunchedEffect(center, state.span, widthDp, heightDp) {
                if (center == null || widthDp <= 0) return@LaunchedEffect
                camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, center.longitude), zoomFor(state.span, widthDp, heightDp)))
            }
            // User pans/zooms → region change once the camera settles.
            LaunchedEffect(camera.isMoving) {
                if (camera.isMoving || camera.cameraMoveStartedReason != CameraMoveStartedReason.GESTURE) return@LaunchedEffect
                val b = camera.projection?.visibleRegion?.latLngBounds ?: return@LaunchedEffect
                val c = b.center
                state.onRegionChange(
                    App8Coordinate(c.latitude, c.longitude),
                    App8CoordinateSpan(b.northeast.latitude - b.southwest.latitude, (b.northeast.longitude - b.southwest.longitude + 360) % 360),
                )
            }
            val context = LocalContext.current
            val canLocate = state.showUserLocation && (
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                )
            val insets = state.viewportInsets
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                properties = MapProperties(
                    mapType = when (state.mapType) {
                        App8MapType.SATELLITE -> MapType.SATELLITE
                        App8MapType.HYBRID -> MapType.HYBRID
                        App8MapType.STANDARD, App8MapType.MUTED -> MapType.NORMAL
                    },
                    isMyLocationEnabled = canLocate,
                ),
                uiSettings = MapUiSettings(
                    zoomGesturesEnabled = state.zoomEnabled,
                    scrollGesturesEnabled = state.scrollEnabled,
                    rotationGesturesEnabled = state.rotateEnabled,
                    tiltGesturesEnabled = state.pitchEnabled,
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                ),
                contentPadding = PaddingValues(insets.left.dp, insets.top.dp, insets.right.dp, insets.bottom.dp),
                onMapClick = { state.onAnnotationDeselect() },
                onMyLocationClick = { l -> state.onUserLocationUpdate(App8Coordinate(l.latitude, l.longitude)) },
            ) {
                for (a in state.annotations) {
                    key(a.id) {
                        val ms = remember { MarkerState(LatLng(a.coordinate.latitude, a.coordinate.longitude)) }
                        ms.position = LatLng(a.coordinate.latitude, a.coordinate.longitude)
                        Marker(
                            state = ms,
                            title = a.title,
                            snippet = a.subtitle,
                            icon = BitmapDescriptorFactory.defaultMarker(hue(a.color ?: state.annotationTint ?: Color(0xFFFF3B30))),
                            zIndex = if (a.id == state.selectedAnnotationId) 1f else 0f,
                            onClick = { state.onAnnotationTap(a.id); false },
                        )
                    }
                }
                val routeColor = state.routeColor ?: Color(0xFF007AFF)
                val width = (state.routeWidth * context.resources.displayMetrics.density).toFloat()
                if (state.polyline.size >= 2) {
                    Polyline(points = state.polyline.map { LatLng(it.latitude, it.longitude) }, color = routeColor, width = width)
                }
                state.route?.let { (from, to) ->
                    Polyline(points = listOf(LatLng(from.latitude, from.longitude), LatLng(to.latitude, to.longitude)), color = routeColor, width = width, geodesic = true)
                }
            }
        }
    }

    private fun hue(color: Color): Float {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        return hsv[0]
    }

    /** The zoom showing [span] in a [widthDp]×[heightDp] map (256dp world tiles). */
    private fun zoomFor(span: App8CoordinateSpan, widthDp: Double, heightDp: Double): Float {
        val byLng = log2(360.0 * widthDp / (256.0 * span.longitudeDelta.coerceAtLeast(1e-6)))
        val byLat = log2(180.0 * heightDp / (256.0 * span.latitudeDelta.coerceAtLeast(1e-6)))
        return min(byLng, byLat).coerceIn(1.0, 21.0).toFloat()
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
