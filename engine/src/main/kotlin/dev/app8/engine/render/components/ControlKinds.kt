package dev.app8.engine.render.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.ActivityIndicatorProperties
import dev.app8.engine.core.model.PickerProperties
import dev.app8.engine.core.model.ShimmerProperties
import dev.app8.engine.core.model.SliderProperties
import dev.app8.engine.core.model.ToggleProperties
import dev.app8.engine.core.model.style.ActivityIndicatorStyle
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.PickerStyle
import dev.app8.engine.core.model.style.SliderStyle
import dev.app8.engine.core.model.style.ToggleStyle
import dev.app8.engine.icons.MaterialSymbols
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.Coerce
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.LocalInteractionEnabled
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.bool
import dev.app8.engine.render.controls.ShimmerMath
import dev.app8.engine.render.controls.SliderMath
import dev.app8.engine.render.number
import dev.app8.engine.render.resolve
import dev.app8.engine.render.string
import dev.app8.engine.style.IosSystemColors

internal fun hexColor(s: String?): Color? = s?.takeIf { it.isNotEmpty() }?.let { Color(ColorHex.parseOrFallback(it).argb) }

/** The tracked value of a `bindVariable` (null when unbound or undefined). */
internal fun RenderNode.bound(name: String?): Any? = name?.let { resolve("{{$it}}") }

/** Writes a control's value to its `bindVariable` (iOS view → variable binding). */
internal fun RenderNode.writeBound(name: String?, value: Any?) {
    name ?: return
    runCatching { scope.store.setValue(name, value) }
        .onFailure { runtime.diagnostics.warn("bindVariable", "'$name' in $key: ${it.message}") }
}

/** Lays a fixed-size system control out at its natural size, centered in the solved frame. */
private val CenterUnbounded = Modifier.wrapContentSize(Alignment.Center, unbounded = true)

/**
 * `toggle` → Material 3 [Switch]. Laid out at UISwitch's 51×31 so screens keep their iOS
 * geometry; `onTintColor` tints the checked track, `thumbTintColor` the thumb. A change
 * writes `bindVariable` and fires `tap` (as iOS `valueChanged` does).
 */
internal object ToggleKind : ComponentKind {
    override val handlesTaps: Boolean get() = true

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits =
        LayoutTraits(intrinsic = IntrinsicMeasure { Size(51.0, 31.0) }, signature = "toggle")

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val p = node.content.properties as? ToggleProperties ?: ToggleProperties()
        val style = node.content.style as? ToggleStyle
        val dispatcher = LocalDispatcher.current
        val on = Coerce.bool(node.bound(p.bindVariable)) ?: (node.ui["on"] as? Boolean) ?: node.bool(p.isOn) ?: false
        val enabled = (node.bool(p.isEnabled) ?: true) && LocalInteractionEnabled.current
        val onTint = hexColor(style?.onTintColor)
        val thumb = hexColor(style?.thumbTintColor)
        val d = SwitchDefaults.colors()
        val colors = SwitchDefaults.colors(
            checkedTrackColor = onTint ?: d.checkedTrackColor,
            checkedThumbColor = thumb ?: d.checkedThumbColor,
            uncheckedThumbColor = thumb ?: d.uncheckedThumbColor,
            checkedBorderColor = onTint ?: d.checkedBorderColor,
        )
        Box(modifier, contentAlignment = Alignment.Center) {
            Switch(
                checked = on,
                onCheckedChange = { v ->
                    node.ui["on"] = v
                    node.writeBound(p.bindVariable, v)
                    dispatcher.dispatchTrigger(node, ActionTrigger.tap)
                },
                enabled = enabled,
                colors = colors,
                modifier = CenterUnbounded,
            )
        }
    }
}

/**
 * `slider` → Material 3 [Slider] across the frame, vertically centered (UISlider). `step`
 * snaps like iOS and becomes M3 stop ticks; track and thumb colors from the DSL.
 */
