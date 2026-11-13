package dev.app8.engine.core.model.style

import dev.app8.engine.core.model.Cursor
import dev.app8.engine.core.model.EdgeInsets
import dev.app8.engine.core.model.decodeEdgeInsets
import dev.app8.engine.core.model.optEnum
import dev.app8.engine.core.model.optSafeEnum
import dev.app8.engine.core.model.safeEnum

/** Fields shared by every component style (iOS `BaseStyleProtocol`). */
public interface BaseStyle {
    public val contentMode: ContentMode?
    public val material: Wrapped<Material>
    public val alpha: Double?
    public val transform: Transform?
    public val blendMode: BlendMode? get() = null
}

@Suppress("EnumEntryName")
public enum class ContentMode {
    scaleToFill, scaleAspectFit, scaleAspectFill, redraw, center, top, bottom, left, right,
    topLeft, topRight, bottomLeft, bottomRight,
}

/** Static transform; `rotation` in degrees. */
public data class Transform(val translateY: Double? = null, val scale: Double? = null, val rotation: Double? = null)

@Suppress("EnumEntryName")
public enum class BlendMode {
    normal, multiply, screen, overlay, darken, lighten, colorDodge, colorBurn, softLight, hardLight,
    difference, exclusion, hue, saturation, color, luminosity,
}

private class Base(val contentMode: ContentMode?, val material: Wrapped<Material>, val alpha: Double?, val transform: Transform?)

private fun decodeBase(c: Cursor): Base {
    c.obj()
    return Base(
        contentMode = c.optEnum<ContentMode>("contentMode"),
        material = c.wrapped("material", ::decodeMaterial),
        alpha = c.optDouble("alpha"),
        transform = c.opt("transform", ::decodeTransform),
    )
}

internal fun decodeTransform(c: Cursor): Transform {
    c.obj()
    return Transform(c.optDouble("translateY"), c.optDouble("scale"), c.optDouble("rotation"))
}

/** Style of `view`, `screen`, `stackView`, `shape`, `shimmer`, `tableView`. */
public data class ViewStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    override val blendMode: BlendMode? = null,
) : BaseStyle

internal fun decodeViewStyle(c: Cursor): ViewStyle {
    val b = decodeBase(c)
    return ViewStyle(b.contentMode, b.material, b.alpha, b.transform, c.optSafeEnum("blendMode", BlendMode.normal))
}

public data class ImageStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    override val blendMode: BlendMode? = null,
    val tintColor: Wrapped<ThemedColor> = Wrapped.none(),
    val renderingMode: RenderingMode? = null,
    val corner: Corner? = null,
) : BaseStyle {
    @Suppress("EnumEntryName")
    public enum class RenderingMode { automatic, alwaysTemplate, alwaysOriginal }
}

internal fun decodeImageStyle(c: Cursor): ImageStyle {
    val b = decodeBase(c)
    return ImageStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        blendMode = c.optSafeEnum("blendMode", BlendMode.normal),
        tintColor = c.wrapped("tintColor", ::decodeThemedColor),
        renderingMode = c.optSafeEnum("renderingMode", ImageStyle.RenderingMode.automatic),
        corner = c.opt("corner", ::decodeCorner),
    )
}

public data class LabelStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val text: Wrapped<TextModel> = Wrapped.none(),
) : BaseStyle

internal fun decodeLabelStyle(c: Cursor): LabelStyle {
    val b = decodeBase(c)
    return LabelStyle(b.contentMode, b.material, b.alpha, b.transform, c.wrapped("text", ::decodeTextModel))
}

public data class ButtonStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val text: Wrapped<TextModel> = Wrapped.none(),
    val system: Wrapped<SystemButtonStyle> = Wrapped.none(),
) : BaseStyle

internal fun decodeButtonStyle(c: Cursor): ButtonStyle {
    val b = decodeBase(c)
    return ButtonStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        text = c.wrapped("text", ::decodeTextModel),
        system = c.wrapped("system", ::decodeSystemButtonStyle),
    )
}

public data class IconStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val tint: Wrapped<ThemedColor> = Wrapped.none(),
    val renderingMode: RenderingMode? = null,
    val fontId: String? = null,
    val symbolFontSize: Double? = null,
    val color: Wrapped<ThemedColor> = Wrapped.none(),
    val hierarchicalColor: Wrapped<ThemedColor> = Wrapped.none(),
    /** `style.icon`: a reference to a whole icon style in styles.json. */
    val iconPointer: StyleAny? = null,
) : BaseStyle {
    @Suppress("EnumEntryName")
    public enum class RenderingMode { original, template }
}

