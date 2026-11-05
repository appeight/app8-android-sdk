package dev.app8.engine.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app8.engine.core.model.DslDecoder
import dev.app8.engine.core.model.style.Corner
import dev.app8.engine.core.model.style.Material
import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.TextModel
import dev.app8.engine.style.App8Appearance
import dev.app8.engine.style.App8BackdropSource
import dev.app8.engine.style.ContinuousRoundedShape
import dev.app8.engine.style.DslPaint
import dev.app8.engine.style.GradientSpec
import dev.app8.engine.style.GradientStop
import dev.app8.engine.style.IosSystemColors
import dev.app8.engine.style.LocalApp8Appearance
import dev.app8.engine.style.LocalApp8Backdrop
import dev.app8.engine.style.LocalApp8FontProvider
import dev.app8.engine.style.LocalApp8TextScaling
import dev.app8.engine.style.MaterialLayer
import dev.app8.engine.style.MaterialPlan
import dev.app8.engine.style.ShadowSpec
import dev.app8.engine.style.TextSizer
import dev.app8.engine.style.TextTransform
import dev.app8.engine.style.dslMaterial
import dev.app8.engine.style.materialPlan
import dev.app8.engine.style.rememberApp8Backdrop
import dev.app8.engine.style.toResolvedText

/**
 * Debug screen: every style primitive the renderer draws (corners, fills, gradients,
 * outlines, shadows, blur / glass, text, iOS system colors), written as DSL JSON where
 * the DSL can express it. Compare with the iOS debug styles screen.
 */
@Composable
public fun App8StyleGallery(modifier: Modifier = Modifier) {
    val bg = IosSystemColors.of("systemGroupedBackground", App8Appearance.Light)
    Column(
        modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Caption("Corners: circular · continuous · capsule · per-corner", bold = true)
        GalleryRow {
            Cell("circular 22", mat(fill("#0A84FF"), corner("22")))
            Cell("continuous 22", mat(fill("#0A84FF"), corner("22", continuous = true)))
            Cell("capsule", mat(fill("#30D158"), corner("\"capsule\"", continuous = true)), height = 40.dp)
            CellBox("0/24/8/32") {
                Box(Modifier.fillMaxSize().clip(ContinuousRoundedShape(0.dp, 24.dp, 8.dp, 32.dp)).background(Color(0xFFFF9F0A)))
            }
        }
        Caption("Fills: themed solid · linear (unit space) · radial · sweep", bold = true)
        GalleryRow {
            Cell("{light,dark}", mat("""{ "id": "f", "type": "fill", "content": { "solid": { "light": "#5856D6", "dark": "#5E5CE6" } } }""", corner("16", true)))
            Cell("linear + mid", mat(LINEAR, corner("16", true)))
            CellBox("radial") { Box(Modifier.fillMaxSize().materialPlan(RADIAL)) }
            CellBox("sweep") { Box(Modifier.fillMaxSize().materialPlan(SWEEP)) }
        }
        Caption("Outlines: inside · center · outside · gradient", bold = true)
        GalleryRow {
            Cell("inside 4", mat(fill("#FFFFFF"), outline(4, "inside", "\"#FF375F\""), corner("18", true)))
            Cell("center 4", mat(fill("#FFFFFF"), outline(4, "center", "\"#FF375F\""), corner("18", true)))
            Cell("outside 4", mat(fill("#FFFFFF"), outline(4, "outside", "\"#FF375F\""), corner("18", true)))
            Cell("gradient 3", mat(fill("#FFFFFF"), outline(3, "inside", GRADIENT_FILL), corner("\"capsule\"", true)), height = 44.dp)
        }
        Caption("Shadows: soft · two layers · non-hollow · spread", bold = true)
        GalleryRow {
            Cell("r8 y4 .25", mat(shadow(SOFT), fill("#FFFFFF"), corner("16", true)))
            Cell("2 layers", mat(shadow(TWO_LAYERS), fill("#FFFFFF"), corner("16", true)))
            Cell("non-hollow", mat(shadow(NON_HOLLOW), corner("16", true)))
            CellBox("spread 6") { Box(Modifier.fillMaxSize().materialPlan(SPREAD)) }
        }
        Caption("Background blur & glass over a backdrop", bold = true)
        EffectsBand()
        Caption("Text", bold = true)
        TextSamples()
        Caption("Dark appearance (engine-controlled) & iOS system colors", bold = true)
        CompositionLocalProvider(LocalApp8Appearance provides App8Appearance.Dark) {
            Column(
                Modifier.fillMaxWidth().clip(ContinuousRoundedShape(20.dp)).background(Color.Black).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GalleryRow {
                    Cell("{light,dark}", mat("""{ "id": "f", "type": "fill", "content": { "solid": { "light": "#5856D6", "dark": "#5E5CE6" } } }""", corner("16", true)), height = 44.dp, dark = true)
                    Cell("card", mat(fill("{ \"light\": \"#FFFFFF\", \"dark\": \"#2C2C2E\" }", raw = true), outline(1, "inside", "{ \"light\": \"#00000014\", \"dark\": \"#FFFFFF26\" }"), corner("16", true)), height = 44.dp, dark = true)
                    Cell("gradient", mat(LINEAR, corner("16", true)), height = 44.dp, dark = true)
                    Cell("thick dark", mat(fill("#0A84FF"), """{ "id": "m", "type": "material", "content": [ { "id": "c", "type": "corner", "content": { "radius": 16, "curve": "continuous" } }, { "id": "v", "type": "visualEffect", "content": { "blur": "systemThickMaterial" } } ] }""", corner("16", true)), height = 44.dp, dark = true)
                }
                SystemColorSwatches()
            }
        }
    }
}

