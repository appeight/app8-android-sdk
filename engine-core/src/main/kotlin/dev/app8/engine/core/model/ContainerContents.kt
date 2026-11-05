package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.CollectionStyle
import dev.app8.engine.core.model.style.MapStyle
import dev.app8.engine.core.model.style.ViewStyle
import dev.app8.engine.core.model.style.decodeCollectionStyle
import dev.app8.engine.core.model.style.decodeMapStyle
import dev.app8.engine.core.model.style.decodeViewStyle
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// MARK: - tabBarScreen

/** `tabBarScreen` content: tabs only (no properties / style / children). */
public data class TabBarScreenContent(
    val tabs: List<Tab>,
    val initialTab: String? = null,
    val tintColor: String? = null,
    val unselectedColor: String? = null,
) : EntityContent {
    override val properties: Any? get() = null
    override val style: Any? get() = null
    override val layout: Layout? get() = null
    override val actions: Map<ActionTrigger, List<Action>>? get() = null
    override val analytics: Map<ActionTrigger, AnalyticsBinding>? get() = null
    override val children: List<Component> get() = emptyList()
}

internal fun decodeTabBarScreenContent(c: Cursor): TabBarScreenContent {
    c.obj()
    return TabBarScreenContent(
        tabs = c.req("tabs") { a -> a.list(::decodeTab) },
        initialTab = c.optString("initialTab"),
        tintColor = c.optString("tintColor"),
        unselectedColor = c.optString("unselectedColor"),
    )
}

// MARK: - collection

public data class CollectionProperties(
    val data: String? = null,
    val sectionDefinitions: List<SectionDefinition>? = null,
    val layout: CollectionLayout? = null,
    val templateName: String? = null,
    val templateKey: String? = null,
    val selection: Selection? = null,
    val groupBy: String? = null,
    val stickyHeaders: Boolean? = null,
    val clipsToBounds: Boolean? = null,
    val pullToRefresh: Boolean? = null,
    val pagination: Pagination? = null,
    val inverted: Boolean? = null,
    val defaultSectionLayout: CollectionLayout? = null,
    val sectionLayouts: Map<String, CollectionLayout>? = null,
    val scrollOffsetVariable: String? = null,
    val swipeActions: SwipeActions? = null,
) {
    public data class SectionDefinition(val key: String, val data: String, val templateName: String? = null, val templateKey: String? = null)

    public data class Selection(val mode: Mode? = null, val binding: String? = null) {
        @Suppress("EnumEntryName")
        public enum class Mode { none, single, multiple }
    }

    public data class Pagination(val enabled: Boolean? = null, val threshold: Int? = null)

    public data class SwipeActions(val leading: List<SwipeAction>? = null, val trailing: List<SwipeAction>? = null, val allowFullSwipe: Boolean? = null)

    public data class SwipeAction(
        val title: String? = null,
        val systemImage: String? = null,
        val backgroundColor: String? = null,
        val style: Style? = null,
        val action: Action? = null,
    ) {
        @Suppress("EnumEntryName")
        public enum class Style { normal, destructive }
    }
}

public data class CollectionLayout(
    val type: LayoutType? = null,
    val itemSpacing: Double? = null,
    val lineSpacing: Double? = null,
    val contentInsets: EdgeInsets? = null,
    val columns: Int? = null,
    val minItemWidth: Double? = null,
    val rows: Int? = null,
    val itemWidth: Double? = null,
    val itemHeight: Double? = null,
    val aspectRatio: Double? = null,
    val estimatedItemHeight: Double? = null,
    val estimatedItemWidth: Double? = null,
    val separatorStyle: SeparatorStyle? = null,
    val pagingEnabled: Boolean? = null,
    val snapToItem: Boolean? = null,
    val pagingStyle: PagingStyle? = null,
    val showsScrollIndicator: Boolean? = null,
    val scrollEnabled: Boolean? = null,
    val currentPageVariable: String? = null,
) {
    @Suppress("EnumEntryName")
    public enum class LayoutType { vertical, horizontal, grid }

    @Suppress("EnumEntryName")
    public enum class SeparatorStyle { none, full, inset }

    @Suppress("EnumEntryName")
    public enum class PagingStyle { continuous, paging, pagingCentered }
}

/** A collection template: a reference to a template name or an inline component. */
public sealed interface TemplateRef {
    public data class Reference(val name: String) : TemplateRef
    public data class InlineComponent(val component: Component) : TemplateRef
}

