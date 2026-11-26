package dev.app8.engine.style

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.app8.engine.core.model.style.Font
import dev.app8.engine.core.model.style.TextModel
import java.util.Locale

/**
 * Fonts the host app ships. iOS looks DSL fonts up by name in the process font
 * registry (`UIFont(name:size:)`); Android has no such registry, so the host maps
 * a DSL font name to a Compose [FontFamily].
 *
 * [name] is what the DSL names: a PostScript name (`TextModel.fontFamily`, e.g.
 * `"Inter-SemiBold"`) or a family display name (`font.family.displayName`). Return
 * `null` for unknown names: the text then falls back to the system font, as on iOS.
 */
public fun interface App8FontProvider {
    public fun fontFamily(name: String, weight: FontWeight): FontFamily?

    public companion object {
        /** Only `"system"`: Roboto ([FontFamily.Default]), the Android counterpart of SF Pro. */
        public val System: App8FontProvider = App8FontProvider { name, _ -> if (name == "system") FontFamily.Default else null }
    }
}

/** The host's fonts. The engine provides it from its configuration. */
public val LocalApp8FontProvider: ProvidableCompositionLocal<App8FontProvider> =
    staticCompositionLocalOf { App8FontProvider.System }

/**
 * How DSL font sizes react to the user's font size setting.
 *
 * DSL lengths are iOS points and render as dp: layout never scales. Text sizes are
 * converted once, here, to a [TextUnit]; Compose then applies the font scale once when it
 * measures. Nothing else multiplies by `fontScale`, so text never scales twice.
 *
 * - [FollowSystem] (default): sizes are `sp`, so text follows the Android font size
 *   setting, including Android 14+ non-linear scaling. Line heights given in points are
 *   `sp` too, so they scale through the same curve and stay proportional to the text.
 *   At the default font scale this is pixel-identical to [Fixed]. Larger text may wrap
 *   or clip where iOS (which ignores Dynamic Type for DSL labels) would not.
 * - [Fixed]: sizes are `dp` converted to `sp` (`Dp.toSp()`), cancelling the font scale:
 *   text renders exactly as on iOS whatever the setting. For hosts that need identical
 *   layouts over accessibility.
 */
public enum class App8TextScaling { FollowSystem, Fixed }

public val LocalApp8TextScaling: ProvidableCompositionLocal<App8TextScaling> =
    staticCompositionLocalOf { App8TextScaling.FollowSystem }

/** Converts DSL points to a text size. One instance per (policy, density). */
internal fun interface TextSizer {
    fun size(points: Float): TextUnit

    companion object {
        val FollowSystem: TextSizer = TextSizer { it.sp }
        fun fixed(density: Density): TextSizer = TextSizer { pts -> with(density) { pts.dp.toSp() } }
        fun of(policy: App8TextScaling, density: Density): TextSizer =
            if (policy == App8TextScaling.Fixed) fixed(density) else FollowSystem
    }
}

/**
 * SF Pro's natural line height as a multiple of its point size (ascender + descender +
 * leading = 1.1934 em). `multiplier` and `interLineSpacing` line heights are relative to
 * the natural height on iOS; we use SF's so authored line heights lay out as on iOS.
 */
internal const val IOS_NATURAL_LINE_HEIGHT_EM: Float = 1.1934f

/** UILabel's default size when a label has no text style. */
internal const val DEFAULT_FONT_SIZE: Float = 17f

/** A text style resolved for Compose: the style plus the line-limit / overflow settings that live outside it. */
@Immutable
internal data class ResolvedText(
    val style: TextStyle,
    /** `numberOfLines`, with 0 (unlimited) as [Int.MAX_VALUE]. */
    val maxLines: Int,
    val overflow: TextOverflow,
    val softWrap: Boolean,
    val adjustsFontSizeToFitWidth: Boolean = false,
    val minimumScaleFactor: Float = 0.5f,
    /**
     * Pixels of letter spacing the platform puts outside the first and the last glyph of a
     * line (see [edgeLetterSpacing]), for underlined or struck-through text only: the label
     * is that much narrower on each side and the decoration is not drawn there, so the line
     * is as wide as the text (styles.md "Underline"). 0 leaves layout and drawing untouched.
     */
    val decorationEdgeTrim: Float = 0f,
)

