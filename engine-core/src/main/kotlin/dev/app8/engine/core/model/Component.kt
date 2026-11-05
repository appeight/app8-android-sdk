package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.ActivityIndicatorStyle
import dev.app8.engine.core.model.style.ButtonStyle
import dev.app8.engine.core.model.style.DatePickerStyle
import dev.app8.engine.core.model.style.IconStyle
import dev.app8.engine.core.model.style.ImageStyle
import dev.app8.engine.core.model.style.LabelStyle
import dev.app8.engine.core.model.style.PageControlStyle
import dev.app8.engine.core.model.style.PickerStyle
import dev.app8.engine.core.model.style.ScrollViewStyle
import dev.app8.engine.core.model.style.SliderStyle
import dev.app8.engine.core.model.style.TextInputStyle
import dev.app8.engine.core.model.style.ToggleStyle
import dev.app8.engine.core.model.style.VideoStyle
import dev.app8.engine.core.model.style.ViewStyle
import dev.app8.engine.core.model.style.decodeActivityIndicatorStyle
import dev.app8.engine.core.model.style.decodeButtonStyle
import dev.app8.engine.core.model.style.decodeDatePickerStyle
import dev.app8.engine.core.model.style.decodeIconStyle
import dev.app8.engine.core.model.style.decodeImageStyle
import dev.app8.engine.core.model.style.decodeLabelStyle
import dev.app8.engine.core.model.style.decodePageControlStyle
import dev.app8.engine.core.model.style.decodePickerStyle
import dev.app8.engine.core.model.style.decodeScrollViewStyle
import dev.app8.engine.core.model.style.decodeSliderStyle
import dev.app8.engine.core.model.style.decodeTextInputStyle
import dev.app8.engine.core.model.style.decodeToggleStyle
import dev.app8.engine.core.model.style.decodeVideoStyle
import dev.app8.engine.core.model.style.decodeViewStyle
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Component discriminator (iOS `Component.CType`): a known key or a custom (template) name. */
public sealed interface ComponentType {
    public data class Known(val key: Key) : ComponentType
    public data class Custom(val name: String) : ComponentType

    public val raw: String
        get() = when (this) {
            is Known -> key.name
            is Custom -> name
        }

    public fun isOneOf(vararg keys: Key): Boolean = this is Known && key in keys

    @Suppress("EnumEntryName")
    public enum class Key {
        screen, view, button, image, icon, striplet, label, textField, textView, list, scrollView, tabBarScreen,
        collection, map, stackView, shape, tableView, activityIndicator, toggle, slider, pageControl, picker,
        shimmer, datePicker, video,
    }

    public companion object {
        public fun of(raw: String): ComponentType = Key.entries.firstOrNull { it.name == raw }?.let { Known(it) } ?: Custom(raw)
    }
}

/**
 * A decoded component node (iOS `Component.Any`): a [ComponentPointer] (no `content`; resolved
 * through templates) or a [ComponentEntity] with typed content.
 */
public sealed interface Component {
    public val id: String
    public val templateId: String?
    public val type: ComponentType
}

public data class ComponentPointer(
    override val id: String,
    override val templateId: String?,
    override val type: ComponentType,
) : Component

public data class ComponentEntity<C : EntityContent>(
    override val id: String,
    override val templateId: String?,
    override val type: ComponentType,
    val content: C,
) : Component

/** Shared surface of every component content. */
public interface EntityContent {
    public val properties: Any?
    public val style: Any?
    public val layout: Layout?
    public val actions: Map<ActionTrigger, List<Action>>?
    public val analytics: Map<ActionTrigger, AnalyticsBinding>?
    public val gestures: Gestures? get() = null
    public val interaction: Interaction? get() = null
    public val accessibility: Accessibility? get() = null
    public val children: List<Component>
}

/** Fields of contents that support `states` / `triggers` / `variables` / `onEvent`. */
public interface StatefulContent : EntityContent {
    public val defaultStateName: String?
    public val triggers: Map<Trigger, String>?

    /** TODO: `Map<String, dev.app8.engine.core.vars.VariableDefinition>` once the vars package lands. */
    public val variables: Map<String, JsonElement>?
    public val onEvent: List<EventTrigger>?
    public val navigationBar: NavigationBar?
}