public data class CollectionContent(
    override val properties: CollectionProperties,
    override val style: CollectionStyle? = null,
    override val layout: Layout? = null,
    override val actions: Map<ActionTrigger, List<Action>>? = null,
    override val analytics: Map<ActionTrigger, AnalyticsBinding>? = null,
    override val defaultStateName: String? = null,
    val states: Map<String, ComponentState<CollectionProperties, CollectionStyle>>? = null,
    override val triggers: Map<Trigger, String>? = null,
    override val variables: Map<String, JsonElement>? = null,
    override val navigationBar: NavigationBar? = null,
    override val onEvent: List<EventTrigger>? = null,
    val template: Component? = null,
    val templates: Map<String, TemplateRef>? = null,
    val emptyState: Component? = null,
    val loadingState: Component? = null,
    val errorState: Component? = null,
    val defaultSectionHeader: Component? = null,
    val sectionHeaders: Map<String, Component>? = null,
    override val children: List<Component> = emptyList(),
) : StatefulContent

internal fun decodeCollectionProperties(c: Cursor): CollectionProperties {
    c.obj()
    return CollectionProperties(
        data = c.optString("data"),
        sectionDefinitions = c.opt("sectionDefinitions") { a ->
            a.list { s ->
                s.obj()
                CollectionProperties.SectionDefinition(s.reqString("key"), s.reqString("data"), s.optString("templateName"), s.optString("templateKey"))
            }
        },
        layout = c.opt("layout", ::decodeCollectionLayout),
        templateName = c.optString("templateName"),
        templateKey = c.optString("templateKey"),
        selection = c.opt("selection") { s ->
            s.obj()
            CollectionProperties.Selection(s.optEnum<CollectionProperties.Selection.Mode>("mode"), s.optString("binding"))
        },
        groupBy = c.optString("groupBy"),
        stickyHeaders = c.optBool("stickyHeaders"),
        clipsToBounds = c.optBool("clipsToBounds"),
        pullToRefresh = c.optBool("pullToRefresh"),
        pagination = c.opt("pagination") { p -> p.obj(); CollectionProperties.Pagination(p.optBool("enabled"), p.optInt("threshold")) },
        inverted = c.optBool("inverted"),
        defaultSectionLayout = c.opt("defaultSectionLayout", ::decodeCollectionLayout),
        sectionLayouts = c.opt("sectionLayouts") { m -> m.map(::decodeCollectionLayout) },
        scrollOffsetVariable = c.optString("scrollOffsetVariable"),
        swipeActions = c.opt("swipeActions") { s ->
            s.obj()
            CollectionProperties.SwipeActions(
                leading = s.opt("leading") { a -> a.list(::decodeSwipeAction) },
                trailing = s.opt("trailing") { a -> a.list(::decodeSwipeAction) },
                allowFullSwipe = s.optBool("allowFullSwipe"),
            )
        },
    )
}

private fun decodeSwipeAction(c: Cursor): CollectionProperties.SwipeAction {
    c.obj()
    return CollectionProperties.SwipeAction(
        title = c.optString("title"),
        systemImage = c.optString("systemImage"),
        backgroundColor = c.optString("backgroundColor"),
        style = c.optEnum<CollectionProperties.SwipeAction.Style>("style"),
        action = c.opt("action", ::decodeAction),
    )
}

internal fun decodeCollectionLayout(c: Cursor): CollectionLayout {
    c.obj()
    return CollectionLayout(
        type = c.optEnum<CollectionLayout.LayoutType>("type"),
        itemSpacing = c.optDouble("itemSpacing"),
        lineSpacing = c.optDouble("lineSpacing"),
        contentInsets = c.opt("contentInsets", ::decodeEdgeInsets),
        columns = c.optInt("columns"),
        minItemWidth = c.optDouble("minItemWidth"),
        rows = c.optInt("rows"),
        itemWidth = c.optDouble("itemWidth"),
        itemHeight = c.optDouble("itemHeight"),
        aspectRatio = c.optDouble("aspectRatio"),
        estimatedItemHeight = c.optDouble("estimatedItemHeight"),
        estimatedItemWidth = c.optDouble("estimatedItemWidth"),
        separatorStyle = c.optEnum<CollectionLayout.SeparatorStyle>("separatorStyle"),
        pagingEnabled = c.optBool("pagingEnabled"),
        snapToItem = c.optBool("snapToItem"),
        pagingStyle = c.optEnum<CollectionLayout.PagingStyle>("pagingStyle"),
        showsScrollIndicator = c.optBool("showsScrollIndicator"),
        scrollEnabled = c.optBool("scrollEnabled"),
        currentPageVariable = c.optString("currentPageVariable"),
    )
}