/** Underlined or struck-through text with positive letter spacing: the only text that is trimmed. */
internal val TextStyle.hasSpacedDecoration: Boolean
    get() = textDecoration.let { it != null && it != TextDecoration.None } &&
        letterSpacing != TextUnit.Unspecified && letterSpacing.value > 0f

/**
 * The letter spacing the platform adds at each end of a line, in pixels, for a letter
 * spacing of [spacingPx]. Up to Android 14 half the spacing goes before and half after every
 * glyph, so a line carries half a spacing beyond its ink at both ends (and its underline with
 * it); Android 15 and later add spacing between glyphs only. Which one applies is measured,
 * not assumed: [spacedWidth] and [plainWidth] are the widths of the same single glyph with
 * and without the spacing (a lone glyph gains the whole spacing in the first case, nothing
 * in the second; the widths are whole pixels, so they only decide the case).
 */
internal fun edgeLetterSpacing(spacedWidth: Float, plainWidth: Float, spacingPx: Float): Float =
    if (spacingPx > 0f && spacingPx.isFinite() && spacedWidth - plainWidth > spacingPx / 2f) spacingPx / 2f else 0f

/**
 * The horizontal strips of a line (`left..right`) that carry only edge letter spacing, as
 * `start to end` pairs: where a decoration must not be drawn. Empty when there is nothing to
 * trim or the line is too short to keep any ink.
 */
internal fun edgeSpacingStrips(left: Float, right: Float, trim: Float): List<Pair<Float, Float>> =
    if (trim <= 0f || right - left <= 2f * trim) emptyList() else listOf(left to left + trim, right - trim to right)

/** iOS `Font.Weight` → CSS-style weights (`ultraLight` 100 … `black` 900), as the DSL docs list them. */
internal fun Font.Weight.toFontWeight(): FontWeight = when (this) {
    Font.Weight.ultraLight -> FontWeight.W100
    Font.Weight.thin -> FontWeight.W200
    Font.Weight.light -> FontWeight.W300
    Font.Weight.regular -> FontWeight.W400
    Font.Weight.medium -> FontWeight.W500
    Font.Weight.semibold -> FontWeight.W600
    Font.Weight.bold -> FontWeight.W700
    Font.Weight.heavy -> FontWeight.W800
    Font.Weight.black -> FontWeight.W900
}

internal fun TextModel.Alignment.toTextAlign(): TextAlign = when (this) {
    TextModel.Alignment.left -> TextAlign.Left
    TextModel.Alignment.center -> TextAlign.Center
    TextModel.Alignment.right -> TextAlign.Right
    TextModel.Alignment.justified -> TextAlign.Justify
    TextModel.Alignment.natural -> TextAlign.Start
}

/**
 * iOS `TextModel.resolveUIFont` lookup order: `fontFamily` (PostScript name), then a
 * non-system `font.family.displayName`, then the system font. Returns the family and
 * whether it is a custom (host) font.
 */
internal fun TextModel.resolveFontFamily(fonts: App8FontProvider, weight: FontWeight): Pair<FontFamily, Boolean> {
    fontFamily?.let { name -> fonts.fontFamily(name, weight)?.let { return it to (name != "system") } }
    font.value?.family?.takeIf { !it.isSystemFont }?.let { fam ->
        fonts.fontFamily(fam.displayName, weight)?.let { return it to true }
    }
    return (fonts.fontFamily("system", weight) ?: FontFamily.Default) to false
}