// MARK: - Effects

@Composable
private fun EffectsBand() {
    val backdrop = rememberApp8Backdrop()
    Box(Modifier.fillMaxWidth().height(176.dp).clip(ContinuousRoundedShape(20.dp))) {
        App8BackdropSource(backdrop, Modifier.fillMaxSize()) { sourceModifier ->
            Box(sourceModifier.background(Brush.linearGradient(listOf(Color(0xFFFF375F), Color(0xFFFF9F0A), Color(0xFF30D158), Color(0xFF0A84FF), Color(0xFFBF5AF2)), start = Offset.Zero, end = Offset(1200f, 300f)))) {
                // Sharp detail so the blur is visible.
                Column(Modifier.padding(top = 6.dp)) {
                    repeat(6) { i ->
                        BasicText(
                            "APP8 ▲ ● ■ BLUR APP8 ▲ ● ■ GLASS",
                            style = TextStyle(color = if (i % 2 == 0) Color.White else Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Black),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        CompositionLocalProvider(LocalApp8Backdrop provides backdrop) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryRow {
                    EffectCell("ultraThin", effect("\"blur\": \"systemUltraThinMaterial\""))
                    EffectCell("material", effect("\"blur\": \"systemMaterial\""))
                    EffectCell("chromeDark", effect("\"blur\": \"systemChromeMaterialDark\""))
                    EffectCell("glass", effect("\"glass\": \"normal\""), capsule = true)
                }
                GalleryRow {
                    EffectCell("glass clear", effect("\"glass\": \"clear\""), capsule = true)
                    EffectCell("light", effect("\"blur\": \"light\""))
                    EffectCell("thinDark", effect("\"blur\": \"systemThinMaterialDark\""))
                    CompositionLocalProvider(LocalApp8Backdrop provides null) {
                        EffectCell("no backdrop", effect("\"blur\": \"systemMaterial\""))
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.EffectCell(label: String, material: Material, capsule: Boolean = false) {
    Box(Modifier.weight(1f).height(70.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(if (capsule) 44.dp else 70.dp).dslMaterial(material), contentAlignment = Alignment.Center) {
            BasicText(label, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (label.contains("Dark")) Color.White else Color.Black))
        }
    }
}

// MARK: - Text

private val TEXTS = listOf(
    """{ "fontSize": 28, "fontWeight": "bold", "letterSpacing": -0.6, "color": "#000000" }""" to "Large Title 28 bold",
    """{ "fontSize": 17, "fontWeight": "semibold", "color": "#0A84FF", "underline": true }""" to "Headline 17 semibold underline",
    """{ "fontSize": 15, "fontWeight": "light", "lineHeight": { "type": "fixed", "value": 24 }, "color": { "light": "#3C3C43", "dark": "#EBEBF5" }, "numberOfLines": 2 }""" to
        "Body 15 light with a fixed 24pt line height that wraps onto a second line and then truncates at the end of it because numberOfLines is two",
    """{ "fontSize": 13, "fontWeight": "medium", "strikethrough": true, "alignment": "center", "color": "#FF375F" }""" to "Centered 13 medium strikethrough",
    """{ "fontSize": 12, "fontWeight": "heavy", "letterSpacing": 2, "color": "#8E8E93" }""" to "caption 12 heavy tracking +2",
    """{ "fontSize": 13, "lineBreakMode": "truncateMiddle", "numberOfLines": 1, "color": "#000000" }""" to "truncateMiddle: a very long file name that will not fit on one line.pdf",
)

@Composable
private fun TextSamples() {
    val appearance = LocalApp8Appearance.current
    val fonts = LocalApp8FontProvider.current
    val density = LocalDensity.current
    val sizer = TextSizer.of(LocalApp8TextScaling.current, density)
    Column(
        Modifier.fillMaxWidth().clip(ContinuousRoundedShape(16.dp)).background(Color.White).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TEXTS.forEachIndexed { i, (json, text) ->
            val resolved = remember(json, appearance, fonts, sizer) { text(json).toResolvedText(appearance, fonts, sizer) }
            BasicText(
                if (i == 4) TextTransform.Uppercase.apply(text) else text,
                Modifier.fillMaxWidth(),
                style = resolved.style,
                maxLines = resolved.maxLines,
                overflow = resolved.overflow,
                softWrap = resolved.softWrap,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("ultraLight", "light", "regular", "medium", "semibold", "bold", "heavy", "black").forEach { w ->
                val r = remember(w, sizer) { text("""{ "fontSize": 15, "fontWeight": "$w" }""").toResolvedText(appearance, fonts, sizer) }
                BasicText("Aa", style = r.style)
            }
        }
    }
}

@Composable
private fun SystemColorSwatches() {
    val names = listOf("systemRed", "systemOrange", "systemYellow", "systemGreen", "systemMint", "systemTeal", "systemCyan", "systemBlue", "systemIndigo", "systemPurple", "systemPink", "systemBrown")
    for (appearance in App8Appearance.entries) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(appearance.name, Modifier.size(width = 32.dp, height = 14.dp), style = TextStyle(fontSize = 9.sp, color = Color(0xFF8E8E93)))
            names.forEach { n ->
                Box(Modifier.weight(1f).height(18.dp).clip(ContinuousRoundedShape(5.dp)).background(IosSystemColors.of(n, appearance)))
            }
        }
    }
}

// MARK: - Cells

@Composable
private fun GalleryRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom, content = content)
}

@Composable
private fun RowScope.Cell(label: String, material: Material, height: Dp = 64.dp, dark: Boolean = false) {
    CellBox(label, height, dark) { Box(Modifier.fillMaxSize().dslMaterial(material)) }
}

@Composable
private fun RowScope.CellBox(label: String, height: Dp = 64.dp, dark: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(64.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(height)) { content() }
        }
        Caption(label, dark = dark)
    }
}

@Composable
private fun Caption(text: String, bold: Boolean = false, dark: Boolean = false) {
    BasicText(
        text,
        Modifier.padding(top = if (bold) 6.dp else 3.dp),
        style = TextStyle(
            fontSize = if (bold) 12.sp else 10.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                dark -> Color(0xFF98989F)
                bold -> Color(0xFF3C3C43)
                else -> Color(0xFF8E8E93)
            },
        ),
        maxLines = 1,
    )
}

// MARK: - DSL fixtures

private val decoder = DslDecoder()

private fun mat(vararg layers: String): Material =
    (decoder.decodeStyles("""[{"id":"m","type":"material","content":[${layers.joinToString(",")}]}]""").single() as StyleEntity<*>).content as Material

private fun text(json: String): TextModel =
    (decoder.decodeStyles("""[{"id":"t","type":"text","content":$json}]""").single() as StyleEntity<*>).content as TextModel

private fun fill(color: String, raw: Boolean = false) =
    """{ "id": "f", "type": "fill", "content": { "solid": ${if (raw) color else "\"$color\""} } }"""

private fun corner(radius: String, continuous: Boolean = false) =
    """{ "id": "c", "type": "corner", "content": { "radius": $radius, "curve": "${if (continuous) "continuous" else "circular"}" } }"""

private fun outline(width: Int, position: String, solidOrFill: String) =
    if (solidOrFill.trimStart().startsWith("{ \"gradient\"")) {
        """{ "id": "o", "type": "outline", "content": { "lineWidth": $width, "position": "$position", "fill": $solidOrFill } }"""
    } else {
        """{ "id": "o", "type": "outline", "content": { "lineWidth": $width, "position": "$position", "fill": { "solid": $solidOrFill } } }"""
    }

private fun shadow(layers: String) = """{ "id": "s", "type": "shadow", "content": { "layers": $layers } }"""

private fun effect(body: String): Material = mat(
    """{ "id": "v", "type": "visualEffect", "content": { $body } }""",
    corner(if (body.contains("glass")) "\"capsule\"" else "18", continuous = true),
)

private const val LINEAR = """{ "id": "g", "type": "fill", "content": { "gradient": {
    "start": { "x": 0, "y": 0, "color": "#FF375F" }, "end": { "x": 1, "y": 1, "color": "#0A84FF" },
    "middlePoints": [ { "position": 0.5, "color": "#FFD60A" } ] } } }"""

private const val GRADIENT_FILL = """{ "gradient": { "start": { "x": 0, "y": 0.5, "color": "#BF5AF2" }, "end": { "x": 1, "y": 0.5, "color": "#0A84FF" } } }"""

private const val SOFT = """[ { "color": "#000000", "radius": 8, "offset": { "x": 0, "y": 4 }, "opacity": 0.25, "isHollow": true } ]"""

private const val TWO_LAYERS = """[
    { "color": "#000000", "radius": 1, "offset": { "x": 0, "y": 1 }, "opacity": 0.3, "isHollow": true },
    { "color": "#0A84FF", "radius": 12, "offset": { "x": 0, "y": 8 }, "opacity": 0.45, "isHollow": true } ]"""

private const val NON_HOLLOW = """[ { "color": "#FF9F0A", "radius": 6, "offset": { "x": 3, "y": 3 }, "opacity": 0.8, "isHollow": false } ]"""

private val CORNER_16 = Corner(Corner.Radius.Fixed(16.0), Corner.Curve.continuous)

private val RADIAL = MaterialPlan(
    CORNER_16,
    listOf(
        MaterialLayer.FillLayer(
            DslPaint.Gradient(GradientSpec.Radial(Offset(0.5f, 0.5f), Offset(1f, 1f), listOf(GradientStop(0f, Color(0xFFFFD60A)), GradientStop(1f, Color(0xFFFF375F))))),
        ),
    ),
)

private val SWEEP = MaterialPlan(
    CORNER_16,
    listOf(
        MaterialLayer.FillLayer(
            DslPaint.Gradient(
                GradientSpec.Sweep(
                    Offset(0.5f, 0.5f), Offset(0.5f, 0f),
                    listOf(0xFFFF375F, 0xFFFFD60A, 0xFF30D158, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF375F).mapIndexed { i, c -> GradientStop(i / 5f, Color(c)) },
                ),
            ),
        ),
    ),
)

private val SPREAD = MaterialPlan(
    CORNER_16,
    listOf(
        MaterialLayer.ShadowLayer(listOf(ShadowSpec(Color(0xFF5E5CE6), radius = 3f, offsetX = 0f, offsetY = 0f, opacity = 0.6f, spread = 6f))),
        MaterialLayer.FillLayer(DslPaint.Solid(Color.White)),
    ),
)
