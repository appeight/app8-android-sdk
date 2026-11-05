package dev.app8.engine.core.layout

import dev.app8.engine.core.layout.LayoutAttribute.BOTTOM
import dev.app8.engine.core.layout.LayoutAttribute.CENTER_X
import dev.app8.engine.core.layout.LayoutAttribute.CENTER_Y
import dev.app8.engine.core.layout.LayoutAttribute.HEIGHT
import dev.app8.engine.core.layout.LayoutAttribute.LEADING
import dev.app8.engine.core.layout.LayoutAttribute.TOP
import dev.app8.engine.core.layout.LayoutAttribute.TRAILING
import dev.app8.engine.core.layout.LayoutAttribute.WIDTH
import dev.app8.engine.core.layout.cassowary.Constraint
import dev.app8.engine.core.layout.cassowary.Expression
import dev.app8.engine.core.layout.cassowary.RelationalOperator
import dev.app8.engine.core.layout.cassowary.Solver
import dev.app8.engine.core.layout.cassowary.Strength
import dev.app8.engine.core.layout.cassowary.UnsatisfiableConstraintException
import dev.app8.engine.core.layout.cassowary.Variable
import kotlin.math.max
import kotlin.math.roundToLong

// Auto Layout without UIKit: one Cassowary system per layout root, built the
// way the iOS SDK installs NSLayoutConstraints
// (Components/BaseView/BaseView.swift, makeConstraints(from:in:)) and the way
// UIKit lays out UIStackView / UIScrollView content:
//
//   - An empty layout fills the superview.
//   - Edge shorthands are raw UIKit constants against the superview's edge
//     (`trailing: -16` is 16pt in from the right); only fixed edges apply.
//   - `ignoresSafeArea` shifts the top shorthand, and top constraints to the
//     superview, up by the superview's top safe-area inset.
//   - Unsupported anchor pairs fall back to like-to-like; multipliers only
//     apply to width/height; safe-area guides ignore multipliers.
//   - Inside a stack view only width/height shorthands and width/height
//     constraints apply; the stack positions its arranged subviews.
//   - Views with an intrinsic size get hugging (<=) and compression
//     resistance (>=) constraints at their priorities. Wrapping text is
//     re-measured at its solved width (UILabel's preferredMaxLayoutWidth).
//   - Under-constrained views resolve to zero size at the superview origin.
//
// Priorities are strict: the solver's objective is lexicographic, so a
// higher priority is never traded for any number of lower ones.

/** The root view's frame. [height] null lets the content decide it (scroll content, self-sizing cells). */
public data class LayoutBounds(
    val width: Double,
    val height: Double?,
    val x: Double = 0.0,
    val y: Double = 0.0,
)

public class LayoutResult internal constructor(
    /** Every visited node's frame, in the root's coordinate space (scroll content at zero offset). */
    public val frames: Map<String, Frame>,
    /** Content size per scroll view key. */
    public val contentSizes: Map<String, Size>,
    /** The root's height, or for a content-sized root, how far its content reaches. */
    public val contentHeight: Double,
    /** Solver passes run (safe-area insets and wrapping text need a second). */
    public val passes: Int,
    private val parents: Map<String, String>,
) {
    public operator fun get(key: String): Frame? = frames[key]

    /** The frame relative to the parent (to its content origin for scroll views), as UIView.frame. */
    public fun localFrame(key: String): Frame? {
        val f = frames[key] ?: return null
        val p = parents[key]?.let { frames[it] } ?: return f
        return Frame(f.x - p.x, f.y - p.y, f.width, f.height)
    }
}

/**
 * Lays out [root] in [frame].
 *
 * @param safeArea the root's safe-area insets; nested views get their overlap with it.
 * @param keyboardHeight the keyboard's height from the bottom of the root (0 when hidden).
 * @param scale frames are snapped to `1/scale` points (a 3x screen by default); 0 disables.
 * @param resolveExpression evaluates `{{…}}` dimensions; null leaves the dimension unset.
 */
