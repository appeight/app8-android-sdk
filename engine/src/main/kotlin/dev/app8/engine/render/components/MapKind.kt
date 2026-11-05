package dev.app8.engine.render.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.MapProperties
import dev.app8.engine.core.model.style.MapStyle
import dev.app8.engine.core.vars.Values
import dev.app8.engine.maps.App8Coordinate
import dev.app8.engine.maps.App8CoordinateSpan
import dev.app8.engine.maps.App8MapAnnotation
import dev.app8.engine.maps.App8MapInsets
import dev.app8.engine.maps.App8MapState
import dev.app8.engine.maps.App8MapType
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.controls.MapModel
import dev.app8.engine.render.resolve
import dev.app8.engine.render.string
import dev.app8.engine.style.DslCornerShape
import dev.app8.engine.style.IosSystemColors

/**
 * `map`: the DSL resolved into [App8MapState] (center/span, static or expression
 * annotations, polyline, route endpoints, bindings) and drawn by the host's
 * [dev.app8.engine.maps.App8MapProvider]. Without one: a static placeholder tile with the
 * pins and their titles, plus a `map.provider.missing` diagnostic.
 */
internal object MapKind : ComponentKind {
    override val handlesTaps: Boolean get() = true

    private fun coordinate(node: RenderNode, v: MapProperties.CoordinateValue?): App8Coordinate? = when (v) {
        is MapProperties.CoordinateValue.Static -> App8Coordinate(v.coordinate.latitude, v.coordinate.longitude)
        is MapProperties.CoordinateValue.Expression -> MapModel.coordinate(node.resolve(v.expression))
        null -> null
    }

    private fun annotations(node: RenderNode, p: MapProperties, tint: Color?): Pair<List<App8MapAnnotation>, Map<String, Map<String, Any?>>> {
        val raws: List<MapModel.RawAnnotation> = p.annotationsExpression?.let { expr ->
            (node.resolve(expr) as? List<*>)?.mapIndexedNotNull { i, v -> MapModel.annotation(v, i) }.orEmpty()
        } ?: p.annotations.orEmpty().mapNotNull { a ->
            val c = coordinate(node, a.coordinate) ?: return@mapNotNull null
            MapModel.RawAnnotation(
                a.id, c, node.string(a.title), node.string(a.subtitle), node.string(a.image), node.string(a.color),
                a.rawFields.mapValues { (_, v) -> Values.fromJson(v).takeUnless { it === Values.Null } },
            )
        }
        val list = raws.map { r -> App8MapAnnotation(r.id, r.coordinate, r.title, r.subtitle, r.image, hexColor(r.color) ?: tint) }
        return list to raws.associate { it.id to it.fields }
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val dispatcher = LocalDispatcher.current
        val p = node.content.properties as? MapProperties ?: MapProperties()
        val style = node.content.style as? MapStyle
        val tint = hexColor(style?.annotationTintColor)
        val (pins, fields) = annotations(node, p, tint)
        val polyline = when (val pl = p.polyline) {
            is MapProperties.PolylineValue.Coordinates -> pl.coordinates.map { App8Coordinate(it.latitude, it.longitude) }
            is MapProperties.PolylineValue.Expression -> (node.resolve(pl.expression) as? List<*>)?.mapNotNull { MapModel.coordinate(it) }.orEmpty()
            null -> emptyList()
        }
        val route = if (p.showDirections == true) {
            val a = coordinate(node, p.routeFrom)
            val b = coordinate(node, p.routeTo)
            if (a != null && b != null) a to b else null
        } else null
        val explicitCenter = coordinate(node, p.center)
        val fit = if (explicitCenter == null) MapModel.fit(pins.map { it.coordinate } + polyline + listOfNotNull(route?.first, route?.second)) else null
        val center = explicitCenter ?: fit?.first
        val span = p.span?.let { App8CoordinateSpan(it.latitudeDelta, it.longitudeDelta) }
            ?: fit?.second?.let { App8CoordinateSpan(it.first, it.second) }
            ?: App8CoordinateSpan(0.1, 0.1)
        val selected = p.selectedAnnotationBinding?.let { b -> node.resolve("{{${MapModel.bindingName(b)}}}") as? String }?.takeIf { it.isNotEmpty() }

        fun write(binding: String?, value: Any?) = binding?.let { node.writeBound(MapModel.bindingName(it), value) }

        val state = App8MapState(
            center = center,
            span = span,
            mapType = when (p.mapType) {
                MapProperties.MapType.satellite, MapProperties.MapType.satelliteFlyover -> App8MapType.SATELLITE
                MapProperties.MapType.hybrid, MapProperties.MapType.hybridFlyover -> App8MapType.HYBRID
                MapProperties.MapType.mutedStandard -> App8MapType.MUTED
                else -> App8MapType.STANDARD
            },
            annotations = pins,
            selectedAnnotationId = selected,
            polyline = polyline,
            route = route,
            routeColor = hexColor(style?.routeColor),
            routeWidth = style?.routeWidth ?: 5.0,
            annotationTint = tint,
            showUserLocation = p.showUserLocation == true,
            zoomEnabled = p.zoomEnabled != false,
            scrollEnabled = p.scrollEnabled != false,
            rotateEnabled = p.rotateEnabled != false,
            pitchEnabled = p.pitchEnabled != false,
            viewportInsets = p.viewportInsets.let { App8MapInsets(it?.top ?: 0.0, it?.left ?: 0.0, it?.bottom ?: 0.0, it?.right ?: 0.0) },
            onAnnotationTap = { id ->
                write(p.selectedAnnotationBinding, id)
                // Actions see the annotation's raw object as `item` (iOS scoped `item` variable).
                dispatcher.dispatchTrigger(node, ActionTrigger.onAnnotationTap, node.scope.untracked.overlaying("item", fields[id].orEmpty()))
            },
            onAnnotationDeselect = { write(p.selectedAnnotationBinding, "") },
            onRegionChange = { c, s ->
                write(p.regionBinding, MapModel.regionValue(c, s.latitudeDelta, s.longitudeDelta))
                dispatcher.dispatchTrigger(node, ActionTrigger.onRegionChange)
            },
            onUserLocationUpdate = { c ->
                write(p.userLocationBinding, mapOf("latitude" to c.latitude, "longitude" to c.longitude))
                dispatcher.dispatchTrigger(node, ActionTrigger.onUserLocationUpdate)
            },
        )
        var m = modifier.clipToBounds()
        style?.corner?.let { m = m.clip(DslCornerShape(it)) }
        val provider = node.runtime.mapProvider
        Box(m) {
            if (provider != null) {
                provider.Map(state, Modifier.fillMaxSize())
            } else {
                remember(node) { node.runtime.diagnostics.warn("map.provider.missing", "no App8MapProvider; '${node.id}' shows a static placeholder (add engine-maps)") }
                Placeholder(state, env.appearance)
            }
            ChildrenLayout(node, Modifier.fillMaxSize())
        }
    }

