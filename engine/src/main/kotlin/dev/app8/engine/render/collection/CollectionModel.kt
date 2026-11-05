package dev.app8.engine.render.collection

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import dev.app8.engine.core.layout.ConstraintTarget
import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.layout.LayoutAttribute
import dev.app8.engine.core.layout.LayoutBounds
import dev.app8.engine.core.layout.LayoutConstraint
import dev.app8.engine.core.layout.LayoutNode
import dev.app8.engine.core.layout.LayoutPriority
import dev.app8.engine.core.model.CollectionContent
import dev.app8.engine.core.model.CollectionProperties
import dev.app8.engine.core.model.Component
import dev.app8.engine.core.model.ComponentEntity
import dev.app8.engine.core.model.TemplateRef
import dev.app8.engine.core.vars.ScopedVariableStore
import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableType
import dev.app8.engine.render.LayoutTree
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.Scope
import dev.app8.engine.render.Solved
import dev.app8.engine.render.resolve
import dev.app8.engine.core.layout.Layout as SolverLayout
import dev.app8.engine.core.model.Layout as DslLayout
import kotlin.math.ceil

/** One data item (iOS `CCollectionViewModel.ItemWrapper`); [value] already carries `$index`. */
@Immutable
internal data class CollectionItem(
    val index: Int,
    val value: Any?,
    val templateKey: String?,
    val sectionTemplate: String?,
)

@Immutable
internal data class CollectionSection(val key: String, val items: List<CollectionItem>, val keys: List<String>)

/**
 * The live state of one `collection`: its sections (derived from the data expression with read
 * tracking, so only a change to the bound variables rebuilds them) and a cache of cell
 * instances by item key (iOS caches cell view models by index path). A cell keeps its variable
 * scope, node tree and solved layout while it is off screen, so scrolling back costs no solve.
 */
internal class CollectionModel(val node: RenderNode) {
    private val content get() = node.content as CollectionContent
    private val props: CollectionProperties get() = content.properties

    private val cells = HashMap<String, Cell>()
    private val headers = HashMap<String, Cell>()
    private val templates = HashMap<String, Component?>()

    /** The sections (iOS `sectionsSubject`), structurally compared so equal data does not recompose. */
    val sections: State<List<CollectionSection>> = derivedStateOf(structuralEqualityPolicy()) { buildSections() }

    // region data

    private fun buildSections(): List<CollectionSection> {
        val p = props
        p.sectionDefinitions?.let { defs ->
            return defs.map { def ->
                val raw = when (val r = node.resolve(def.data)) {
                    is List<*> -> r
                    is Map<*, *> -> listOf(r) // a single object renders as one cell
                    else -> emptyList<Any?>()
                }
                val items = raw.mapIndexed { i, v -> item(i, v, def.templateKey ?: p.templateKey, def.templateName) }
                section(def.key, items)
            }
        }
        val expr = p.data ?: return emptyList()
        val raw = node.resolve(expr) as? List<*> ?: return emptyList()
        val items = raw.mapIndexed { i, v -> item(i, v, p.templateKey, null) }
        val groupBy = p.groupBy ?: return listOf(section("", items))
        val groups = LinkedHashMap<String, MutableList<CollectionItem>>()
        for (it in items) groups.getOrPut(groupKey(groupBy, it)) { ArrayList() } += it
        return groups.map { (k, v) -> section(k, v) }
    }

    private fun section(key: String, items: List<CollectionItem>) =
        CollectionSection(key, items, CollectionPlan.itemKeys(key, items.map { it.value }))

    private fun item(index: Int, raw: Any?, templateKeyPath: String?, sectionTemplate: String?): CollectionItem {
        val v = Values.normalize(raw)
        @Suppress("UNCHECKED_CAST")
        val value = (v as? Map<String, Any?>)?.let { LinkedHashMap(it).apply { put("\$index", index.toLong()) } } ?: v
        return CollectionItem(index, value, templateKeyPath?.let { templateKeyOf(v, it) }, sectionTemplate)
    }

    /** `templateKey` like `item.type`: the value at that path, as a string. */
    private fun templateKeyOf(value: Any?, keyPath: String): String? {
        var cur: Any? = value
        for (k in keyPath.replace("item.", "").split('.')) cur = (cur as? Map<*, *>)?.get(k) ?: return null
        return when (cur) {
            null -> null
            is Boolean -> if (cur) "true" else "false"
            is Double -> if (cur % 1.0 == 0.0) cur.toLong().toString() else cur.toString()
            else -> cur.toString()
        }
    }

