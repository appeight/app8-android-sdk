package dev.app8.engine.core.model.style

import dev.app8.engine.core.model.Cursor
import dev.app8.engine.core.model.failableList
import dev.app8.engine.core.model.optEnum
import dev.app8.engine.core.model.optSafeEnum
import dev.app8.engine.core.model.safeList
import dev.app8.engine.core.model.strictEnum

/** Solid or gradient fill. Both fields are lenient (a bad value is dropped, never fails the fill). */
public data class Fill(
    val solid: Wrapped<ThemedColor> = Wrapped.none(),
    val gradient: Wrapped<Gradient> = Wrapped.none(),
) {
    public companion object {
        public val NONE: Fill = Fill()
    }
}

internal fun decodeFill(c: Cursor): Fill {
    c.obj()
    return Fill(solid = c.wrapped("solid", ::decodeThemedColor), gradient = c.wrapped("gradient", ::decodeGradient))
}

/** Linear gradient between [start] and [end] (unit coordinates) with optional [middlePoints]. */
public data class Gradient(
    val start: Vertex,
    val end: Vertex,
    val middlePoints: List<MiddlePoint> = emptyList(),
) {
    public data class Vertex(val x: Double, val y: Double, val color: Wrapped<ThemedColor> = Wrapped.none())
    public data class MiddlePoint(val position: Double, val color: Wrapped<ThemedColor> = Wrapped.none())
}

internal fun decodeGradient(c: Cursor): Gradient {
    c.obj()
    return Gradient(
        start = c.req("start", ::decodeVertex),
        end = c.req("end", ::decodeVertex),
        middlePoints = c.safeList("middlePoints", "Gradient.MiddlePoint") { m ->
            m.obj()
            Gradient.MiddlePoint(m.reqDouble("position"), m.wrapped("color", ::decodeThemedColor))
        },
    )
}

private fun decodeVertex(c: Cursor): Gradient.Vertex {
    c.obj()
    return Gradient.Vertex(c.reqDouble("x"), c.reqDouble("y"), c.wrapped("color", ::decodeThemedColor))
}

/** Corner radius and curve. `curve` defaults to circular. */
public data class Corner(val radius: Radius, val curve: Curve = Curve.circular) {

    @Suppress("EnumEntryName")
    public enum class Curve { circular, continuous }

    public sealed interface Radius {
        public data class Fixed(val value: Double) : Radius
        public data class Fraction(val value: Double) : Radius
        public data object Capsule : Radius

        public val isRelative: Boolean get() = this !is Fixed

        /** Radius in points for a [width] × [height] box. */
        public fun resolved(width: Double, height: Double): Double = when (this) {
            is Fixed -> maxOf(0.0, value)
            is Fraction -> maxOf(0.0, minOf(width, height) * value)
            Capsule -> maxOf(0.0, minOf(width, height) / 2)
        }
    }

    public companion object {
        public val NONE: Corner = Corner(Radius.Fixed(0.0), Curve.circular)
    }
}

internal fun decodeCorner(c: Cursor): Corner {
    c.obj()
    return Corner(
        radius = c.req("radius", ::decodeCornerRadius),
        curve = c.optSafeEnum("curve", Corner.Curve.circular) ?: Corner.Curve.circular,
    )
}

internal fun decodeCornerRadius(c: Cursor): Corner.Radius {
    c.doubleOrNull()?.let { return Corner.Radius.Fixed(it) }
    c.stringOrNull()?.let { s ->
        if (s == "capsule") return Corner.Radius.Capsule
        if (s.endsWith("%")) s.dropLast(1).toDoubleOrNull()?.let { return Corner.Radius.Fraction(it / 100) }
    }
    c.obj()
    return when (c.req("type") { it.strictEnum<RadiusType>() }) {
        RadiusType.capsule -> Corner.Radius.Capsule
        RadiusType.fixed -> Corner.Radius.Fixed(c.reqDouble("value"))
        RadiusType.fraction -> Corner.Radius.Fraction(c.reqDouble("value"))
    }
}

@Suppress("EnumEntryName")
private enum class RadiusType { fixed, fraction, capsule }

/**
 * Stack of material layers (fill / outline / shadow / corner / visualEffect / material).
 * Decoded from a JSON array: failing layers are dropped, non-material styles (and untyped pointers) are filtered out.
 */
public data class Material(val layers: List<StyleAny>)

internal fun decodeMaterial(c: Cursor): Material =
    Material(c.failableList("Style.Any", ::decodeStyleAny).filter { it.isMaterial })

/** Multi-layer shadow. Decoded from `{ "layers": [...] }`; bad layers are dropped. */
public data class Shadow(val layers: List<Layer> = emptyList()) {
    public data class Layer(
        val color: ThemedColor,
        val radius: Double,
        val offset: Offset,
        val opacity: Double,
        /** Required on iOS (synthesized decoding ignores the `= true` default), so it is required here too. */
        val isHollow: Boolean,
    )

    public data class Offset(val x: Double, val y: Double)
}

internal fun decodeShadow(c: Cursor): Shadow {
    c.obj()
    return Shadow(c.safeList("layers", "Shadow.Layer", ::decodeShadowLayer))
}

internal fun decodeShadowLayer(c: Cursor): Shadow.Layer {
    c.obj()
    return Shadow.Layer(
        color = c.req("color", ::decodeThemedColor),
        radius = c.reqDouble("radius"),
        offset = c.req("offset") { o -> o.obj(); Shadow.Offset(o.reqDouble("x"), o.reqDouble("y")) },
        opacity = c.reqDouble("opacity"),
        isHollow = c.req("isHollow") { it.bool() },
    )
}

/** Stroke drawn inside, outside or centered on the bounds. */
public data class Outline(val lineWidth: Float, val position: Position, val fill: Fill) {
    @Suppress("EnumEntryName")
    public enum class Position { inside, outside, center }

    public companion object {
        public val NONE: Outline = Outline(0f, Position.inside, Fill.NONE)
    }
}

internal fun decodeOutline(c: Cursor): Outline {
    c.obj()
    return Outline(
        lineWidth = c.reqFloat("lineWidth"),
        position = c.req("position") { it.strictEnum<Outline.Position>() },
        fill = c.req("fill", ::decodeFill),
    )
}

/** Background blur or (iOS 26) glass. */
public data class VisualEffect(val blur: BackgroundBlur? = null, val glass: Glass? = null, val container: Boolean? = null)

@Suppress("EnumEntryName")
public enum class BackgroundBlur {
    extraLight, light, dark, regular,
    systemUltraThinMaterial, systemThinMaterial, systemMaterial, systemThickMaterial, systemChromeMaterial,
    systemUltraThinMaterialDark, systemThinMaterialDark, systemMaterialDark, systemThickMaterialDark, systemChromeMaterialDark,
}

@Suppress("EnumEntryName")
public enum class Glass { normal, clear }

internal fun decodeVisualEffect(c: Cursor): VisualEffect {
    c.obj()
    return VisualEffect(c.optEnum<BackgroundBlur>("blur"), c.optEnum<Glass>("glass"), c.optBool("container"))
}