internal object SliderKind : ComponentKind {
    override val handlesTaps: Boolean get() = true

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits =
        LayoutTraits(intrinsic = IntrinsicMeasure { Size(Size.NO_INTRINSIC_METRIC, 31.0) }, signature = "slider")

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val p = node.content.properties as? SliderProperties ?: SliderProperties()
        val style = node.content.style as? SliderStyle
        val min = p.minimumValue ?: 0f
        val max = (p.maximumValue ?: 1f).coerceAtLeast(min)
        val v = (Coerce.double(node.bound(p.bindVariable))?.toFloat() ?: (node.ui["value"] as? Float) ?: node.number(p.value)?.toFloat() ?: min)
            .coerceIn(min, max)
        val enabled = (node.bool(p.isEnabled) ?: true) && LocalInteractionEnabled.current
        val d = SliderDefaults.colors()
        val active = hexColor(style?.minimumTrackTintColor)
        val inactive = hexColor(style?.maximumTrackTintColor)
        val colors = SliderDefaults.colors(
            thumbColor = hexColor(style?.thumbTintColor) ?: d.thumbColor,
            activeTrackColor = active ?: d.activeTrackColor,
            inactiveTrackColor = inactive ?: d.inactiveTrackColor,
            activeTickColor = inactive ?: d.activeTickColor,
            inactiveTickColor = active ?: d.inactiveTickColor,
        )
        Box(modifier, contentAlignment = Alignment.Center) {
            Slider(
                value = v,
                onValueChange = { raw ->
                    val snapped = SliderMath.snap(raw, min, max, p.step)
                    node.ui["value"] = snapped
                    node.writeBound(p.bindVariable, snapped.toDouble())
                },
                valueRange = min..max,
                steps = SliderMath.steps(min, max, p.step),
                enabled = enabled,
                colors = colors,
                modifier = Modifier.fillMaxWidth().wrapContentHeight(unbounded = true),
            )
        }
    }
}

/**
 * `picker`. `segmented` → Material 3 [SingleChoiceSegmentedButtonRow]
 * (`selectedSegmentTintColor` fills the selected segment); `menu` → the selected label as a
 * pop-up button (DSL text style, tint color, an up/down indicator) opening an
 * [ExposedDropdownMenuBox] menu with option icons and a check on the selection.
 */
internal object PickerKind : ComponentKind {
    override val handlesTaps: Boolean get() = true

    /** Literal `options`, or an expression giving `[{value, label, icon?}]` (or plain strings). */
    private fun options(node: RenderNode, p: PickerProperties): List<PickerProperties.Option> {
        p.options?.let { return it }
        val raw = p.optionsExpression?.let { node.resolve(it) } as? List<*> ?: return emptyList()
        return raw.mapNotNull { o ->
            when (o) {
                is Map<*, *> -> {
                    val value = o["value"]?.let(Coerce::string) ?: return@mapNotNull null
                    PickerProperties.Option(value, o["label"]?.let(Coerce::string) ?: value, o["icon"] as? String)
                }
                is String -> PickerProperties.Option(o, o)
                else -> null
            }
        }
    }

    /** With `bindKey`, the bound variable is an object map holding one choice per key. */
    private fun boundSelection(node: RenderNode, p: PickerProperties): String? {
        val bound = node.bound(p.bindVariable)
        val key = p.bindKey?.let { node.string(it) } ?: return bound as? String
        return ((bound as? Map<*, *>)?.get(key))?.let(Coerce::string)
    }

    private fun selection(node: RenderNode, p: PickerProperties): String =
        boundSelection(node, p) ?: (node.ui["value"] as? String) ?: node.string(p.selectedValue) ?: ""

    private fun write(node: RenderNode, p: PickerProperties, value: String) {
        val key = p.bindKey?.let { node.string(it, node.scope.untracked) }
        val name = p.bindVariable
        if (key == null || name == null) {
            node.writeBound(name, value)
            return
        }
        val current = runCatching { node.scope.store.getValue(name) }.getOrNull() as? Map<*, *>
        val next = LinkedHashMap<String, Any?>()
        current?.forEach { (k, v) -> next[k.toString()] = v }
        next[key] = value
        node.writeBound(name, next)
    }

    private fun menuText(node: RenderNode, env: RenderEnv) =
        env.text(env.textModel((node.content.style as? PickerStyle)?.text), 15f, IosSystemColors.of("systemBlue", env.appearance), 1).copy(maxLines = 1)

    /** Pull-down look title: `style.label` (brand font), default 15pt label color. */
    private fun labelText(style: PickerStyle, env: RenderEnv) =
        env.text(env.textModel(style.label.takeIf { it.base != null || it.direct != null } ?: style.text), 15f, env.labelColor, 1)
            .copy(maxLines = 1)

    /** `style.indicator` (SF name; default `chevron.up.chevron.down`, `""` hides it). */
    private fun indicatorName(style: PickerStyle): String? =
        (style.indicator ?: "chevron.up.chevron.down").takeIf { it.isNotEmpty() }