internal fun decodeCollectionContent(c: Cursor): CollectionContent {
    c.obj()
    var properties = c.req("properties", ::decodeCollectionProperties)
    // The collection layout (`type: vertical|horizontal|grid`, …) belongs in `properties.layout`, but is
    // accepted at `content.layout` too — a positioning layout has no `type`, so the two never clash.
    if (properties.layout == null) {
        val collectionLayout = c.attempt { it.opt("layout", ::decodeCollectionLayout) }
        if (collectionLayout?.type != null) properties = properties.copy(layout = collectionLayout)
    }
    return CollectionContent(
        properties = properties,
        style = c.opt("style", ::decodeCollectionStyle),
        layout = c.opt("layout", ::decodeLayout),
        navigationBar = c.opt("navigationBar", ::decodeNavigationBar),
        analytics = c.decodeAnalyticsField(),
        actions = c.decodeActionsField(),
        defaultStateName = c.optString("defaultStateName"),
        states = c.opt("states") { m -> m.map { decodeState(it, ::decodeCollectionProperties, ::decodeCollectionStyle) } },
        variables = c.opt("variables", ::decodeVariables),
        onEvent = c.opt("onEvent") { a -> a.list(::decodeEventTrigger) },
        triggers = c.decodeTriggersField(),
        template = decodeCollectionTemplate(c),
        templates = c.opt("templates") { m ->
            val out = LinkedHashMap<String, TemplateRef>()
            for ((k, v) in m.obj()) {
                val e = m.with(v, m.childPath(k))
                val ref = e.stringOrNull()?.let { TemplateRef.Reference(it) } ?: try {
                    TemplateRef.InlineComponent(decodeComponent(e))
                } catch (ex: PlatformExcludedException) {
                    null
                }
                if (ref != null) out[k] = ref
            }
            out
        },
        emptyState = c.optComponent("emptyState"),
        loadingState = c.optComponent("loadingState"),
        errorState = c.optComponent("errorState"),
        defaultSectionHeader = c.optComponent("defaultSectionHeader"),
        sectionHeaders = c.opt("sectionHeaders") { it.componentMap() },
        children = c.decodeChildren(),
    )
}

/** An inline template may omit `id`; it then gets `"template"`. */
private fun decodeCollectionTemplate(c: Cursor): Component? {
    val t = c.child("template") ?: return null
    if (t.isNull) return null
    val obj = t.element as? JsonObject
    val fixed = if (obj != null && !obj.containsKey("id")) {
        t.with(JsonObject(LinkedHashMap(obj).apply { put("id", JsonPrimitive("template")) }))
    } else {
        t
    }
    return try {
        decodeComponent(fixed)
    } catch (e: PlatformExcludedException) {
        null
    }
}

// MARK: - map

public data class MapProperties(
    val mapType: MapType? = null,
    val showUserLocation: Boolean? = null,
    val zoomEnabled: Boolean? = null,
    val scrollEnabled: Boolean? = null,
    val rotateEnabled: Boolean? = null,
    val pitchEnabled: Boolean? = null,
    val center: CoordinateValue? = null,
    val span: CoordinateSpan? = null,
    val annotations: List<Annotation>? = null,
    val annotationsExpression: String? = null,
    val showDirections: Boolean? = null,
    val routeFrom: CoordinateValue? = null,
    val routeTo: CoordinateValue? = null,
    val routeTransportType: TransportTypeValue? = null,
    val polyline: PolylineValue? = null,
    val polylineFollowsRoads: Boolean? = null,
    val regionBinding: String? = null,
    val selectedAnnotationBinding: String? = null,
    val userLocationBinding: String? = null,
    val routeStatusBinding: String? = null,
    val viewportInsets: EdgeInsets? = null,
) {
    @Suppress("EnumEntryName")
    public enum class MapType { standard, satellite, hybrid, satelliteFlyover, hybridFlyover, mutedStandard }

    @Suppress("EnumEntryName")
    public enum class TransportType { automobile, walking, transit }

    public data class Coordinate(val latitude: Double, val longitude: Double)

    public data class CoordinateSpan(val latitudeDelta: Double, val longitudeDelta: Double)

    public sealed interface CoordinateValue {
        public data class Static(val coordinate: Coordinate) : CoordinateValue
        public data class Expression(val expression: String) : CoordinateValue
    }

    public sealed interface PolylineValue {
        public data class Coordinates(val coordinates: List<Coordinate>) : PolylineValue
        public data class Expression(val expression: String) : PolylineValue
    }

    /** A static type; an unknown name falls back to automobile. */
    public sealed interface TransportTypeValue {
        public data class Static(val type: TransportType) : TransportTypeValue
        public data class Expression(val expression: String) : TransportTypeValue
    }

    public data class Annotation(
        val id: String,
        val coordinate: CoordinateValue? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val image: String? = null,
        val color: String? = null,
        val data: Map<String, JsonElement>? = null,
        /** The whole raw annotation object, available to `onAnnotationTap` actions. */
        val rawFields: Map<String, JsonElement> = emptyMap(),
    )
}