public fun solveLayout(
    root: LayoutNode,
    frame: LayoutBounds,
    safeArea: EdgeInsets = EdgeInsets.ZERO,
    keyboardHeight: Double = 0.0,
    scale: Double = 3.0,
    maxPasses: Int = 4,
    resolveExpression: (String) -> Double? = { null },
): LayoutResult {
    val measurements = Measurements()
    var previous: Map<String, Frame>? = null
    var pass: LayoutPass? = null
    var count = 0
    while (count < max(1, maxPasses)) {
        count++
        val p = LayoutPass(root, frame, safeArea, keyboardHeight, scale, resolveExpression, previous, measurements)
        p.run()
        val stable = previous != null && p.frames == previous
        pass = p
        previous = p.frames
        if (stable) break
        // Another pass only changes something if a nested safe-area inset was
        // read (it came from the previous frames) or some intrinsic size
        // differs at the width it was just given.
        val remeasureChanges = p.measured.any { (node, used) ->
            measurements.measure(node, p.frames.getValue(node.key).width) != used
        }
        if (!p.insetsUsed && !remeasureChanges) break
    }
    val p = pass!!
    return LayoutResult(p.frames, p.contentSizes, p.contentHeight, count, p.parents)
}

/** Intrinsic sizes per node and width, shared by the passes. */
private class Measurements {
    private val cache = HashMap<String, HashMap<Double?, Size?>>()

    fun measure(node: LayoutNode, maxWidth: Double?): Size? {
        val byWidth = cache.getOrPut(node.key) { HashMap() }
        if (byWidth.containsKey(maxWidth)) return byWidth[maxWidth]
        return node.intrinsicSize!!.measure(maxWidth).also { byWidth[maxWidth] = it }
    }
}

/** Priority of a broken required constraint: above every other priority, below required. */
private const val BROKEN_REQUIRED = 999.5

/**
 * Under-constrained views settle at zero size at the superview origin. Zero size wins
 * over the origin: a view pinned only by its bottom edge sits at that edge with height
 * 0 (as on iOS), not stretched up from the superview's top.
 */
private const val AMBIGUITY = 0.5
private const val AMBIGUITY_SIZE = 0.6

/** Content-sized roots and scroll content are as short as their content allows. */
private const val CONTENT_FIT = 1.0

/** UIStackView keeps its cross size at its largest arranged subview for non-fill alignments. */
private const val STACK_SPANNING_FIT = 51.0

/** Index tie-break between equal priorities in a `fill` stack: earlier views stretch and shrink first. */
private const val FILL_TIE_BREAK = 0.001

private fun interface Anchors {
    fun attr(a: LayoutAttribute): Expression
}

private class Box(name: String) : Anchors {
    val x = Variable("$name.x")
    val y = Variable("$name.y")
    val w = Variable("$name.w")
    val h = Variable("$name.h")

    override fun attr(a: LayoutAttribute): Expression = when (a) {
        LayoutAttribute.LEADING -> Expression.of(x)
        LayoutAttribute.TRAILING -> x + w
        LayoutAttribute.TOP -> Expression.of(y)
        LayoutAttribute.BOTTOM -> y + h
        LayoutAttribute.CENTER_X -> x + w * 0.5
        LayoutAttribute.CENTER_Y -> y + h * 0.5
        LayoutAttribute.WIDTH -> Expression.of(w)
        LayoutAttribute.HEIGHT -> Expression.of(h)
    }
}

/** A view's safeAreaLayoutGuide: its box minus its safe-area insets. */
private class GuideAnchors(private val b: Box, private val i: EdgeInsets) : Anchors {
    override fun attr(a: LayoutAttribute): Expression = when (a) {
        LayoutAttribute.TOP -> b.y + i.top
        LayoutAttribute.BOTTOM -> b.y + b.h - i.bottom
        LayoutAttribute.LEADING -> b.x + i.left
        LayoutAttribute.TRAILING -> b.x + b.w - i.right
        LayoutAttribute.CENTER_X -> b.x + b.w * 0.5 + (i.left - i.right) / 2
        LayoutAttribute.CENTER_Y -> b.y + b.h * 0.5 + (i.top - i.bottom) / 2
        LayoutAttribute.WIDTH -> b.w - (i.left + i.right)
        LayoutAttribute.HEIGHT -> b.h - (i.top + i.bottom)
    }
}