    private fun groupKey(expr: String, item: CollectionItem): String {
        val store = itemStore(item.value, null)
        return try {
            node.runtime.propertyResolver.resolveToString(expr, VariableContext(store))
        } catch (e: Exception) {
            node.runtime.diagnostics.warn("collection.groupBy", "'$expr' in ${node.key}: ${e.message}")
            "unknown"
        } finally {
            store.detach()
        }
    }

    // endregion

    // region templates (iOS resolveTemplate(for:))

    fun template(item: CollectionItem): Component? {
        val c = content
        item.sectionTemplate?.let { name ->
            c.templates?.get(name)?.let { return ref(it) }
            return named(name)
        }
        c.template?.let { return it }
        c.properties.templateName?.let { return named(it) }
        val tmpls = c.templates ?: return null
        val key = item.templateKey
        return (key?.let { tmpls[it] } ?: tmpls["default"])?.let(::ref)
    }

    private fun ref(r: TemplateRef): Component? = when (r) {
        is TemplateRef.Reference -> named(r.name)
        is TemplateRef.InlineComponent -> r.component
    }

    private fun named(name: String): Component? = templates.getOrPut(name) { node.runtime.resolveTemplate(name) }

    /** The fixed width/height of a template's own layout (mixed horizontal sections size items by it). */
    fun templateSize(item: CollectionItem?): Pair<Double?, Double?> {
        val layout = (item?.let(::template) as? ComponentEntity<*>)?.content?.layout ?: return null to null
        return (layout.width as? DslLayout.Dimension.Fixed)?.value?.toDouble() to (layout.height as? DslLayout.Dimension.Fixed)?.value?.toDouble()
    }

    fun headerTemplate(sectionKey: String): Component? = content.sectionHeaders?.get(sectionKey) ?: content.defaultSectionHeader

    // endregion

    // region cells

    /** The cached cell for [key] (created on first use), bound to [item]. */
    fun cell(key: String, item: CollectionItem, template: Component): Cell? {
        cells[key]?.let { if (it.template === template) return it else { it.dispose(); cells.remove(key) } }
        val store = itemStore(item.value, props.scrollOffsetVariable)
        return Cell.create(node, template, store, "item", item.value)?.also { cells[key] = it }
    }

    fun header(section: Int, key: String, template: Component): Cell? {
        val hk = "$section:$key"
        headers[hk]?.let { if (it.template === template) return it else { it.dispose(); headers.remove(hk) } }
        val value = linkedMapOf<String, Any?>("key" to key, "\$index" to section.toLong())
        val store = ScopedVariableStore(node.scope.store)
        store.defineVariable("section", VariableDefinition(VariableType.OBJECT, value))
        return Cell.create(node, template, store, "section", value)?.also { headers[hk] = it }
    }

    /**
     * After a data change: pushes new item values into cached cells (iOS
     * `refreshVisibleItemData`) and drops cells whose items are gone.
     */
    fun sync(sections: List<CollectionSection>) {
        val live = HashSet<String>()
        for (s in sections) for ((i, it) in s.items.withIndex()) {
            val k = s.keys[i]
            live += k
            cells[k]?.update(it.value)
        }
        val gone = cells.keys - live
        for (k in gone) cells.remove(k)?.dispose()
    }

    /** A cell store with `item` (and `$index` inside it); the scroll offset variable does not propagate in. */
    private fun itemStore(value: Any?, scrollVar: String?): ScopedVariableStore {
        val store = ScopedVariableStore(node.scope.store)
        if (scrollVar != null) store.setParentPropagationFilter { it != scrollVar }
        try {
            store.defineVariable("item", VariableDefinition(VariableType.inferType(value), value))
        } catch (e: Exception) {
            node.runtime.diagnostics.warn("collection.item", "${node.key}: ${e.message}")
        }
        return store
    }

    fun dispose() {
        cells.values.forEach { it.dispose() }
        headers.values.forEach { it.dispose() }
        cells.clear()
        headers.clear()
    }

    // endregion
}

/**
 * One cell (or section header / table row): its variable scope, the node tree of its template
 * and its solved layout. Node keys are relative to the cell (`ProductCard.title`), so cells with
 * the same layout inputs share a [dev.app8.engine.render.SolveCache] entry.
 */
