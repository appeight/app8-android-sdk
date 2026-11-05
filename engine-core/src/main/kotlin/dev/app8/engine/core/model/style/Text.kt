package dev.app8.engine.core.model.style

import dev.app8.engine.core.model.Asset
import dev.app8.engine.core.model.Cursor
import dev.app8.engine.core.model.decodeAsset
import dev.app8.engine.core.model.optEnum
import dev.app8.engine.core.model.safeOptionalList
import dev.app8.engine.core.model.strictEnum

/** Text style (iOS `Style.TextModel`). `fontSize` is required. */
public data class TextModel(
    val fontSize: Double,
    val fontWeight: Font.Weight? = null,
    val alignment: Alignment? = null,
    val lineHeight: LineHeight? = null,
    val letterSpacing: LetterSpacing? = null,
    val numberOfLines: Int? = null,
    val lineBreakMode: LineBreakMode? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val adjustsFontSizeToFitWidth: Boolean? = null,
    val minimumScaleFactor: Double? = null,
    val fontFamily: String? = null,
    val color: Wrapped<ThemedColor> = Wrapped.none(),
    val font: Wrapped<Font> = Wrapped.none(),
) {
    public data class LineHeight(val type: Type, val value: Double) {
        @Suppress("EnumEntryName")
        public enum class Type { auto, multiplier, fontSizeFraction, fixed, interLineSpacing }
    }

    /** A bare number is fixed spacing in points; the object form defaults `value` to 0. */
    public data class LetterSpacing(val type: Type, val value: Double) {
        @Suppress("EnumEntryName")
        public enum class Type { auto, fixed }
    }

    /** Accepts names (`"center"`) or the raw iOS integer (`0…4`). */
    @Suppress("EnumEntryName")
    public enum class Alignment { left, center, right, justified, natural }

    @Suppress("EnumEntryName")
    public enum class LineBreakMode { wordWrap, charWrap, clip, truncateHead, truncateTail, truncateMiddle }
}

internal fun decodeTextModel(c: Cursor): TextModel {
    c.obj()
    return TextModel(
        fontSize = c.reqDouble("fontSize"),
        fontWeight = c.optEnum<Font.Weight>("fontWeight"),
        alignment = c.opt("alignment", ::decodeAlignment),
        lineHeight = c.opt("lineHeight") { l ->
            l.obj()
            TextModel.LineHeight(l.req("type") { it.strictEnum<TextModel.LineHeight.Type>() }, l.reqDouble("value"))
        },
        letterSpacing = c.opt("letterSpacing", ::decodeLetterSpacing),
        numberOfLines = c.optInt("numberOfLines"),
        lineBreakMode = c.optEnum<TextModel.LineBreakMode>("lineBreakMode"),
        underline = c.optBool("underline"),
        strikethrough = c.optBool("strikethrough"),
        adjustsFontSizeToFitWidth = c.optBool("adjustsFontSizeToFitWidth"),
        minimumScaleFactor = c.optDouble("minimumScaleFactor"),
        fontFamily = c.optString("fontFamily"),
        color = c.wrapped("color", ::decodeThemedColor),
        font = c.wrapped("font", ::decodeFont),
    )
}

private fun decodeAlignment(c: Cursor): TextModel.Alignment {
    c.stringOrNull()?.let { name ->
        return TextModel.Alignment.entries.firstOrNull { it.name == name } ?: c.fail("unknown text alignment '$name'")
    }
    val raw = c.int()
    return TextModel.Alignment.entries.getOrNull(raw) ?: c.fail("unknown text alignment $raw")
}

private fun decodeLetterSpacing(c: Cursor): TextModel.LetterSpacing {
    c.doubleOrNull()?.let { return TextModel.LetterSpacing(TextModel.LetterSpacing.Type.fixed, it) }
    c.obj()
    return TextModel.LetterSpacing(c.req("type") { it.strictEnum<TextModel.LetterSpacing.Type>() }, c.optDouble("value") ?: 0.0)
}

/** Font style: a family with faces or variable axes. */
public data class Font(val family: Family) {
    public data class Family(
        val displayName: String,
        val isSystemFont: Boolean,
        val foundry: String? = null,
        val license: String? = null,
        val variable: Boolean? = null,
        /** `@SafeOptionalArrayDecodable`. */
        val faces: List<Face>? = null,
        val axes: List<Axis>? = null,
        val asset: Asset? = null,
    )

    public data class Face(
        val postScriptName: String,
        val style: Style,
        val weight: Weight,
        val width: Int? = null,
        val asset: Asset,
    )

    @Suppress("EnumEntryName")
    public enum class Weight { ultraLight, thin, light, regular, medium, semibold, bold, heavy, black }

    @Suppress("EnumEntryName")
    public enum class Style { normal, italic, oblique }

    public data class Axis(val tag: String, val min: Double, val max: Double, val default: Double)
}

internal fun decodeFont(c: Cursor): Font {
    c.obj()
    return Font(c.req("family", ::decodeFontFamily))
}

private fun decodeFontFamily(c: Cursor): Font.Family {
    c.obj()
    return Font.Family(
        displayName = c.reqString("displayName"),
        isSystemFont = c.req("isSystemFont") { it.bool() },
        foundry = c.optString("foundry"),
        license = c.optString("license"),
        variable = c.optBool("variable"),
        faces = c.safeOptionalList("faces", "Font.Face") { f ->
            f.obj()
            Font.Face(
                postScriptName = f.reqString("postScriptName"),
                style = f.req("style") { it.strictEnum<Font.Style>() },
                weight = f.req("weight") { it.strictEnum<Font.Weight>() },
                width = f.optInt("width"),
                asset = f.req("asset", ::decodeAsset),
            )
        },
        axes = c.opt("axes") { a ->
            a.list { x ->
                x.obj()
                Font.Axis(x.reqString("tag"), x.reqDouble("min"), x.reqDouble("max"), x.reqDouble("default"))
            }
        },
        asset = c.opt("asset", ::decodeAsset),
    )
}