/**
 * The SDK's KeyboardTrackingView: a zero-height view spanning the container,
 * whose bottom sits at the keyboard top, or at the container's safe-area
 * bottom (its bottom, with ignoresSafeArea) when the keyboard is hidden.
 */
private class KeyboardAnchors(
    private val c: Box,
    insets: EdgeInsets,
    keyboardHeight: Double,
    ignoresSafeArea: Boolean,
) : Anchors {
    private val lift = if (ignoresSafeArea) keyboardHeight else insets.bottom + max(0.0, keyboardHeight - insets.bottom)

    override fun attr(a: LayoutAttribute): Expression = when (a) {
        LayoutAttribute.TOP, LayoutAttribute.BOTTOM, LayoutAttribute.CENTER_Y -> c.y + c.h - lift
        LayoutAttribute.LEADING -> Expression.of(c.x)
        LayoutAttribute.TRAILING -> c.x + c.w
        LayoutAttribute.CENTER_X -> c.x + c.w * 0.5
        LayoutAttribute.WIDTH -> Expression.of(c.w)
        LayoutAttribute.HEIGHT -> Expression(0.0)
    }
}

/** Anchor pairs BaseView.makeRelation supports; anything else relates like-to-like. */
private val RELATION_PAIRS: Map<LayoutAttribute, Set<LayoutAttribute>> =
    mapOf(
        LEADING to setOf(LEADING, TRAILING),
        TRAILING to setOf(TRAILING, LEADING),
        TOP to setOf(TOP, BOTTOM),
        BOTTOM to setOf(BOTTOM, TOP),
        CENTER_X to setOf(CENTER_X, LEADING, TRAILING),
        CENTER_Y to setOf(CENTER_Y, TOP, BOTTOM),
        WIDTH to setOf(WIDTH, HEIGHT),
        HEIGHT to setOf(HEIGHT, WIDTH),
    )

/** Pairs BaseView.makeRelationToGuide supports; anything else becomes bottom = guide.bottom. */
private val GUIDE_PAIRS: Map<LayoutAttribute, Set<LayoutAttribute>> =
    mapOf(
        TOP to setOf(TOP, BOTTOM),
        BOTTOM to setOf(BOTTOM, TOP),
        LEADING to setOf(LEADING, TRAILING),
        TRAILING to setOf(TRAILING, LEADING),
        CENTER_X to setOf(CENTER_X),
        CENTER_Y to setOf(CENTER_Y),
        WIDTH to setOf(WIDTH),
        HEIGHT to setOf(HEIGHT),
    )

private class NodeInfo(val node: LayoutNode, val parent: NodeInfo?) {
    val children = ArrayList<NodeInfo>()
    val box = Box(node.key)

    /** The scroll view's content view. */
    val content: Box? = if (node.scroll != null && !node.opaque) Box("${node.key}#content") else null

    /** The view's own safeAreaInsets (from the previous pass for nested views). */
    var insets: EdgeInsets = EdgeInsets.ZERO

    /** The view children are added to, and its safe-area insets. */
    val container: Box get() = content ?: box
    val containerInsets: EdgeInsets get() = if (content != null) EdgeInsets.ZERO else insets

    /** A stack's arranged subview. */
    val isArranged: Boolean get() = parent != null && parent.node.stack != null

    /** Main-axis priority offset from [FILL_TIE_BREAK]. */
    var tieBreak = 0.0
}