internal class Cell private constructor(
    val template: Component,
    val scope: Scope,
    val root: RenderNode,
    private val scopes: List<Scope>,
    private val variable: String?,
    private var value: Any?,
    private val ownsScope: Boolean = true,
) {
    private var layoutKey: Any? = null
    private var layoutState: State<Solved>? = null

    fun update(newValue: Any?) {
        val variable = variable ?: return
        if (newValue == value) return
        value = newValue
        runCatching { scope.store.setValue(variable, newValue) }
    }

    /**
     * The cell's solved layout at [width] × [height] (null height: self-sizing, the cell is as
     * tall as its content). With [selfSizingWidth] the cell is as wide as its content. A derived
     * state: it re-solves only when a variable the layout reads changes, and the solve cache
     * makes a repeat solve free.
     */
    fun layout(env: RenderEnv, width: Double, height: Double?, selfSizingWidth: Boolean = false, edges: Boolean = true): State<Solved> {
        val key = listOf(env, width, height, selfSizingWidth, edges)
        layoutState?.let { if (key == layoutKey) return it }
        val state = derivedStateOf(structuralEqualityPolicy()) { solve(env, width, height, selfSizingWidth, edges) }
        layoutKey = key
        layoutState = state
        return state
    }

    private fun solve(env: RenderEnv, width: Double, height: Double?, selfSizingWidth: Boolean, edges: Boolean): Solved = try {
        val (tree, sig) = LayoutTree.build(root, env, isRoot = false)
        val cache = root.runtime.solveCache
        val scale = env.density.density.toDouble()
        var w = width
        if (selfSizingWidth) {
            // iOS sizes these cells with a fitting-size trailing edge and hugging content.
            val probe = cache.solve(wrap(tree, edges, weakTrailing = true), "cell~w|$sig", LayoutBounds(PROBE_WIDTH, height), EdgeInsets.ZERO, scale)
            w = ceil(probe.local[root.key]?.width ?: width)
        }
        cache.solve(wrap(tree, edges, weakTrailing = false), "cell|$edges|$sig", LayoutBounds(w, height), EdgeInsets.ZERO, scale)
    } catch (e: Exception) {
        root.runtime.diagnostics.warn("layout", "cell '${root.key}' failed to lay out: ${e.message}")
        Solved.EMPTY
    }

    fun dispose() {
        scopes.forEach { it.dispose() }
        if (!ownsScope) return
        scope.dispose()
        (scope.store as? ScopedVariableStore)?.detach()
    }

    companion object {
        const val ROOT = "#cell"
        private const val PROBE_WIDTH = 10_000.0

        fun create(parent: RenderNode, template: Component, store: ScopedVariableStore, variable: String?, value: Any?): Cell? {
            val scope = Scope(store, parent.scope)
            val scopes = ArrayList<Scope>()
            val root = RenderNode.build(parent.session, template, template.id, scope, parent.type, scopes)
            if (root == null) {
                scope.dispose()
                store.detach()
                return null
            }
            return Cell(template, scope, root, scopes, variable, value)
        }

        /** A cell rendered in [parent]'s own scope (table rows): the scope is not the cell's to dispose. */
        fun inParentScope(parent: RenderNode, template: Component): Cell? {
            val scopes = ArrayList<Scope>()
            val root = RenderNode.build(parent.session, template, template.id, parent.scope, parent.type, scopes) ?: return null
            return Cell(template, parent.scope, root, scopes, null, null, ownsScope = false)
        }

        /**
         * The cell's content view around the template root: top, leading and trailing pinned,
         * bottom at 750 (CCollectionCell.setupConstraints), added to the template's own layout.
         */
        fun wrap(tree: LayoutNode, edges: Boolean, weakTrailing: Boolean): LayoutNode {
            if (!edges) return LayoutNode(key = ROOT, children = listOf(tree))
            val base = tree.layout ?: SolverLayout()
            val extra = listOf(
                LayoutConstraint(LayoutAttribute.TOP, ConstraintTarget.Superview),
                LayoutConstraint(LayoutAttribute.LEADING, ConstraintTarget.Superview),
                LayoutConstraint(LayoutAttribute.TRAILING, ConstraintTarget.Superview, priority = if (weakTrailing) LayoutPriority.FITTING_SIZE_LEVEL else LayoutPriority.REQUIRED),
                LayoutConstraint(LayoutAttribute.BOTTOM, ConstraintTarget.Superview, priority = LayoutPriority.DEFAULT_HIGH),
            )
            val child = LayoutNode(
                key = tree.key, id = tree.id, layout = base.copy(constraints = base.constraints + extra),
                children = tree.children, stack = tree.stack, scroll = tree.scroll, hidden = tree.hidden,
                intrinsicSize = tree.intrinsicSize, hugging = tree.hugging, compressionResistance = tree.compressionResistance,
                opaque = tree.opaque,
            )
            return LayoutNode(key = ROOT, children = listOf(child))
        }
    }
}
