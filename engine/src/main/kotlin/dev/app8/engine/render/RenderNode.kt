package dev.app8.engine.render

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.app8.engine.core.model.Component
import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.core.model.ComponentEntity
import dev.app8.engine.core.model.ComponentType
import dev.app8.engine.core.model.DeferPlacement
import dev.app8.engine.core.model.EntityContent
import dev.app8.engine.core.model.NativeContent
import dev.app8.engine.core.model.StatefulContent
import dev.app8.engine.core.model.style.TextInputStyle
import dev.app8.engine.core.vars.ScopedVariableStore
import dev.app8.engine.runtime.App8Runtime

/**
 * One component of a rendered screen: the decoded node, its variable scope and its children.
 * Built once per screen presentation; everything that changes at runtime (variables, the
 * current DSL state) is snapshot state read during composition or layout, so the tree itself
 * is immutable and its identity is a stable key for Compose.
 *
 * @property key the component path (`screen.card.title`), unique in the screen; layout frames
 *   and Compose keys use it.
 */
@Stable
internal class RenderNode(
    val key: String,
    val id: String,
    val type: ComponentType,
    private val base: EntityContent,
    val scope: Scope,
    val session: ScreenSession,
    val parentType: ComponentType?,
    /** Base `properties.isHidden` of any component type (see [ComponentEntity.isHidden]). */
    private val baseHiddenBinding: String? = null,
    /** Inside a vertical `scrollView` (directly or through other components). */
    val inVerticalScroll: Boolean = false,
    /** Base `animation` of a wrapped `properties.isHidden` (see [ComponentEntity.isHiddenAnimation]). */
    private val baseHiddenAnimation: dev.app8.engine.core.model.Animation? = null,
    /** The enclosing node; null for a screen or cell root. */
    val parent: RenderNode? = null,
    /** Set on the placeholder of a deferred component (see [DeferredSlot]). */
    val deferredSlot: DeferredSlot? = null,
) {
    /**
     * Snapshot state: a deferred component replaces its placeholder here after the screen is
     * shown, and layout and composition that read the list follow.
     */
    var children: List<RenderNode> by mutableStateOf(emptyList())
        internal set

    /** Built from a deferred slot: fades in when first drawn. */
    var fadesIn: Boolean = false
        internal set

    /** True when [scope] is this node's own store: the screen root, or a component declaring variables. */
    val ownsVariableScope: Boolean
        get() = parentType == null || !(base as? StatefulContent)?.variables.isNullOrEmpty()

    /**
     * The DSL state (`states`, `setState`, `triggers`); null = base content. A `{{…}}`
     * `defaultStateName` is a state binding (iOS `setupStateBinding`): evaluated here for the
     * first frame, then kept in sync by [NodeView].
     */
    var stateName: String? by mutableStateOf(initialStateName())

    /** The `defaultStateName` expression, when it is one. */
    val stateBinding: String? get() = (base as? StatefulContent)?.defaultStateName?.takeIf { it.contains("{{") }

    private fun initialStateName(): String? {
        val raw = (base as? StatefulContent)?.defaultStateName ?: return null
        if (!raw.contains("{{")) return raw
        return runCatching { session.runtime.propertyResolver.resolveToString(raw, scope.untracked) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** The content with the current state's overrides applied (reads [stateName]). */
    val content: EntityContent get() = applyState(base, stateName)

    val baseContent: EntityContent get() = base

    /**
     * `properties.isHidden` of any component type, state-aware (reads [stateName]): a state with
     * `properties` replaces the base properties, its `isHidden` included, as [applyState] does.
     */
    val hiddenBinding: String?
        get() {
            val name = stateName ?: return baseHiddenBinding
            val states = when (base) {
                is ComponentContent<*, *> -> base.states
                is dev.app8.engine.core.model.CollectionContent -> base.states
                is dev.app8.engine.core.model.MapContent -> base.states
                is dev.app8.engine.core.model.TableViewContent -> base.states
                else -> null
            }
            val state = states?.get(name) ?: return baseHiddenBinding
            return if (state.properties != null) state.isHidden else baseHiddenBinding
        }

    /**
     * The `animation` of a wrapped `properties.isHidden`, state-aware like [hiddenBinding]: a
     * change of the hidden value then animates (see `LayoutTransition`). Null applies it at once.
     */
    val hiddenAnimation: dev.app8.engine.core.model.Animation?
        get() {
            val name = stateName ?: return baseHiddenAnimation
            val states = when (base) {
                is ComponentContent<*, *> -> base.states
                is dev.app8.engine.core.model.CollectionContent -> base.states
                is dev.app8.engine.core.model.MapContent -> base.states
                is dev.app8.engine.core.model.TableViewContent -> base.states
                else -> null
            }
            val state = states?.get(name) ?: return baseHiddenAnimation
            return if (state.properties != null) state.isHiddenAnimation else baseHiddenAnimation
        }

    /** Control state that outlives recomposition and feeds layout (a text input's text, …). */
    val ui = androidx.compose.runtime.mutableStateMapOf<String, Any?>()

    val typeKey: ComponentType.Key? get() = (type as? ComponentType.Known)?.key

    val inStack: Boolean get() = parentType == ComponentType.Known(ComponentType.Key.stackView)

    val runtime: App8Runtime get() = session.runtime

    /** Depth-first search by DSL id (for `childStates`). */
    fun find(id: String): RenderNode? {
        if (this.id == id) return this
        for (c in children) c.find(id)?.let { return it }
        return null
    }

    override fun toString(): String = "RenderNode($key)"

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun applyState(content: EntityContent, name: String?): EntityContent {
            if (name == null) return content
            val cc = content as? ComponentContent<Any, Any> ?: return content
            val state = cc.states?.get(name) ?: return content
            return cc.copy(
                properties = state.properties ?: cc.properties,
                // Text input states merge over the base style (iOS `MergeableStyle`).
                style = state.style?.let { st ->
                    val base = cc.style
                    if (st is TextInputStyle && base is TextInputStyle) st.merging(base) else st
                } ?: cc.style,
                layout = state.layout ?: cc.layout,
            )
        }

        /**
         * The initial state's `childStates` (as `setState` applies them later). With [within],
         * only components inside that subtree (a deferred component that just arrived) change.
         */
        @Suppress("UNCHECKED_CAST")
        internal fun applyChildStates(node: RenderNode, name: String, within: RenderNode? = null) {
            val state = (node.baseContent as? ComponentContent<Any, Any>)?.states?.get(name) ?: return
            state.childStates?.forEach { (childId, childState) ->
                val target = if (within != null) within.find(childId) else node.children.firstNotNullOfOrNull { it.find(childId) }
                target?.let {
                    it.stateName = childState
                    applyChildStates(it, childState)
                }
            }
        }

        /**
         * Builds the node tree under [component]. Components that declare `variables` get their
         * own [ScopedVariableStore] whose parent is the enclosing scope (iOS component scope).
         */
        fun build(
            session: ScreenSession,
            component: Component,
            key: String,
            parentScope: Scope,
            parentType: ComponentType?,
            scopes: MutableList<Scope>,
            inVerticalScroll: Boolean = false,
            parent: RenderNode? = null,
            /** In a deferred component's subtree: `defer` is ignored. */
            insideDeferred: Boolean = false,
            /** In a collection item or a per-item template: `defer` is ignored. */
            insidePerItem: Boolean = false,
        ): RenderNode? {
            val entity = component as? ComponentEntity<*> ?: run {
                session.runtime.diagnostics.warn("component.pointer", "'${component.id}' (${component.type.raw}) has no content and no template")
                return null
            }
            when (DeferPlacement.of(entity.defer, parentType, insideDeferred, insidePerItem)) {
                DeferPlacement.DEFERRED -> if (parent != null) {
                    return DeferredSlot.placeholder(session, entity, key, parentScope, parent, scopes, inVerticalScroll)
                }
                DeferPlacement.NOT_IN_STACK -> session.runtime.diagnostics.warn(
                    DeferPlacement.NOT_IN_STACK_WARNING,
                    "'${entity.id}' is not a direct stackView child; built with its screen",
                )
                DeferPlacement.NONE, DeferPlacement.IGNORED -> Unit
            }
            val content = entity.content
            // The screen's own variables live in the session scope (defined with datasources and params).
            val isScreenRoot = parentType == null
            val scope = (content as? StatefulContent)?.variables?.takeIf { it.isNotEmpty() && !isScreenRoot }?.let { raw ->
                val store = ScopedVariableStore(parentScope.store)
                store.defineVariables(App8Runtime.decodeVariableDefinitions(raw, session.runtime.diagnostics)) { name, e ->
                    session.runtime.diagnostics.warn("variable", "'$name' in '${entity.id}': ${e.message}")
                }
                Scope(store, parentScope).also { scopes += it }
            } ?: parentScope
            val node = RenderNode(key, entity.id, entity.type, content, scope, session, parentType, entity.isHidden, inVerticalScroll, entity.isHiddenAnimation, parent)
            val scrollsVertically = (entity.type as? ComponentType.Known)?.key == ComponentType.Key.scrollView &&
                (content.properties as? dev.app8.engine.core.model.ScrollViewProperties)?.direction != dev.app8.engine.core.model.ScrollViewProperties.Direction.horizontal
            val childrenInScroll = inVerticalScroll || scrollsVertically
            val seen = HashSet<String>()
            // A registered `native` module replaces its fallback, so the fallback is not laid out.
            val children = if (content is NativeContent && content.properties.module in session.runtime.nativeComponents) emptyList() else content.children
            node.children = children.mapIndexedNotNull { i, child ->
                // Keys must be unique among siblings even when ids repeat.
                val childKey = "$key.${child.id}".let { if (seen.add(it)) it else "$it#$i" }
                build(session, child, childKey, scope, entity.type, scopes, childrenInScroll, node, insideDeferred, insidePerItem)
            }
            node.stateName?.let { applyChildStates(node, it) }
            return node
        }
    }
}
