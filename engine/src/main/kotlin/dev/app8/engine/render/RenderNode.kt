package dev.app8.engine.render

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.app8.engine.core.model.Component
import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.core.model.ComponentEntity
import dev.app8.engine.core.model.ComponentType
import dev.app8.engine.core.model.EntityContent
import dev.app8.engine.core.model.StatefulContent
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
) {
    lateinit var children: List<RenderNode>
        internal set

    /** The DSL state (`states`, `setState`, `triggers`); null = base content. */
    var stateName: String? by mutableStateOf((base as? StatefulContent)?.defaultStateName)

    /** The content with the current state's overrides applied (reads [stateName]). */
    val content: EntityContent get() = applyState(base, stateName)

    val baseContent: EntityContent get() = base

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
                style = state.style ?: cc.style,
                layout = state.layout ?: cc.layout,
            )
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
        ): RenderNode? {
            val entity = component as? ComponentEntity<*> ?: run {
                session.runtime.diagnostics.warn("component.pointer", "'${component.id}' (${component.type.raw}) has no content and no template")
                return null
            }
            val content = entity.content
            val scope = (content as? StatefulContent)?.variables?.takeIf { it.isNotEmpty() }?.let { raw ->
                val store = ScopedVariableStore(parentScope.store)
                store.defineVariables(App8Runtime.decodeVariableDefinitions(raw, session.runtime.diagnostics))
                Scope(store, parentScope).also { scopes += it }
            } ?: parentScope
            val node = RenderNode(key, entity.id, entity.type, content, scope, session, parentType)
            val seen = HashSet<String>()
            node.children = content.children.mapIndexedNotNull { i, child ->
                // Keys must be unique among siblings even when ids repeat.
                val childKey = "$key.${child.id}".let { if (seen.add(it)) it else "$it#$i" }
                build(session, child, childKey, scope, entity.type, scopes)
            }
            return node
        }
    }
}
