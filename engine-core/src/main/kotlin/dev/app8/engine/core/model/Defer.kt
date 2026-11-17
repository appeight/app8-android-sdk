package dev.app8.engine.core.model

/**
 * `defer` on a component envelope (DSL "Deferred sections"): the component is not built with
 * its screen. A placeholder holds its slot in the parent `stackView` until the engine builds it,
 * after the screen is on screen.
 */
public data class Defer(
    val placeholder: Placeholder = Placeholder.none,
    /** Placeholder height in a vertical stack, in points. */
    val estimatedHeight: Double = 0.0,
    /** Placeholder width in a horizontal stack, in points. */
    val estimatedWidth: Double = 0.0,
) {
    @Suppress("EnumEntryName")
    public enum class Placeholder {
        /** Empty space. */
        none,

        /** A neutral rounded block with a sweeping highlight, as wide (or tall) as the stack. */
        shimmer,
    }
}

/** What the engine does with a component's `defer` where it appears. */
public enum class DeferPlacement {
    /** No `defer` on the component. */
    NONE,

    /** A direct `stackView` child: a placeholder now, the component after the screen is shown. */
    DEFERRED,

    /** Not a direct `stackView` child: built with the screen, warning `defer.notInStack`. */
    NOT_IN_STACK,

    /** Inside a deferred component or a per-item template: built with its parent, silently. */
    IGNORED;

    public companion object {
        /** Warning code for [NOT_IN_STACK]. */
        public const val NOT_IN_STACK_WARNING: String = "defer.notInStack"

        /**
         * @param parentType the type of the component's parent; null for a screen root.
         * @param insideDeferred the component is in the subtree of a deferred component.
         * @param insidePerItem the component is in a collection item or a template rendered per item.
         */
        public fun of(
            defer: Defer?,
            parentType: ComponentType?,
            insideDeferred: Boolean,
            insidePerItem: Boolean,
        ): DeferPlacement = when {
            defer == null -> NONE
            insideDeferred || insidePerItem -> IGNORED
            parentType?.isOneOf(ComponentType.Key.stackView) == true -> DEFERRED
            else -> NOT_IN_STACK
        }
    }
}

/**
 * `defer`, read leniently: a malformed value (not an object, a bad field) never rejects the
 * component; unknown placeholders are `none`, negative or non-numeric estimates are `0`.
 */
internal fun decodeDefer(c: Cursor): Defer? {
    if (!c.isObject()) return null
    fun estimate(key: String): Double = c.attempt { it.optDouble(key) }?.takeIf { it > 0 } ?: 0.0
    return Defer(
        placeholder = c.attempt { it.optSafeEnum("placeholder", Defer.Placeholder.none) } ?: Defer.Placeholder.none,
        estimatedHeight = estimate("estimatedHeight"),
        estimatedWidth = estimate("estimatedWidth"),
    )
}