public data class MapContent(
    override val properties: MapProperties,
    override val style: MapStyle? = null,
    override val layout: Layout? = null,
    override val actions: Map<ActionTrigger, List<Action>>? = null,
    override val analytics: Map<ActionTrigger, AnalyticsBinding>? = null,
    override val defaultStateName: String? = null,
    val states: Map<String, ComponentState<MapProperties, MapStyle>>? = null,
    override val triggers: Map<Trigger, String>? = null,
    override val variables: Map<String, JsonElement>? = null,
    override val navigationBar: NavigationBar? = null,
    override val onEvent: List<EventTrigger>? = null,
    override val children: List<Component> = emptyList(),
) : StatefulContent

private fun decodeCoordinate(c: Cursor): MapProperties.Coordinate {
    c.obj()
    return MapProperties.Coordinate(c.reqDouble("latitude"), c.reqDouble("longitude"))
}

private fun decodeCoordinateValue(c: Cursor): MapProperties.CoordinateValue =
    c.stringOrNull()?.let { MapProperties.CoordinateValue.Expression(it) }
        ?: MapProperties.CoordinateValue.Static(decodeCoordinate(c))

internal fun decodeMapProperties(c: Cursor): MapProperties {
    c.obj()
    return MapProperties(
        mapType = c.optEnum<MapProperties.MapType>("mapType"),
        showUserLocation = c.optBool("showUserLocation"),
        zoomEnabled = c.optBool("zoomEnabled"),
        scrollEnabled = c.optBool("scrollEnabled"),
        rotateEnabled = c.optBool("rotateEnabled"),
        pitchEnabled = c.optBool("pitchEnabled"),
        center = c.opt("center", ::decodeCoordinateValue),
        span = c.opt("span") { s -> s.obj(); MapProperties.CoordinateSpan(s.reqDouble("latitudeDelta"), s.reqDouble("longitudeDelta")) },
        annotations = c.opt("annotations") { a ->
            a.list { n ->
                n.obj()
                MapProperties.Annotation(
                    id = n.reqString("id"),
                    coordinate = n.opt("coordinate", ::decodeCoordinateValue),
                    title = n.optString("title"),
                    subtitle = n.optString("subtitle"),
                    image = n.optString("image"),
                    color = n.optString("color"),
                    data = n.opt("data", ::decodeAnyMap),
                    rawFields = LinkedHashMap(n.obj()),
                )
            }
        },
        annotationsExpression = c.optString("annotationsExpression"),
        showDirections = c.optBool("showDirections"),
        routeFrom = c.opt("routeFrom", ::decodeCoordinateValue),
        routeTo = c.opt("routeTo", ::decodeCoordinateValue),
        routeTransportType = c.opt("routeTransportType") { t ->
            val s = t.string()
            when {
                s.contains("{{") -> MapProperties.TransportTypeValue.Expression(s)
                else -> MapProperties.TransportTypeValue.Static(
                    MapProperties.TransportType.entries.firstOrNull { it.name == s } ?: MapProperties.TransportType.automobile,
                )
            }
        },
        polyline = c.opt("polyline") { p ->
            p.stringOrNull()?.let { MapProperties.PolylineValue.Expression(it) }
                ?: MapProperties.PolylineValue.Coordinates(p.list(::decodeCoordinate))
        },
        polylineFollowsRoads = c.optBool("polylineFollowsRoads"),
        regionBinding = c.optString("regionBinding"),
        selectedAnnotationBinding = c.optString("selectedAnnotationBinding"),
        userLocationBinding = c.optString("userLocationBinding"),
        routeStatusBinding = c.optString("routeStatusBinding"),
        viewportInsets = c.opt("viewportInsets", ::decodeEdgeInsets),
    )
}