/** Generic content (iOS `Component.Content<Properties, Style>`), used by most component types. */
public data class ComponentContent<P : Any, S : Any>(
    override val properties: P,
    override val style: S? = null,
    override val layout: Layout? = null,
    override val actions: Map<ActionTrigger, List<Action>>? = null,
    override val analytics: Map<ActionTrigger, AnalyticsBinding>? = null,
    override val gestures: Gestures? = null,
    override val interaction: Interaction? = null,
    override val accessibility: Accessibility? = null,
    override val defaultStateName: String? = null,
    val states: Map<String, ComponentState<P, S>>? = null,
    override val triggers: Map<Trigger, String>? = null,
    override val variables: Map<String, JsonElement>? = null,
    /** Screens only. */
    override val navigationBar: NavigationBar? = null,
    /** Screen root: default screen transition. Child: shared-element participation. */
    val transition: ComponentTransition? = null,
    val hidesTabBar: Boolean? = null,
    val swipeBackEnabled: Boolean? = null,
    val dismissKeyboardOnTap: Boolean? = null,
    val streaming: Boolean? = null,
    val additionalSafeAreaInsets: EdgeInsets? = null,
    override val onEvent: List<EventTrigger>? = null,
    override val children: List<Component> = emptyList(),
) : StatefulContent {
    public val elementTransition: ScreenTransition.ElementTransition? get() = transition?.elementOrNull
}

public typealias ViewContent = ComponentContent<ViewProperties, ViewStyle>
public typealias ButtonContent = ComponentContent<ButtonProperties, ButtonStyle>
public typealias ImageContent = ComponentContent<ImageProperties, ImageStyle>
public typealias VideoContent = ComponentContent<VideoProperties, VideoStyle>
public typealias IconContent = ComponentContent<IconProperties, IconStyle>
public typealias LabelContent = ComponentContent<LabelProperties, LabelStyle>
public typealias ScrollViewContent = ComponentContent<ScrollViewProperties, ScrollViewStyle>
public typealias TextFieldContent = ComponentContent<TextFieldProperties, TextInputStyle>
public typealias TextViewContent = ComponentContent<TextViewProperties, TextInputStyle>
public typealias StackViewContent = ComponentContent<StackViewProperties, ViewStyle>
public typealias ShapeContent = ComponentContent<ShapeProperties, ViewStyle>
public typealias ActivityIndicatorContent = ComponentContent<ActivityIndicatorProperties, ActivityIndicatorStyle>
public typealias ToggleContent = ComponentContent<ToggleProperties, ToggleStyle>
public typealias SliderContent = ComponentContent<SliderProperties, SliderStyle>
public typealias PageControlContent = ComponentContent<PageControlProperties, PageControlStyle>
public typealias PickerContent = ComponentContent<PickerProperties, PickerStyle>
public typealias ShimmerContent = ComponentContent<ShimmerProperties, ViewStyle>
public typealias DatePickerContent = ComponentContent<DatePickerProperties, DatePickerStyle>

/** Typed access helpers. */
public val Component.entity: ComponentEntity<*>? get() = this as? ComponentEntity<*>
public val Component.pointer: ComponentPointer? get() = this as? ComponentPointer
public val Component.content: EntityContent? get() = (this as? ComponentEntity<*>)?.content

@Suppress("UNCHECKED_CAST")
public inline fun <reified C : EntityContent> Component.contentAs(): C? = (this as? ComponentEntity<*>)?.content as? C

// MARK: - Decoding

internal class ContentSpec<P : Any, S : Any>(
    val decodeProps: (Cursor) -> P,
    /** iOS `CustomDecodable` properties: missing key → this default (a present `null` throws). Otherwise decode `{}`. */
    val defaultProps: (() -> P)? = null,
    val decodeStyle: (Cursor) -> S,
    /** iOS `CustomDecodable` style (`Style.View`): missing key → default instead of `null`. */
    val defaultStyle: (() -> S)? = null,
)