    /** A static tile: neutral land, a street grid, the route/polyline, pins, and the pin titles. */
    @Composable
    private fun Placeholder(state: App8MapState, appearance: dev.app8.engine.style.App8Appearance) {
        val dark = appearance.isDark
        val land = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2EFE9)
        val street = if (dark) Color(0xFF3A3A3C) else Color.White
        val pinDefault = IosSystemColors.of("systemRed", appearance)
        val routeColor = state.routeColor ?: IosSystemColors.of("systemBlue", appearance)
        Box(Modifier.fillMaxSize().background(land)) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(state.annotations, state.center) {
                    detectTapGestures { pos ->
                        val c = state.center ?: return@detectTapGestures
                        val hit = state.annotations.mapNotNull { a ->
                            MapModel.project(a.coordinate, c, state.span.latitudeDelta, state.span.longitudeDelta, size.width.toFloat(), size.height.toFloat())
                                ?.let { (x, y) -> a to Offset(x, y - 14.dp.toPx()) }
                        }.minByOrNull { (_, o) -> (o - pos).getDistance() }
                        if (hit != null && (hit.second - pos).getDistance() < 28.dp.toPx()) state.onAnnotationTap(hit.first.id)
                        else state.onAnnotationDeselect()
                    }
                },
            ) {
                val step = 48.dp.toPx()
                var x = step / 2
                while (x < size.width) { drawLine(street, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx()); x += step }
                var y = step / 3
                while (y < size.height) { drawLine(street, Offset(0f, y), Offset(size.width, y), 3.dp.toPx()); y += step }
                val c = state.center ?: return@Canvas
                fun proj(p: App8Coordinate) = MapModel.project(p, c, state.span.latitudeDelta, state.span.longitudeDelta, size.width, size.height)
                    ?.let { Offset(it.first, it.second) }
                val line = state.polyline.ifEmpty { state.route?.let { listOf(it.first, it.second) }.orEmpty() }
                if (line.size >= 2) {
                    val path = Path()
                    line.mapNotNull(::proj).forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                    drawPath(path, routeColor, style = Stroke(state.routeWidth.toFloat().dp.toPx()))
                }
                for (a in state.annotations) {
                    val o = proj(a.coordinate) ?: continue
                    val r = (if (a.id == state.selectedAnnotationId) 12 else 9).dp.toPx()
                    val color = a.color ?: pinDefault
                    val pin = Path().apply {
                        moveTo(o.x, o.y)
                        lineTo(o.x - r * 0.6f, o.y - r * 1.3f)
                        lineTo(o.x + r * 0.6f, o.y - r * 1.3f)
                        close()
                    }
                    drawPath(pin, color)
                    drawCircle(color, r, Offset(o.x, o.y - r * 1.6f))
                    drawCircle(Color.White, r * 0.38f, Offset(o.x, o.y - r * 1.6f))
                }
            }
            val titled = state.annotations.filter { !it.title.isNullOrEmpty() }
            if (titled.isNotEmpty()) {
                Column(
                    Modifier.align(Alignment.BottomStart).padding(8.dp)
                        .background(if (dark) Color(0xCC1C1C1E) else Color(0xE6FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val text = TextStyle(color = if (dark) Color.White else Color.Black, fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp))
                    for (a in titled.take(4)) BasicText("● ${a.title}", style = text.copy(fontWeight = if (a.id == state.selectedAnnotationId) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
                    if (titled.size > 4) BasicText("+${titled.size - 4}", style = text)
                }
            }
        }
    }
}
