package dev.app8.engine.render.controls

import dev.app8.engine.maps.App8Coordinate
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** iOS `CMapViewModel` value handling, pure for tests. */
internal object MapModel {
    /** A raw annotation (from `annotationsExpression`) before colors are parsed. */
    data class RawAnnotation(
        val id: String,
        val coordinate: App8Coordinate,
        val title: String?,
        val subtitle: String?,
        val image: String?,
        val color: String?,
        val fields: Map<String, Any?>,
    )

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    /** `{latitude, longitude}` (numbers or numeric strings). */
    fun coordinate(v: Any?): App8Coordinate? {
        val m = v as? Map<*, *> ?: return null
        val lat = num(m["latitude"]) ?: return null
        val lng = num(m["longitude"]) ?: return null
        return App8Coordinate(lat, lng)
    }

    /**
     * One element of an `annotationsExpression` array: the coordinate under `coordinate`,
     * `location` or the object itself; `title`/`name`, `subtitle`/`address`/`description`,
     * `image`/`icon`; the index when there is no `id`.
     */
    fun annotation(value: Any?, index: Int): RawAnnotation? {
        val m = value as? Map<*, *> ?: return null
        val c = coordinate(m["coordinate"]) ?: coordinate(m["location"]) ?: coordinate(m) ?: return null
        fun s(k: String) = m[k] as? String
        @Suppress("UNCHECKED_CAST")
        return RawAnnotation(
            id = s("id") ?: index.toString(),
            coordinate = c,
            title = s("title") ?: s("name"),
            subtitle = s("subtitle") ?: s("address") ?: s("description"),
            image = s("image") ?: s("icon"),
            color = s("color"),
            fields = m.entries.associate { (k, v) -> k.toString() to v },
        )
    }

    /** `selectedAnnotationBinding` and friends are written as `{{name}}` or `name`. */
    fun bindingName(binding: String): String = binding.replace("{{", "").replace("}}", "").trim()

    fun regionValue(center: App8Coordinate, latDelta: Double, lngDelta: Double): Map<String, Any?> = mapOf(
        "center" to mapOf("latitude" to center.latitude, "longitude" to center.longitude),
        "span" to mapOf("latitudeDelta" to latDelta, "longitudeDelta" to lngDelta),
    )

    /** Web-Mercator y of a latitude, in [0,1] world units. */
    fun mercatorY(latitude: Double): Double {
        val r = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2
    }

    /**
     * Where [point] falls in a [width]×[height] viewport showing [center] ± span/2
     * (equirectangular in x, Mercator in y). Null when outside.
     */
    fun project(point: App8Coordinate, center: App8Coordinate, latDelta: Double, lngDelta: Double, width: Float, height: Float): Pair<Float, Float>? {
        if (latDelta <= 0 || lngDelta <= 0) return null
        val x = ((point.longitude - center.longitude) / lngDelta + 0.5) * width
        val top = mercatorY(center.latitude + latDelta / 2)
        val bottom = mercatorY(center.latitude - latDelta / 2)
        val y = (mercatorY(point.latitude) - top) / (bottom - top) * height
        if (x < 0 || x > width || y < 0 || y > height) return null
        return x.toFloat() to y.toFloat()
    }

    /** A center and span covering every point, padded 20% (the camera fit when no `center` is given). */
    fun fit(points: List<App8Coordinate>): Pair<App8Coordinate, Pair<Double, Double>>? {
        if (points.isEmpty()) return null
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }
        val c = App8Coordinate((minLat + maxLat) / 2, (minLng + maxLng) / 2)
        return c to (maxOf((maxLat - minLat) * 1.4, 0.01) to maxOf((maxLng - minLng) * 1.4, 0.01))
    }
}