internal fun <P : Any, S : Any> decodeContent(c: Cursor, spec: ContentSpec<P, S>): ComponentContent<P, S> {
    c.obj()
    val properties: P = when {
        spec.defaultProps != null -> if (c.has("properties")) c.req("properties", spec.decodeProps) else spec.defaultProps.invoke()
        else -> c.opt("properties", spec.decodeProps) ?: spec.decodeProps(c.with(JsonObject(emptyMap()), c.childPath("properties")))
    }
    val style: S? = when {
        spec.defaultStyle != null -> if (c.has("style")) c.req("style", spec.decodeStyle) else spec.defaultStyle.invoke()
        else -> c.opt("style", spec.decodeStyle)
    }
    return ComponentContent(
        properties = properties,
        style = style,
        layout = c.opt("layout", ::decodeLayout),
        actions = c.decodeActionsField(),
        analytics = c.decodeAnalyticsField(),
        gestures = c.opt("gestures", ::decodeGestures),
        interaction = c.opt("interaction", ::decodeInteraction),
        accessibility = c.opt("accessibility", ::decodeAccessibility),
        defaultStateName = c.optString("defaultStateName"),
        states = c.opt("states") { m -> m.map { decodeState(it, spec.decodeProps, spec.decodeStyle) } },
        triggers = c.decodeTriggersField(),
        variables = c.opt("variables", ::decodeVariables),
        navigationBar = c.opt("navigationBar", ::decodeNavigationBar),
        transition = c.opt("transition", ::decodeComponentTransition),
        hidesTabBar = c.optBool("hidesTabBar"),
        swipeBackEnabled = c.optBool("swipeBackEnabled"),
        dismissKeyboardOnTap = c.optBool("dismissKeyboardOnTap"),
        streaming = c.optBool("streaming"),
        additionalSafeAreaInsets = c.opt("additionalSafeAreaInsets", ::decodeEdgeInsets),
        onEvent = c.opt("onEvent") { a -> a.list(::decodeEventTrigger) },
        children = c.decodeChildren(),
    )
}

/** `@SafeArrayDecodable var children`: bad or platform-excluded children are dropped. */
internal fun Cursor.decodeChildren(key: String = "children"): List<Component> = safeList(key, "Component.Any", ::decodeComponent)

/** `decodeIfPresent(Component.Any)`, except a platform-excluded node is `null`. */
internal fun Cursor.optComponent(key: String): Component? = try {
    opt(key, ::decodeComponent)
} catch (e: PlatformExcludedException) {
    null
}

/** `[String: Component]`, platform-excluded entries dropped. */
internal fun Cursor.componentMap(): Map<String, Component> {
    val out = LinkedHashMap<String, Component>()
    for ((k, v) in obj()) {
        try {
            out[k] = decodeComponent(with(v, childPath(k)))
        } catch (e: PlatformExcludedException) {
            // absent on this platform
        }
    }
    return out
}

private val VIEW_SPEC = ContentSpec(::decodeViewProperties, { ViewProperties() }, ::decodeViewStyle, { ViewStyle() })
private val BUTTON_SPEC = ContentSpec(::decodeButtonProperties, null, ::decodeButtonStyle)
private val IMAGE_SPEC = ContentSpec(::decodeImageProperties, null, ::decodeImageStyle)
private val VIDEO_SPEC = ContentSpec(::decodeVideoProperties, null, ::decodeVideoStyle)
private val ICON_SPEC = ContentSpec(::decodeIconProperties, null, ::decodeIconStyle)
private val LABEL_SPEC = ContentSpec(::decodeLabelProperties, null, ::decodeLabelStyle)
private val SCROLL_SPEC = ContentSpec(::decodeScrollViewProperties, null, ::decodeScrollViewStyle)
private val TEXT_FIELD_SPEC = ContentSpec(::decodeTextFieldProperties, null, ::decodeTextInputStyle)
private val TEXT_VIEW_SPEC = ContentSpec(::decodeTextViewProperties, null, ::decodeTextInputStyle)
private val STACK_SPEC = ContentSpec(::decodeStackViewProperties, { StackViewProperties() }, ::decodeViewStyle, { ViewStyle() })
private val SHAPE_SPEC = ContentSpec(::decodeShapeProperties, null, ::decodeViewStyle, { ViewStyle() })
private val ACTIVITY_SPEC = ContentSpec(::decodeActivityIndicatorProperties, null, ::decodeActivityIndicatorStyle)
private val TOGGLE_SPEC = ContentSpec(::decodeToggleProperties, null, ::decodeToggleStyle)
private val SLIDER_SPEC = ContentSpec(::decodeSliderProperties, null, ::decodeSliderStyle)
private val PAGE_SPEC = ContentSpec(::decodePageControlProperties, null, ::decodePageControlStyle)
private val PICKER_SPEC = ContentSpec(::decodePickerProperties, null, ::decodePickerStyle)
private val SHIMMER_SPEC = ContentSpec(::decodeShimmerProperties, null, ::decodeViewStyle, { ViewStyle() })
private val DATE_SPEC = ContentSpec(::decodeDatePickerProperties, null, ::decodeDatePickerStyle)