internal fun decodeIconStyle(c: Cursor): IconStyle {
    val b = decodeBase(c)
    return IconStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        tint = c.wrapped("tint", ::decodeThemedColor),
        renderingMode = c.optEnum<IconStyle.RenderingMode>("renderingMode"),
        fontId = c.optString("fontId"),
        symbolFontSize = c.optDouble("symbolFontSize"),
        color = c.wrapped("color", ::decodeThemedColor),
        hierarchicalColor = c.wrapped("hierarchicalColor", ::decodeThemedColor),
        iconPointer = c.opt("icon", ::decodeStyleAny),
    )
}

public data class VideoStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val corner: Corner? = null,
) : BaseStyle

internal fun decodeVideoStyle(c: Cursor): VideoStyle {
    val b = decodeBase(c)
    return VideoStyle(b.contentMode, b.material, b.alpha, b.transform, c.opt("corner", ::decodeCorner))
}

public data class ScrollViewStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
) : BaseStyle

internal fun decodeScrollViewStyle(c: Cursor): ScrollViewStyle {
    val b = decodeBase(c)
    return ScrollViewStyle(b.contentMode, b.material, b.alpha, b.transform)
}

public data class ActivityIndicatorStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val indicatorStyle: IndicatorStyle? = null,
    val color: String? = null,
) : BaseStyle {
    @Suppress("EnumEntryName")
    public enum class IndicatorStyle { medium, large }
}

internal fun decodeActivityIndicatorStyle(c: Cursor): ActivityIndicatorStyle {
    val b = decodeBase(c)
    return ActivityIndicatorStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        indicatorStyle = c.optEnum<ActivityIndicatorStyle.IndicatorStyle>("indicatorStyle"),
        color = c.optString("color"),
    )
}

public data class CollectionStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val backgroundColor: String? = null,
    val separatorColor: String? = null,
    val separatorInset: Double? = null,
) : BaseStyle

internal fun decodeCollectionStyle(c: Cursor): CollectionStyle {
    val b = decodeBase(c)
    return CollectionStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        backgroundColor = c.optString("backgroundColor"),
        separatorColor = c.optString("separatorColor"),
        separatorInset = c.optDouble("separatorInset"),
    )
}

public data class DatePickerStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val tintColor: String? = null,
) : BaseStyle

internal fun decodeDatePickerStyle(c: Cursor): DatePickerStyle {
    val b = decodeBase(c)
    return DatePickerStyle(b.contentMode, b.material, b.alpha, b.transform, c.optString("tintColor"))
}

public data class MapStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val corner: Corner? = null,
    val routeColor: String? = null,
    val routeWidth: Double? = null,
    val annotationTintColor: String? = null,
) : BaseStyle

internal fun decodeMapStyle(c: Cursor): MapStyle {
    val b = decodeBase(c)
    return MapStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        corner = c.opt("corner", ::decodeCorner),
        routeColor = c.optString("routeColor"),
        routeWidth = c.optDouble("routeWidth"),
        annotationTintColor = c.optString("annotationTintColor"),
    )
}

public data class PageControlStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val pageIndicatorTintColor: String? = null,
    val currentPageIndicatorTintColor: String? = null,
) : BaseStyle

internal fun decodePageControlStyle(c: Cursor): PageControlStyle {
    val b = decodeBase(c)
    return PageControlStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        c.optString("pageIndicatorTintColor"), c.optString("currentPageIndicatorTintColor"),
    )
}

public data class PickerStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val text: Wrapped<TextModel> = Wrapped.none(),
    val selectedSegmentTintColor: String? = null,
    /** Menu mode: pull-down button title style; opts into the plain pull-down look. */
    val label: Wrapped<TextModel> = Wrapped.none(),
    /** Menu mode: SF Symbol after the title (default `chevron.up.chevron.down`, `""` hides it). */
    val indicator: String? = null,
    val indicatorColor: String? = null,
    val indicatorSize: Double? = null,
    val indicatorSpacing: Double? = null,
) : BaseStyle {
    /** True when `label` or `indicator` opts into the plain pull-down look. */
    public val isPullDown: Boolean get() = label.base != null || label.direct != null || indicator != null
}

internal fun decodePickerStyle(c: Cursor): PickerStyle {
    val b = decodeBase(c)
    return PickerStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        c.wrapped("text", ::decodeTextModel), c.optString("selectedSegmentTintColor"),
        c.wrapped("label", ::decodeTextModel), c.optString("indicator"), c.optString("indicatorColor"),
        c.optDouble("indicatorSize"), c.optDouble("indicatorSpacing"),
    )
}

public data class SliderStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val minimumTrackTintColor: String? = null,
    val maximumTrackTintColor: String? = null,
    val thumbTintColor: String? = null,
) : BaseStyle