/**
 * DSL text style → Compose, following what `CLabelView` applies on iOS:
 *
 * - size and weight; a custom font keeps its own faces (no synthesized bold/italic),
 *   since iOS ignores `fontWeight` for a named font;
 * - `letterSpacing` fixed points (`.kern`) → em of the font size, so it scales with it;
 * - `lineHeight`: `fixed` = min = max line height; `multiplier` = natural × value, the
 *   natural height being the font's own ([naturalLineHeightEm] for a bundle font, SF Pro's
 *   for the system font);
 *   `interLineSpacing` / `fontSizeFraction` = natural + value (resp. size × value). A `fixed`
 *   line splits its leading evenly above and below the glyphs; a `multiplier` line has its
 *   extra height above them and paragraph spacing goes below a line, as in UIKit;
 *   [LineHeightStyle] reproduces all three;
 *   a `fixed` line is exactly its value, also below the font's own height ([naturalLineHeightEm]
 *   tells): the glyphs then overflow the line evenly;
 * - alignment, underline / strikethrough, `numberOfLines` (0 = unlimited), `lineBreakMode`.
 *
 * [color] `null` leaves the color unspecified (the component decides).
 */
internal fun TextModel.toResolvedText(
    appearance: App8Appearance,
    fonts: App8FontProvider = App8FontProvider.System,
    sizer: TextSizer = TextSizer.FollowSystem,
    naturalLineHeightEm: (FontFamily, FontWeight) -> Float? = { _, _ -> null },
): ResolvedText {
    val size = fontSize.toFloat()
    val weight = (fontWeight ?: Font.Weight.regular).toFontWeight()
    val (family, custom) = resolveFontFamily(fonts, weight)

    // iOS's natural line height is the font's own (UIFont.lineHeight): a bundle font's
    // measured one; the system font keeps SF Pro's, which Roboto stands in for.
    val naturalEm = if (custom && lineHeight?.type?.let { it != TextModel.LineHeight.Type.auto && it != TextModel.LineHeight.Type.fixed } == true) {
        naturalLineHeightEm(family, weight) ?: IOS_NATURAL_LINE_HEIGHT_EM
    } else {
        IOS_NATURAL_LINE_HEIGHT_EM
    }
    // A `fixed` line below the font's own height on this platform (ascent + descent, what
    // Compose centres on) needs its own style, see [TIGHT].
    val fontEm = if (lineHeight?.type == TextModel.LineHeight.Type.fixed) naturalLineHeightEm(family, weight) else null
    val (lineHeight, lineHeightStyle) = lineHeight(size, sizer, naturalEm, fontEm)

    val letterSpacingEm = letterSpacing
        ?.takeIf { it.type == TextModel.LetterSpacing.Type.fixed && size > 0f }
        ?.let { (it.value / size).toFloat().em }
        ?: TextUnit.Unspecified

    val decorations = buildList {
        if (underline == true) add(TextDecoration.Underline)
        if (strikethrough == true) add(TextDecoration.LineThrough)
    }

    val style = TextStyle(
        color = color.colorOrNull(appearance) ?: Color.Unspecified,
        fontSize = sizer.size(size),
        fontWeight = weight,
        fontFamily = family,
        fontSynthesis = if (custom) FontSynthesis.None else null,
        letterSpacing = letterSpacingEm,
        textAlign = alignment?.toTextAlign() ?: TextAlign.Unspecified,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        lineHeight = lineHeight,
        lineHeightStyle = lineHeightStyle,
        // Avoid a lone word on the last line, like iOS's .pushOut strategy.
        lineBreak = LineBreak.Paragraph,
    )

    val lines = numberOfLines ?: 0
    val (overflow, softWrap) = when (lineBreakMode) {
        null, TextModel.LineBreakMode.truncateTail -> TextOverflow.Ellipsis to true
        TextModel.LineBreakMode.truncateHead -> TextOverflow.StartEllipsis to true
        TextModel.LineBreakMode.truncateMiddle -> TextOverflow.MiddleEllipsis to true
        // Compose wraps at word boundaries and falls back to characters for long words.
        TextModel.LineBreakMode.wordWrap, TextModel.LineBreakMode.charWrap -> TextOverflow.Clip to true
        TextModel.LineBreakMode.clip -> TextOverflow.Clip to true
    }
    return ResolvedText(
        style = style,
        maxLines = if (lines <= 0) Int.MAX_VALUE else lines,
        overflow = overflow,
        softWrap = softWrap,
        adjustsFontSizeToFitWidth = adjustsFontSizeToFitWidth == true,
        minimumScaleFactor = (minimumScaleFactor ?: 0.5).toFloat().coerceIn(0.05f, 1f),
    )
}