/**
 * Decodes one component node. Applies platform overrides first; throws
 * [PlatformExcludedException] when the node does not exist on the current platform.
 */
internal fun decodeComponent(input: Cursor): Component {
    if (input.componentDepth > ModelLimits.MAX_COMPONENT_DEPTH) input.fail("component tree nested too deeply")
    val node = PlatformOverrides.apply(input.obj(), input.options.platform)
        ?: throw PlatformExcludedException(input.path, input.options.platform)
    val c = input.with(node)
    val type = c.req("type") { ComponentType.of(it.string()) }
    if (!c.hasNonNull("content")) {
        return ComponentPointer(templateId = c.optString("templateId"), id = c.reqString("id"), type = type)
    }
    val templateId = c.optString("templateId")
    val id = c.reqString("id")
    val content: EntityContent = c.req("content") { raw ->
        val cc = Cursor(raw.element, raw.path, raw.options, input.componentDepth + 1)
        when ((type as? ComponentType.Known)?.key) {
            ComponentType.Key.screen, ComponentType.Key.view -> decodeContent(cc, VIEW_SPEC)
            ComponentType.Key.button -> decodeContent(cc, BUTTON_SPEC)
            ComponentType.Key.image -> decodeContent(cc, IMAGE_SPEC)
            ComponentType.Key.video -> decodeContent(cc, VIDEO_SPEC)
            ComponentType.Key.icon -> decodeContent(cc, ICON_SPEC)
            ComponentType.Key.label -> decodeContent(cc, LABEL_SPEC)
            ComponentType.Key.scrollView -> decodeContent(cc, SCROLL_SPEC)
            ComponentType.Key.tabBarScreen -> decodeTabBarScreenContent(cc)
            ComponentType.Key.collection -> decodeCollectionContent(cc)
            ComponentType.Key.map -> decodeMapContent(cc)
            ComponentType.Key.textField -> decodeContent(cc, TEXT_FIELD_SPEC)
            ComponentType.Key.textView -> decodeContent(cc, TEXT_VIEW_SPEC)
            ComponentType.Key.stackView -> decodeContent(cc, STACK_SPEC)
            ComponentType.Key.shape -> decodeContent(cc, SHAPE_SPEC)
            ComponentType.Key.tableView -> decodeTableViewContent(cc)
            ComponentType.Key.activityIndicator -> decodeContent(cc, ACTIVITY_SPEC)
            ComponentType.Key.toggle -> decodeContent(cc, TOGGLE_SPEC)
            ComponentType.Key.slider -> decodeContent(cc, SLIDER_SPEC)
            ComponentType.Key.pageControl -> decodeContent(cc, PAGE_SPEC)
            ComponentType.Key.picker -> decodeContent(cc, PICKER_SPEC)
            ComponentType.Key.shimmer -> decodeContent(cc, SHIMMER_SPEC)
            ComponentType.Key.datePicker -> decodeContent(cc, DATE_SPEC)
            ComponentType.Key.striplet, ComponentType.Key.list -> c.fail("unsupported component type: ${type.raw}")
            null -> c.fail("unsupported custom component type with content: ${type.raw}")
        }
    }
    return ComponentEntity(id = id, templateId = templateId, type = type, content = content)
}