internal fun decodeSliderStyle(c: Cursor): SliderStyle {
    val b = decodeBase(c)
    return SliderStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        c.optString("minimumTrackTintColor"), c.optString("maximumTrackTintColor"), c.optString("thumbTintColor"),
    )
}

public data class ToggleStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val onTintColor: String? = null,
    val thumbTintColor: String? = null,
) : BaseStyle

internal fun decodeToggleStyle(c: Cursor): ToggleStyle {
    val b = decodeBase(c)
    return ToggleStyle(b.contentMode, b.material, b.alpha, b.transform, c.optString("onTintColor"), c.optString("thumbTintColor"))
}

/** Style of `textField` and `textView`. */
public data class TextInputStyle(
    override val contentMode: ContentMode? = null,
    override val material: Wrapped<Material> = Wrapped.none(),
    override val alpha: Double? = null,
    override val transform: Transform? = null,
    val text: Wrapped<TextModel> = Wrapped.none(),
    val placeholder: Wrapped<TextModel> = Wrapped.none(),
    val tintColor: Wrapped<ThemedColor> = Wrapped.none(),
    val padding: EdgeInsets? = null,
) : BaseStyle {
    /** State-style merge: fields set on this style (by reference or value) win over [base]. */
    public fun merging(base: TextInputStyle): TextInputStyle = base.copy(
        material = if (material.base != null) material else base.material,
        text = if (text.base != null) text else base.text,
        placeholder = if (placeholder.base != null) placeholder else base.placeholder,
        tintColor = if (tintColor.base != null) tintColor else base.tintColor,
    )
}

internal fun decodeTextInputStyle(c: Cursor): TextInputStyle {
    val b = decodeBase(c)
    return TextInputStyle(
        b.contentMode, b.material, b.alpha, b.transform,
        text = c.wrapped("text", ::decodeTextModel),
        placeholder = c.wrapped("placeholder", ::decodeTextModel),
        tintColor = c.wrapped("tintColor", ::decodeThemedColor),
        padding = c.opt("padding", ::decodeEdgeInsets),
    )
}

/** Native `UIButton.Configuration`-style button (`style.system` / `systemButton` styles). */
public data class SystemButtonStyle(
    val variant: Variant? = null,
    val cornerStyle: CornerStyle? = null,
    val size: Size? = null,
    val role: Role? = null,
    val tint: Wrapped<ThemedColor> = Wrapped.none(),
    val foreground: Wrapped<ThemedColor> = Wrapped.none(),
    val image: Image? = null,
    val imagePlacement: ImagePlacement? = null,
    val imagePadding: Double? = null,
    val subtitle: String? = null,
    val showsActivityIndicator: String? = null,
) {
    @Suppress("EnumEntryName")
    public enum class Variant { plain, gray, tinted, filled, bordered, borderedTinted, borderedProminent, glass, prominentGlass }

    @Suppress("EnumEntryName")
    public enum class CornerStyle { dynamic, fixed, capsule, large, medium, small }

    @Suppress("EnumEntryName")
    public enum class Size { mini, small, medium, large }

    @Suppress("EnumEntryName")
    public enum class Role { normal, primary, cancel, destructive }

    @Suppress("EnumEntryName")
    public enum class ImagePlacement { leading, trailing, top, bottom }

    public data class Image(val type: Kind, val name: String) {
        @Suppress("EnumEntryName")
        public enum class Kind { symbol, asset }
    }
}

internal fun decodeSystemButtonStyle(c: Cursor): SystemButtonStyle {
    c.obj()
    return SystemButtonStyle(
        variant = c.optSafeEnum("variant", SystemButtonStyle.Variant.filled),
        cornerStyle = c.optSafeEnum("cornerStyle", SystemButtonStyle.CornerStyle.dynamic),
        size = c.optSafeEnum("size", SystemButtonStyle.Size.medium),
        role = c.optSafeEnum("role", SystemButtonStyle.Role.normal),
        tint = c.wrapped("tint", ::decodeThemedColor),
        foreground = c.wrapped("foreground", ::decodeThemedColor),
        image = c.opt("image") { i ->
            i.obj()
            // `type` is a required SafeEnum: a missing key throws, a present-but-unknown value is `symbol`.
            val kind = i.obj()["type"]?.let { i.with(it, i.childPath("type")).safeEnum(SystemButtonStyle.Image.Kind.symbol) }
                ?: i.fail("missing key 'type'")
            SystemButtonStyle.Image(kind, i.reqString("name"))
        },
        imagePlacement = c.optSafeEnum("imagePlacement", SystemButtonStyle.ImagePlacement.leading),
        imagePadding = c.optDouble("imagePadding"),
        subtitle = c.optString("subtitle"),
        showsActivityIndicator = c.optString("showsActivityIndicator"),
    )
}