    private fun title(node: RenderNode, p: PickerProperties): String {
        val sel = selection(node, p)
        return options(node, p).firstOrNull { it.value == sel }?.label ?: p.placeholder ?: "Select"
    }

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val p = node.content.properties as? PickerProperties ?: PickerProperties()
        if (p.displayMode == PickerProperties.DisplayMode.segmented) {
            val t = env.text(null, 13f, env.labelColor, 1)
            val labels = p.options.orEmpty().map { it.label }
            return LayoutTraits(
                intrinsic = IntrinsicMeasure {
                    // UISegmentedControl: equal segments as wide as the widest title + 2×10pt, 32pt tall.
                    val widest = labels.maxOfOrNull { env.measure(AnnotatedString(it), t, null).width } ?: 0.0
                    Size(labels.size * (widest + 20.0), 32.0)
                },
                signature = "seg:$labels",
            )
        }
        val style = node.content.style as? PickerStyle
        if (style?.isPullDown == true) {
            val text = labelText(style, env)
            val title = title(node, p)
            val fs = text.style.fontSize.value.toDouble()
            val hasIndicator = indicatorName(style) != null
            val iconSize = style.indicatorSize ?: (fs * 0.8)
            val spacing = style.indicatorSpacing ?: 6.0
            return LayoutTraits(
                intrinsic = IntrinsicMeasure {
                    // Hugs its content: title + spacing + indicator.
                    val s = env.measure(AnnotatedString(title), text, null)
                    val extra = if (hasIndicator) spacing + iconSize else 0.0
                    Size(s.width + extra, maxOf(s.height, if (hasIndicator) iconSize else 0.0))
                },
                signature = "pulldown:$title|$text|$hasIndicator|$iconSize|$spacing",
            )
        }
        val text = menuText(node, env)
        val title = title(node, p)
        return LayoutTraits(
            intrinsic = IntrinsicMeasure {
                val s = env.measure(AnnotatedString(title), text, null)
                val fs = text.style.fontSize.value.toDouble()
                Size(s.width + fs * 1.1 + 4.0, s.height + 12.0)
            },
            signature = "menu:$title|$text",
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val p = node.content.properties as? PickerProperties ?: PickerProperties()
        val style = node.content.style as? PickerStyle
        val options = options(node, p)
        val sel = selection(node, p)
        val enabled = (node.bool(p.isEnabled) ?: true) && LocalInteractionEnabled.current
        val select: (String) -> Unit = { v ->
            node.ui["value"] = v
            write(node, p, v)
        }
        if (style?.isPullDown == true) {
            PullDown(node, p, style, options, sel, enabled, select, modifier)
            return
        }
        if (p.displayMode == PickerProperties.DisplayMode.segmented) {
            val tint = hexColor(style?.selectedSegmentTintColor)
            val d = SegmentedButtonDefaults.colors()
            val colors = SegmentedButtonDefaults.colors(activeContainerColor = tint ?: d.activeContainerColor)
            Box(modifier, contentAlignment = Alignment.Center) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true)) {
                    options.forEachIndexed { i, o ->
                        SegmentedButton(
                            selected = o.value == sel,
                            onClick = { select(o.value) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            enabled = enabled,
                            colors = colors,
                            label = { Text(o.label, maxLines = 1) },
                        )
                    }
                }
            }
            return
        }
        val text = menuText(node, env)
        var expanded by remember(node) { mutableStateOf(false) }
        val context = LocalContext.current
        val fs = text.style.fontSize
        Box(modifier) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }, modifier = Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxSize()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                        .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(title(node, p), style = text.style, maxLines = 1, softWrap = false)
                    BasicText(
                        MaterialSymbols.material(context, "unfold_more")?.text.orEmpty(),
                        style = TextStyle(color = text.style.color, fontSize = fs * 1.1f, fontFamily = MaterialSymbols.family(false, 500, fs.value)),
                        maxLines = 1,
                    )
                }
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, matchAnchorWidth = false) {
                    for (o in options) {
                        val glyph = o.icon?.let { MaterialSymbols.resolve(context, it, env.runtime.diagnostics, node.id) }
                        DropdownMenuItem(
                            text = { Text(o.label) },
                            leadingIcon = glyph?.let { g -> { Text(g.text, fontFamily = MaterialSymbols.family(g.filled), fontSize = env.textSize(20f)) } },
                            trailingIcon = if (o.value == sel) ({ Text(MaterialSymbols.material(context, "check")?.text.orEmpty(), fontFamily = MaterialSymbols.family(false), fontSize = env.textSize(20f)) }) else null,
                            onClick = {
                                expanded = false
                                select(o.value)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Picker menu look (`style.label` / `style.indicator`): the selected label in the brand font and
 * a trailing Material icon, anchoring a Material 3 [DropdownMenu] with a leading check on the
 * selected item. Leading-aligned in its frame.
 */
@Composable
private fun PullDown(
    node: RenderNode,
    p: PickerProperties,
    style: PickerStyle,
    options: List<PickerProperties.Option>,
    sel: String,
    enabled: Boolean,
    select: (String) -> Unit,
    modifier: Modifier,
) {
    val env = LocalRenderEnv.current
    val context = LocalContext.current
    val text = env.text(
        env.textModel(style.label.takeIf { it.base != null || it.direct != null } ?: style.text), 15f, env.labelColor, 1,
    ).copy(maxLines = 1)
    val fs = text.style.fontSize.value
    val glyph = (style.indicator ?: "chevron.up.chevron.down").takeIf { it.isNotEmpty() }
        ?.let { MaterialSymbols.resolve(context, it, env.runtime.diagnostics, node.id) }
    val iconSize = style.indicatorSize?.toFloat() ?: (fs * 0.8f)
    val iconColor = hexColor(style.indicatorColor) ?: text.style.color
    val title = options.firstOrNull { it.value == sel }?.label ?: p.placeholder ?: "Select"
    var expanded by remember(node) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Box {
            Row(
                Modifier
                    .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                    .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(title, style = text.style, maxLines = 1, softWrap = false)
                if (glyph != null) {
                    Spacer(Modifier.width((style.indicatorSpacing ?: 6.0).dp))
                    BasicText(
                        glyph.text,
                        style = TextStyle(
                            color = iconColor,
                            fontSize = env.textSize(iconSize),
                            fontFamily = MaterialSymbols.family(glyph.filled, 400, iconSize),
                        ),
                        maxLines = 1,
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val check = MaterialSymbols.material(context, "check")?.text.orEmpty()
                for (o in options) {
                    val selected = o.value == sel
                    DropdownMenuItem(
                        text = { Text(o.label) },
                        leadingIcon = {
                            Text(
                                if (selected) check else "",
                                fontFamily = MaterialSymbols.family(false),
                                fontSize = env.textSize(20f),
                                modifier = Modifier.width(24.dp),
                            )
                        },
                        onClick = {
                            expanded = false
                            select(o.value)
                        },
                        modifier = Modifier.semantics { this.selected = selected },
                    )
                }
            }
        }
    }
}

/**
 * `activityIndicator` → Material 3 [CircularProgressIndicator], sized like
 * UIActivityIndicatorView (`medium` 20pt, `large` 37pt), gray unless `style.color`.
 * Stopped: hidden when `hidesWhenStopped` (default), else a still arc.
 */
internal object ActivityIndicatorKind : ComponentKind {
    private fun side(node: RenderNode) =
        if ((node.content.style as? ActivityIndicatorStyle)?.indicatorStyle == ActivityIndicatorStyle.IndicatorStyle.large) 37.0 else 20.0

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val s = side(node)
        return LayoutTraits(intrinsic = IntrinsicMeasure { Size(s, s) }, signature = "spinner:$s")
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val p = node.content.properties as? ActivityIndicatorProperties ?: ActivityIndicatorProperties()
        val style = node.content.style as? ActivityIndicatorStyle
        val animating = node.bool(p.isAnimating) ?: true
        val color = hexColor(style?.color) ?: IosSystemColors.of("secondaryLabel", env.appearance)
        val s = side(node)
        val stroke = (s / 9.0).dp
        Box(modifier, contentAlignment = Alignment.Center) {
            when {
                animating -> CircularProgressIndicator(Modifier.size(s.dp), color = color, strokeWidth = stroke, trackColor = Color.Transparent)
                p.hidesWhenStopped != false -> Unit
                else -> CircularProgressIndicator(progress = { 0.75f }, modifier = Modifier.size(s.dp), color = color, strokeWidth = stroke, trackColor = Color.Transparent)
            }
        }
    }
}

/**
 * `shimmer`: its children with a white sweep over them, as the iOS gradient layer: 0 → 30%
 * → 0% white, a band one frame wide, crossing linearly every `duration` seconds (1.5 by
 * default), clipped to the frame.
 */
internal object ShimmerKind : ComponentKind {
    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val p = node.content.properties as? ShimmerProperties ?: ShimmerProperties()
        val animating = node.bool(p.isAnimating) ?: true
        var m = modifier.clipToBounds()
        if (animating) {
            val ms = ((p.duration ?: 1.5) * 1000).toInt().coerceAtLeast(16)
            val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
                0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "shimmer.t",
            )
            val direction = p.direction ?: ShimmerProperties.Direction.leftToRight
            m = m.drawWithContent {
                drawContent()
                val (a, _, b) = ShimmerMath.stops(t)
                val w = size.width
                val h = size.height
                val (start, end) = when (direction) {
                    ShimmerProperties.Direction.leftToRight -> Offset(a * w, 0f) to Offset(b * w, 0f)
                    ShimmerProperties.Direction.rightToLeft -> Offset(w - a * w, 0f) to Offset(w - b * w, 0f)
                    ShimmerProperties.Direction.topToBottom -> Offset(0f, a * h) to Offset(0f, b * h)
                }
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0f)), start, end))
            }
        }
        Box(m) { ChildrenLayout(node, Modifier.fillMaxSize()) }
    }
}