internal fun decodeMapContent(c: Cursor): MapContent {
    c.obj()
    return MapContent(
        properties = c.req("properties", ::decodeMapProperties),
        style = c.opt("style", ::decodeMapStyle),
        layout = c.opt("layout", ::decodeLayout),
        navigationBar = c.opt("navigationBar", ::decodeNavigationBar),
        actions = c.decodeActionsField(),
        analytics = c.decodeAnalyticsField(),
        defaultStateName = c.optString("defaultStateName"),
        states = c.opt("states") { m -> m.map { decodeState(it, ::decodeMapProperties, ::decodeMapStyle) } },
        variables = c.opt("variables", ::decodeVariables),
        onEvent = c.opt("onEvent") { a -> a.list(::decodeEventTrigger) },
        triggers = c.decodeTriggersField(),
        children = c.decodeChildren(),
    )
}

// MARK: - tableView

public data class TableViewProperties(
    val tableStyle: TableStyle? = null,
    val showsIndicator: Boolean? = null,
    val separatorInset: Double? = null,
) {
    @Suppress("EnumEntryName")
    public enum class TableStyle { plain, grouped, insetGrouped }
}

public data class TableViewSection(
    val id: String,
    val header: String? = null,
    val footer: String? = null,
    val rows: List<TableViewRow> = emptyList(),
)

public data class TableViewRow(
    val id: String,
    val height: Double? = null,
    val clearBackground: Boolean? = null,
    val actions: Map<ActionTrigger, List<Action>>? = null,
    val analytics: Map<ActionTrigger, AnalyticsBinding>? = null,
    val children: List<Component> = emptyList(),
)

public data class TableViewContent(
    override val properties: TableViewProperties,
    override val style: ViewStyle? = null,
    override val layout: Layout? = null,
    override val variables: Map<String, JsonElement>? = null,
    override val actions: Map<ActionTrigger, List<Action>>? = null,
    override val analytics: Map<ActionTrigger, AnalyticsBinding>? = null,
    override val defaultStateName: String? = null,
    val states: Map<String, ComponentState<TableViewProperties, ViewStyle>>? = null,
    override val triggers: Map<Trigger, String>? = null,
    override val onEvent: List<EventTrigger>? = null,
    override val children: List<Component> = emptyList(),
    val sections: List<TableViewSection> = emptyList(),
) : StatefulContent {
    override val navigationBar: NavigationBar? get() = null
}

private fun decodeTableViewProperties(c: Cursor): TableViewProperties {
    c.obj()
    return TableViewProperties(
        tableStyle = c.optSafeEnum("tableStyle", TableViewProperties.TableStyle.insetGrouped),
        showsIndicator = c.optBool("showsIndicator"),
        separatorInset = c.optDouble("separatorInset"),
    )
}

private fun decodeTableViewRow(c: Cursor): TableViewRow {
    c.obj()
    return TableViewRow(
        id = c.reqString("id"),
        height = c.optDouble("height"),
        clearBackground = c.optBool("clearBackground"),
        actions = c.decodeActionsField(),
        analytics = c.decodeAnalyticsField(),
        children = c.decodeChildren(),
    )
}

internal fun decodeTableViewContent(c: Cursor): TableViewContent {
    c.obj()
    return TableViewContent(
        properties = c.req("properties", ::decodeTableViewProperties),
        style = c.opt("style", ::decodeViewStyle),
        layout = c.opt("layout", ::decodeLayout),
        variables = c.opt("variables", ::decodeVariables),
        actions = c.decodeActionsField(),
        analytics = c.decodeAnalyticsField(),
        defaultStateName = c.optString("defaultStateName"),
        states = c.opt("states") { m -> m.map { decodeState(it, ::decodeTableViewProperties, ::decodeViewStyle) } },
        triggers = c.decodeTriggersField(),
        onEvent = c.opt("onEvent") { a -> a.list(::decodeEventTrigger) },
        children = c.decodeChildren(),
        sections = c.safeList("sections", "TableView.Section") { s ->
            s.obj()
            TableViewSection(
                id = s.reqString("id"),
                header = s.optString("header"),
                footer = s.optString("footer"),
                rows = s.safeList("rows", "TableView.Row", ::decodeTableViewRow),
            )
        },
    )
}
