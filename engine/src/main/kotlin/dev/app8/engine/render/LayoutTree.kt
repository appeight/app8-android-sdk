package dev.app8.engine.render

import androidx.compose.runtime.Immutable
import dev.app8.engine.core.layout.AxisValues
import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.layout.Frame
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.LayoutBounds
import dev.app8.engine.core.layout.LayoutNode
import dev.app8.engine.core.layout.ScrollSpec
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.layout.StackSpec
import dev.app8.engine.core.layout.solveLayout
import dev.app8.engine.render.components.ComponentRegistry

/** How a component takes part in layout (UIKit view traits). */
internal data class LayoutTraits(
    val intrinsic: IntrinsicMeasure? = null,
    /** Everything the intrinsic size depends on, for the solve cache (`sig` in web solve.ts). */
    val signature: String? = null,
    val hugging: AxisValues = AxisValues.DEFAULT_HUGGING,
    val compression: AxisValues = AxisValues.DEFAULT_COMPRESSION_RESISTANCE,
    val stack: StackSpec? = null,
    val scroll: ScrollSpec? = null,
    /** Children laid out by the component itself (collections). */
    val opaque: Boolean = false,
) {
    companion object {
        val NONE = LayoutTraits()
    }
}

/** A solved screen: frames relative to each node's parent (or scroll content origin), and scroll content sizes. */
@Immutable
internal data class Solved(
    val local: Map<String, Frame>,
    val absolute: Map<String, Frame>,
    val contentSizes: Map<String, Size>,
    val safeArea: EdgeInsets,
    /** The root's height, or for a content-sized root (self-sizing cells), how far its content reaches. */
    val contentHeight: Double = 0.0,
) {
    companion object {
        val EMPTY = Solved(emptyMap(), emptyMap(), emptyMap(), EdgeInsets.ZERO)
    }
}

/**
 * Builds the solver input for a screen: each node's layout (with `{{…}}` dimensions
 * evaluated), visibility and component traits. Runs inside a derived state, so every
 * variable it reads becomes a dependency of the layout and nothing else.
 */
internal object LayoutTree {
    fun build(node: RenderNode, env: RenderEnv, isRoot: Boolean = false): Pair<LayoutNode, String> {
        val sig = StringBuilder()
        val tree = build(node, env, isRoot, sig)
        return tree to sig.toString()
    }

    private fun build(node: RenderNode, env: RenderEnv, isRoot: Boolean, sig: StringBuilder): LayoutNode {
        val content = node.content
        val kind = ComponentRegistry.kind(node)
        val traits = kind.traits(node, env)
        val hidden = kind.isHidden(node)
        val layout = if (isRoot) null else LayoutMapping.map(content.layout) { expr -> node.number(expr) }
        sig.append('[').append(node.key).append('|').append(layout).append('|').append(traits.stack).append('|')
            .append(traits.scroll).append('|').append(hidden).append('|').append(traits.opaque).append('|')
            .append(traits.signature).append('|').append(traits.hugging).append(traits.compression)
        val children = if (traits.opaque) emptyList() else node.children.map { build(it, env, false, sig) }
        sig.append(']')
        return LayoutNode(
            key = node.key,
            id = node.id,
            layout = layout,
            children = children,
            stack = traits.stack,
            scroll = traits.scroll,
            hidden = hidden,
            intrinsicSize = traits.intrinsic,
            hugging = traits.hugging,
            compressionResistance = traits.compression,
            opaque = traits.opaque,
        )
    }
}

/**
 * Solver results cached by what they depend on: the tree signature, the bounds and the safe
 * area (shoppu-web `render/solve.ts`). Re-rendering a screen whose layout inputs did not
 * change, or switching back to a tab, skips the solver.
 */
internal class SolveCache(private val maxEntries: Int = 1024) {
    private val cache = object : LinkedHashMap<String, Solved>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Solved>?) = size > maxEntries
    }

    var hits = 0
        private set
    var misses = 0
        private set

    fun solve(tree: LayoutNode, signature: String, bounds: LayoutBounds, safeArea: EdgeInsets, scale: Double): Solved {
        val key = "${bounds.width},${bounds.height};${safeArea.top},${safeArea.left},${safeArea.bottom},${safeArea.right};$scale;$signature"
        cache[key]?.let { hits++; return it }
        misses++
        val r = solveLayout(tree, bounds, safeArea, scale = scale)
        val local = HashMap<String, Frame>(r.frames.size)
        for (k in r.frames.keys) r.localFrame(k)?.let { local[k] = it }
        return Solved(local, r.frames, r.contentSizes, safeArea, r.contentHeight).also { cache[key] = it }
    }

    fun clear() = cache.clear()
}