/**
 * A `fixed` line height: the difference to the font's own height is split evenly above and
 * below the glyphs (half-leading, styles.md "LineHeight Types"). The centre is the font's
 * ascent + descent, which is also the height of a line without a line height, so a centred
 * one-line label draws its glyphs where the same label without a line height does, for the
 * system font and for a bundle font alike (no natural-height reference is involved).
 */
private val CENTERED = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * A `fixed` line height below the font's own height. [CENTERED] would keep the font's ascent
 * on the first line and its descent on the last (Compose pads them against clipping), so a
 * block of n lines would be taller than n × value. Trimming both in [LineHeightStyle.Mode.Tight]
 * makes every line the value; the glyphs overflow it by the same amount above and below.
 * Only for lines below the font's height: on a taller line this trim would remove the leading.
 */
private val TIGHT = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both, LineHeightStyle.Mode.Tight)

/** Extra height above the glyphs, like UIKit's line height multiple. */
private val ABOVE = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None)

/** Spacing below each line but the last, like `NSParagraphStyle.lineSpacing`. */
private val BETWEEN = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.LastLineBottom)

/**
 * [naturalEm]: the font's natural line height (ascender + descender + leading) as a multiple
 * of its size, which `multiplier` multiplies (styles.md, "LineHeight Types"; UIKit
 * `lineHeightMultiple`). [fontEm]: the height of one line of the resolved font on this
 * platform, as a multiple of its size (`null` = unknown, taken as not above a `fixed` value).
 */
private fun TextModel.lineHeight(
    size: Float,
    sizer: TextSizer,
    naturalEm: Float = IOS_NATURAL_LINE_HEIGHT_EM,
    fontEm: Float? = null,
): Pair<TextUnit, LineHeightStyle?> {
    val lh = lineHeight ?: return TextUnit.Unspecified to null
    val v = lh.value.toFloat()
    val natural = size * naturalEm
    return when (lh.type) {
        TextModel.LineHeight.Type.auto -> TextUnit.Unspecified to null
        TextModel.LineHeight.Type.fixed -> sizer.size(v) to if (fontEm != null && v < size * fontEm) TIGHT else CENTERED
        // em is relative to the font size, so it follows any scaling of the size itself.
        TextModel.LineHeight.Type.multiplier -> (naturalEm * v).em to ABOVE
        TextModel.LineHeight.Type.interLineSpacing -> sizer.size(natural + v) to BETWEEN
        TextModel.LineHeight.Type.fontSizeFraction -> sizer.size(natural + size * v) to BETWEEN
    }
}

/**
 * Case transforms for text. The DSL has no text-transform field yet (iOS uppercases
 * through expressions, `upper(...)`); this is the one place the renderer will apply
 * one, with the content's locale, as `String.uppercased(Locale)` does on iOS.
 */
internal enum class TextTransform {
    None, Uppercase, Lowercase, Capitalize;

    fun apply(text: String, locale: Locale = Locale.getDefault()): String = when (this) {
        None -> text
        Uppercase -> text.uppercase(locale)
        Lowercase -> text.lowercase(locale)
        Capitalize -> text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() } }
    }
}
