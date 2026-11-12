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
)

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
 * - `lineHeight`: `fixed` = min = max line height; `multiplier` = natural × value;
 *   `interLineSpacing` / `fontSizeFraction` = natural + value (resp. size × value). UIKit puts
 *   the extra height of a taller line above the glyphs, and paragraph spacing below a line;
 *   [LineHeightStyle] reproduces both;
 * - alignment, underline / strikethrough, `numberOfLines` (0 = unlimited), `lineBreakMode`.
 *
 * [color] `null` leaves the color unspecified (the component decides).
 */
internal fun TextModel.toResolvedText(
    appearance: App8Appearance,
    fonts: App8FontProvider = App8FontProvider.System,
    sizer: TextSizer = TextSizer.FollowSystem,
): ResolvedText {
    val size = fontSize.toFloat()
    val weight = (fontWeight ?: Font.Weight.regular).toFontWeight()
    val (family, custom) = resolveFontFamily(fonts, weight)

    val (lineHeight, lineHeightStyle) = lineHeight(size, sizer)

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

/** Extra height above the glyphs, like UIKit's min/max line height and line height multiple. */
private val ABOVE = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None)

/** Spacing below each line but the last, like `NSParagraphStyle.lineSpacing`. */
private val BETWEEN = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.LastLineBottom)

private fun TextModel.lineHeight(size: Float, sizer: TextSizer): Pair<TextUnit, LineHeightStyle?> {
    val lh = lineHeight ?: return TextUnit.Unspecified to null
    val v = lh.value.toFloat()
    val natural = size * IOS_NATURAL_LINE_HEIGHT_EM
    return when (lh.type) {
        TextModel.LineHeight.Type.auto -> TextUnit.Unspecified to null
        TextModel.LineHeight.Type.fixed -> sizer.size(v) to ABOVE
        // em is relative to the font size, so it follows any scaling of the size itself.
        TextModel.LineHeight.Type.multiplier -> (IOS_NATURAL_LINE_HEIGHT_EM * v).em to ABOVE
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