private class LayoutPass(
    private val root: LayoutNode,
    private val bounds: LayoutBounds,
    private val safeArea: EdgeInsets,
    private val keyboardHeight: Double,
    private val scale: Double,
    private val resolveExpression: (String) -> Double?,
    private val previous: Map<String, Frame>?,
    private val measurements: Measurements,
) {
    private val solver = Solver()
    private val infos = ArrayList<NodeInfo>()
    private val keys = HashSet<String>()
    private val intrinsicCache = HashMap<NodeInfo, Size?>()

    /** Whether a nested view's safe-area insets (from the previous pass's frames) were used. */
    var insetsUsed = false
        private set

    /** The intrinsic sizes this pass used. */
    val measured = LinkedHashMap<LayoutNode, Size?>()

    lateinit var frames: Map<String, Frame>
    lateinit var contentSizes: Map<String, Size>
    val parents = HashMap<String, String>()
    var contentHeight = 0.0

    fun run() {
        val rootInfo = build(root, null)
        computeInsets()

        val rb = rootInfo.box
        add(Expression.of(rb.x), LayoutRelation.EQUAL, Expression(bounds.x), 1000.0)
        add(Expression.of(rb.y), LayoutRelation.EQUAL, Expression(bounds.y), 1000.0)
        add(Expression.of(rb.w), LayoutRelation.EQUAL, Expression(bounds.width), 1000.0)
        if (bounds.height != null) {
            add(Expression.of(rb.h), LayoutRelation.EQUAL, Expression(bounds.height), 1000.0)
        } else {
            add(Expression.of(rb.h), LayoutRelation.EQUAL, Expression(0.0), CONTENT_FIT)
        }

        for (info in infos) {
            info.content?.let { installScrollContent(info, it) }
            if (info.parent == null) continue
            add(Expression.of(info.box.w), LayoutRelation.GREATER_OR_EQUAL, Expression(0.0), 1000.0)
            add(Expression.of(info.box.h), LayoutRelation.GREATER_OR_EQUAL, Expression(0.0), 1000.0)
        }

        for (info in infos) {
            if (info.parent != null) installFor(info)
            if (info.node.stack != null && !info.node.opaque) installStack(info)
            installIntrinsic(info)
        }

        // Last, so real constraints pick genuinely free variables as row
        // subjects: added first, these weak pulls make every variable basic in
        // their error terms and the tableau fills in (several times slower).
        for (info in infos) {
            val parent = info.parent ?: continue
            val v = info.box
            val p = parent.container
            add(Expression.of(v.x), LayoutRelation.EQUAL, Expression.of(p.x), AMBIGUITY)
            add(Expression.of(v.y), LayoutRelation.EQUAL, Expression.of(p.y), AMBIGUITY)
            add(Expression.of(v.w), LayoutRelation.EQUAL, Expression(0.0), AMBIGUITY_SIZE)
            add(Expression.of(v.h), LayoutRelation.EQUAL, Expression(0.0), AMBIGUITY_SIZE)
        }

        solver.updateVariables()
        collect(rootInfo)
    }

    private fun build(node: LayoutNode, parent: NodeInfo?): NodeInfo {
        val info = NodeInfo(node, parent)
        require(keys.add(node.key)) { "duplicate layout node key: ${node.key}" }
        infos.add(info)
        if (parent != null) parents[node.key] = parent.node.key
        if (!node.opaque) for (child in node.children) info.children.add(build(child, info))
        return info
    }

    // region safe area

    /**
     * UIKit gives every view the part of its superview's safe-area insets it
     * overlaps. Nested frames come from the previous pass; scroll content
     * gets none (the scroll view adjusts its content inset instead).
     */
    private fun computeInsets() {
        for (info in infos) {
            val parent = info.parent
            info.insets = when {
                parent == null -> safeArea
                parent.content != null -> EdgeInsets.ZERO
                else -> overlap(info, parent)
            }
        }
    }

    private fun overlap(info: NodeInfo, parent: NodeInfo): EdgeInsets {
        val f = previous?.get(info.node.key) ?: return EdgeInsets.ZERO
        val pf = previous[parent.node.key] ?: return EdgeInsets.ZERO
        val pi = parent.insets
        val safeTop = pf.y + pi.top
        val safeBottom = pf.maxY - pi.bottom
        val safeLeft = pf.x + pi.left
        val safeRight = pf.maxX - pi.right
        return EdgeInsets(
            top = (safeTop - f.y).coerceIn(0.0, max(0.0, f.height)),
            left = (safeLeft - f.x).coerceIn(0.0, max(0.0, f.width)),
            bottom = (f.maxY - safeBottom).coerceIn(0.0, max(0.0, f.height)),
            right = (f.maxX - safeRight).coerceIn(0.0, max(0.0, f.width)),
        )
    }

    private fun insetsOf(info: NodeInfo): EdgeInsets {
        if (info.parent != null) insetsUsed = true
        return info.insets
    }

    private fun containerInsetsOf(info: NodeInfo): EdgeInsets {
        if (info.content == null && info.parent != null) insetsUsed = true
        return info.containerInsets
    }

    // endregion

    // region constraints

    private fun add(lhs: Expression, op: LayoutRelation, rhs: Expression, priority: Double) {
        val operator = when (op) {
            LayoutRelation.EQUAL -> RelationalOperator.EQ
            LayoutRelation.LESS_OR_EQUAL -> RelationalOperator.LE
            LayoutRelation.GREATER_OR_EQUAL -> RelationalOperator.GE
        }
        val strength = if (priority >= 1000.0) Strength.required else priority
        try {
            solver.addConstraint(Constraint(lhs, operator, rhs, strength))
        } catch (_: UnsatisfiableConstraintException) {
            // Auto Layout breaks one of the conflicting required constraints and
            // logs; keep this one as the strongest preference instead.
            solver.addConstraint(Constraint(lhs, operator, rhs, BROKEN_REQUIRED))
        }
    }

    private fun add(lhs: Expression, op: LayoutRelation, constant: Double, priority: Double) =
        add(lhs, op, Expression(constant), priority)

    private fun installScrollContent(info: NodeInfo, content: Box) {
        val b = info.box
        // Content view pinned to the content layout guide (zero offset), and to
        // the frame layout guide on the non-scrolling axis.
        add(Expression.of(content.x), LayoutRelation.EQUAL, Expression.of(b.x), 1000.0)
        add(Expression.of(content.y), LayoutRelation.EQUAL, Expression.of(b.y), 1000.0)
        val (fixed, free) = when (info.node.scroll!!.direction) {
            ScrollDirection.VERTICAL -> LayoutAttribute.WIDTH to LayoutAttribute.HEIGHT
            ScrollDirection.HORIZONTAL -> LayoutAttribute.HEIGHT to LayoutAttribute.WIDTH
        }
        add(content.attr(fixed), LayoutRelation.EQUAL, b.attr(fixed), 1000.0)
        add(content.attr(free), LayoutRelation.GREATER_OR_EQUAL, 0.0, 1000.0)
        add(content.attr(free), LayoutRelation.EQUAL, 0.0, CONTENT_FIT)
    }

    private fun dimension(info: NodeInfo, d: Dimension, a: LayoutAttribute) {
        val me = info.box.attr(a)
        when (d) {
            is Dimension.Fixed -> add(me, LayoutRelation.EQUAL, d.value, 1000.0)
            is Dimension.Fraction -> add(me, LayoutRelation.EQUAL, info.parent!!.container.attr(a) * d.value, 1000.0)
            is Dimension.Expression -> resolveExpression(d.source)?.let { add(me, LayoutRelation.EQUAL, it, 1000.0) }
        }
    }

    private fun selfDimension(info: NodeInfo, c: LayoutConstraint) {
        // Self-targeted edges/centers are meaningless (an assertion on iOS).
        if (!c.type.isDimension) return
        add(info.box.attr(c.type), c.op, c.constant, priorityOf(c.priority))
    }

    private fun installFor(info: NodeInfo) {
        val parent = info.parent!!
        val raw = info.node.layout

        if (info.isArranged) {
            // UIStackView positions arranged subviews: only sizes apply.
            raw?.width?.let { dimension(info, it, LayoutAttribute.WIDTH) }
            raw?.height?.let { dimension(info, it, LayoutAttribute.HEIGHT) }
            for (c in raw?.constraints.orEmpty()) {
                if (!c.type.isDimension) continue
                val target = c.target
                if (target == null) {
                    selfDimension(info, c)
                    continue
                }
                val anchors = resolveTarget(target, info, ignoresSafeArea = false) ?: continue
                relate(info, c, anchors, c.constant)
            }
            return
        }

        val layout = raw?.takeUnless { it.isEmpty } ?: Layout.FILL_SUPERVIEW
        layout.width?.let { dimension(info, it, LayoutAttribute.WIDTH) }
        layout.height?.let { dimension(info, it, LayoutAttribute.HEIGHT) }

        val ignoresSafeArea = layout.ignoresSafeArea == true
        val topShift = if (ignoresSafeArea) -containerInsetsOf(parent).top else 0.0

        val me = info.box
        val sv = parent.container
        fun edge(d: Dimension?, a: LayoutAttribute, shift: Double = 0.0) {
            if (d !is Dimension.Fixed) return // fractional / expression edges are not supported
            add(me.attr(a), LayoutRelation.EQUAL, sv.attr(a) + (d.value + shift), 1000.0)
        }
        edge(layout.leading, LayoutAttribute.LEADING)
        edge(layout.trailing, LayoutAttribute.TRAILING)
        edge(layout.top, LayoutAttribute.TOP, topShift)
        edge(layout.bottom, LayoutAttribute.BOTTOM)

        for (c in layout.constraints) {
            val target = c.target
            if (target == null) {
                selfDimension(info, c)
                continue
            }
            if (target is ConstraintTarget.SafeArea) {
                val guide = resolveGuide(target, info) ?: continue
                relateToGuide(info, c, guide)
                continue
            }
            val anchors = resolveTarget(target, info, ignoresSafeArea) ?: continue
            var constant = c.constant
            if (ignoresSafeArea && c.type == LayoutAttribute.TOP && target == ConstraintTarget.Superview) {
                constant += topShift
            }
            relate(info, c, anchors, constant)
        }
    }

    /** BaseView.makeRelation. */
    private fun relate(info: NodeInfo, c: LayoutConstraint, target: Anchors, constant: Double) {
        val wanted = c.attribute ?: c.type
        val to = if (wanted in RELATION_PAIRS.getValue(c.type)) wanted else c.type
        val multiplier = if (c.type.isDimension) c.multiplier ?: 1.0 else 1.0
        add(info.box.attr(c.type), c.op, target.attr(to) * multiplier + constant, priorityOf(c.priority))
    }

    /** BaseView.makeRelationToGuide (no multiplier). */
    private fun relateToGuide(info: NodeInfo, c: LayoutConstraint, guide: Anchors) {
        val to = c.attribute ?: c.type
        val (from, toAttr) = if (to in GUIDE_PAIRS.getValue(c.type)) c.type to to else LayoutAttribute.BOTTOM to LayoutAttribute.BOTTOM
        add(info.box.attr(from), c.op, guide.attr(toAttr) + c.constant, priorityOf(c.priority))
    }

    private fun resolveTarget(target: ConstraintTarget, info: NodeInfo, ignoresSafeArea: Boolean): Anchors? {
        val parent = info.parent!!
        return when (target) {
            ConstraintTarget.Superview -> parent.container
            ConstraintTarget.SelfView -> info.box
            is ConstraintTarget.Sibling -> sibling(info, target.id)?.box
            ConstraintTarget.Keyboard ->
                KeyboardAnchors(parent.container, containerInsetsOf(parent), keyboardHeight, ignoresSafeArea)
            is ConstraintTarget.SafeArea -> null
        }
    }

    private fun resolveGuide(target: ConstraintTarget.SafeArea, info: NodeInfo): Anchors? {
        val parent = info.parent!!
        val id = target.id ?: return GuideAnchors(parent.container, containerInsetsOf(parent))
        val owner = sibling(info, id) ?: return null
        return GuideAnchors(owner.box, insetsOf(owner))
    }

    /** A sibling by DSL id: a direct sibling first, then any view under the same superview. */
    private fun sibling(info: NodeInfo, id: String): NodeInfo? {
        val parent = info.parent ?: return null
        parent.children.firstOrNull { it !== info && it.node.id == id }?.let { return it }
        val queue = ArrayDeque(parent.children)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c !== info && c.node.id == id) return c
            queue.addAll(c.children)
        }
        return null
    }

    private fun priorityOf(p: Float): Double = LayoutPriority.clamp(p).toDouble()

    // endregion

    // region stack view

    private fun installStack(info: NodeInfo) {
        val s = info.node.stack!!
        val sv = info.box
        val vertical = s.axis == StackAxis.VERTICAL
        val lead = if (vertical) LayoutAttribute.TOP else LayoutAttribute.LEADING
        val trail = if (vertical) LayoutAttribute.BOTTOM else LayoutAttribute.TRAILING
        val main = if (vertical) LayoutAttribute.HEIGHT else LayoutAttribute.WIDTH
        val crossLead = if (vertical) LayoutAttribute.LEADING else LayoutAttribute.TOP
        val crossTrail = if (vertical) LayoutAttribute.TRAILING else LayoutAttribute.BOTTOM
        val cross = if (vertical) LayoutAttribute.WIDTH else LayoutAttribute.HEIGHT
        val crossCenter = if (vertical) LayoutAttribute.CENTER_X else LayoutAttribute.CENTER_Y
        val mainCenter = if (vertical) LayoutAttribute.CENTER_Y else LayoutAttribute.CENTER_X
        val eq = LayoutRelation.EQUAL

        // Hidden arranged subviews collapse to zero along the axis and take no spacing.
        for (c in info.children) if (c.node.hidden) add(c.box.attr(main), eq, 0.0, 1000.0)

        val items = info.children.filter { !it.node.hidden }
        if (items.isEmpty()) {
            add(sv.attr(main), eq, 0.0, LayoutPriority.DEFAULT_LOW.toDouble())
            return
        }

        items.forEachIndexed { i, c ->
            val cv = c.box
            if (i == 0) {
                add(cv.attr(lead), eq, sv.attr(lead), 1000.0)
            } else {
                val pv = items[i - 1].box
                val spaced = pv.attr(trail) + s.spacing
                when (s.distribution) {
                    StackDistribution.EQUAL_SPACING, StackDistribution.EQUAL_CENTERING ->
                        add(cv.attr(lead), LayoutRelation.GREATER_OR_EQUAL, spaced, 1000.0)
                    else -> add(cv.attr(lead), eq, spaced, 1000.0)
                }
            }
            if (i == items.size - 1) add(cv.attr(trail), eq, sv.attr(trail), 1000.0)
            if (s.distribution == StackDistribution.FILL_EQUALLY && i > 0) {
                add(cv.attr(main), eq, items[0].box.attr(main), 1000.0)
            }
            if (s.distribution == StackDistribution.FILL) c.tieBreak = i * FILL_TIE_BREAK

            when (s.alignment) {
                StackAlignment.FILL -> {
                    add(cv.attr(crossLead), eq, sv.attr(crossLead), 1000.0)
                    add(cv.attr(crossTrail), eq, sv.attr(crossTrail), 1000.0)
                }
                else -> {
                    add(cv.attr(crossLead), LayoutRelation.GREATER_OR_EQUAL, sv.attr(crossLead), 1000.0)
                    add(cv.attr(crossTrail), LayoutRelation.LESS_OR_EQUAL, sv.attr(crossTrail), 1000.0)
                    val a = when (s.alignment) {
                        StackAlignment.LEADING -> crossLead
                        StackAlignment.CENTER -> crossCenter
                        else -> crossTrail
                    }
                    add(cv.attr(a), eq, sv.attr(a), 1000.0)
                }
            }
        }
        if (s.alignment != StackAlignment.FILL) {
            // As tall/wide as the largest arranged subview when nothing else decides.
            add(sv.attr(cross), eq, 0.0, STACK_SPANNING_FIT)
        }

        when (s.distribution) {
            StackDistribution.EQUAL_SPACING -> for (i in 2 until items.size) {
                val a = items[i - 2].box
                val b = items[i - 1].box
                val c = items[i].box
                add(b.attr(lead) - a.attr(trail), eq, c.attr(lead) - b.attr(trail), 1000.0)
            }
            StackDistribution.EQUAL_CENTERING -> for (i in 2 until items.size) {
                val a = items[i - 2].box
                val b = items[i - 1].box
                val c = items[i].box
                add(b.attr(mainCenter) - a.attr(mainCenter), eq, c.attr(mainCenter) - b.attr(mainCenter), 1000.0)
            }
            StackDistribution.FILL_PROPORTIONALLY -> {
                // Sizes keep the ratio of the intrinsic sizes along the axis.
                val weighted = items.mapNotNull { c ->
                    val size = intrinsicOf(c) ?: return@mapNotNull null
                    val w = if (vertical) size.height else size.width
                    if (w > 0.0) c to w else null
                }
                if (weighted.size >= 2) {
                    val (first, w0) = weighted[0]
                    for ((c, w) in weighted.drop(1)) {
                        add(c.box.attr(main), eq, first.box.attr(main) * (w / w0), 1000.0)
                    }
                }
            }
            else -> Unit
        }
    }

    // endregion

    // region intrinsic size

    private fun intrinsicOf(info: NodeInfo): Size? {
        if (info.node.intrinsicSize == null) return null
        return intrinsicCache.getOrPut(info) {
            measurements.measure(info.node, previous?.get(info.node.key)?.width).also { measured[info.node] = it }
        }
    }

    private fun installIntrinsic(info: NodeInfo) {
        val size = intrinsicOf(info) ?: return
        val node = info.node
        val horizontalMain = info.parent?.node?.stack?.axis == StackAxis.HORIZONTAL
        val tieH = if (horizontalMain) info.tieBreak else 0.0
        val tieV = if (!horizontalMain) info.tieBreak else 0.0
        val hug = node.layout?.contentHuggingPriority
        val cr = node.layout?.contentCompressionResistancePriority
        val v = info.box
        if (size.width >= 0.0) {
            add(Expression.of(v.w), LayoutRelation.LESS_OR_EQUAL, size.width, priorityOf(hug?.h ?: node.hugging.h) + tieH)
            add(Expression.of(v.w), LayoutRelation.GREATER_OR_EQUAL, size.width, priorityOf(cr?.h ?: node.compressionResistance.h) + tieH)
        }
        if (size.height >= 0.0) {
            add(Expression.of(v.h), LayoutRelation.LESS_OR_EQUAL, size.height, priorityOf(hug?.v ?: node.hugging.v) + tieV)
            add(Expression.of(v.h), LayoutRelation.GREATER_OR_EQUAL, size.height, priorityOf(cr?.v ?: node.compressionResistance.v) + tieV)
        }
    }

    // endregion

    private fun snap(v: Double): Double =
        if (scale > 0.0) (v * scale).roundToLong() / scale + 0.0 else v + 0.0

    private fun collect(rootInfo: NodeInfo) {
        val out = LinkedHashMap<String, Frame>()
        val sizes = LinkedHashMap<String, Size>()
        var bottom = 0.0
        for (info in infos) {
            val b = info.box
            val f = Frame(snap(b.x.value), snap(b.y.value), snap(b.w.value), snap(b.h.value))
            out[info.node.key] = f
            if (info !== rootInfo) bottom = max(bottom, f.maxY)
            info.content?.let { sizes[info.node.key] = Size(snap(it.w.value), snap(it.h.value)) }
        }
        frames = out
        contentSizes = sizes
        val rf = out.getValue(rootInfo.node.key)
        contentHeight = bounds.height ?: max(rf.height, bottom - rf.y)
    }
}
