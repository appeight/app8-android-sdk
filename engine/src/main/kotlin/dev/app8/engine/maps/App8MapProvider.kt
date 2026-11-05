package dev.app8.engine.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Draws the `map` component. The SDK ships no map SDK of its own (Google Maps needs an API
 * key and Play services): without a provider a `map` renders a static placeholder tile with
 * its pins and reports `map.provider.missing`. Add the optional `engine-maps` module and pass
 * its `GoogleMapsProvider` in `App8Config.mapProvider`, or implement this for another SDK.
 *
 * The SDK resolves the DSL (expressions, annotation sources, bindings) into [App8MapState];
 * a provider only draws it and reports user interaction through its callbacks.
 */
public fun interface App8MapProvider {
    @Composable
    public fun Map(state: App8MapState, modifier: Modifier)
}

@Immutable
public data class App8Coordinate(val latitude: Double, val longitude: Double)

/** A visible region's extent in degrees (MKCoordinateSpan). */
@Immutable
public data class App8CoordinateSpan(val latitudeDelta: Double, val longitudeDelta: Double)

public enum class App8MapType { STANDARD, SATELLITE, HYBRID, MUTED }

/** A resolved pin. [color] is the DSL's or the style's `annotationTintColor`; [image] a symbol or asset name. */
@Immutable
public data class App8MapAnnotation(
    val id: String,
    val coordinate: App8Coordinate,
    val title: String? = null,
    val subtitle: String? = null,
    val image: String? = null,
    val color: Color? = null,
)

/** Insets (points) the camera keeps its content clear of. */
@Immutable
public data class App8MapInsets(val top: Double = 0.0, val left: Double = 0.0, val bottom: Double = 0.0, val right: Double = 0.0)

/**
 * Everything a provider draws, resolved for the current variable values. Callbacks write the
 * DSL bindings (`regionBinding`, `selectedAnnotationBinding`, `userLocationBinding`) and fire
 * the `onAnnotationTap` / `onRegionChange` / `onUserLocationUpdate` actions.
 *
 * @property route the `showDirections` endpoints. Road routing needs a directions service,
 *   which providers may lack; they then draw a straight geodesic segment.
 */
@Immutable
public data class App8MapState(
    val center: App8Coordinate?,
    val span: App8CoordinateSpan,
    val mapType: App8MapType,
    val annotations: List<App8MapAnnotation>,
    val selectedAnnotationId: String?,
    val polyline: List<App8Coordinate>,
    val route: Pair<App8Coordinate, App8Coordinate>?,
    val routeColor: Color?,
    val routeWidth: Double,
    val annotationTint: Color?,
    val showUserLocation: Boolean,
    val zoomEnabled: Boolean,
    val scrollEnabled: Boolean,
    val rotateEnabled: Boolean,
    val pitchEnabled: Boolean,
    val viewportInsets: App8MapInsets,
    val onAnnotationTap: (String) -> Unit,
    val onAnnotationDeselect: () -> Unit,
    val onRegionChange: (App8Coordinate, App8CoordinateSpan) -> Unit,
    val onUserLocationUpdate: (App8Coordinate) -> Unit,
)
