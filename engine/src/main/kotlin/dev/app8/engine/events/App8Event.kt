package dev.app8.engine.events

/** A host-facing event from an `emit` action (iOS `App8Event`). [payload] is exactly what the DSL author wrote, resolved. */
public data class App8Event(
    val name: String,
    val screenId: String,
    val componentId: String?,
    val componentType: String?,
    val locale: String? = null,
    val engineVersion: String = "",
    val cloudVersion: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val timestampMillis: Long = System.currentTimeMillis(),
)

/** An analytics event, author-declared or auto-fired (iOS `App8AnalyticsEvent`). */
public data class App8AnalyticsEvent(
    val name: String,
    val screenId: String? = null,
    val componentId: String? = null,
    val componentType: String? = null,
    val locale: String? = null,
    val engineVersion: String = "",
    val cloudVersion: String? = null,
    val properties: Map<String, Any?> = emptyMap(),
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    /** Reserved auto-event names (`app8-dsl/docs/analytics-events.spec.md`). */
    public object Auto {
        public const val SCREEN_APPEARED: String = "app8.screen.appeared"
        public const val SCREEN_DISMISSED: String = "app8.screen.dismissed"
        public const val SCREEN_RENDERED: String = "app8.screen.rendered"
        public const val SCREEN_SHORTCIRCUIT: String = "app8.screen.shortcircuit"
        public const val COMPONENT_TAPPED: String = "app8.component.tapped"
        public const val NAVIGATION_PUSHED: String = "app8.navigation.pushed"
        public const val URL_OPENED: String = "app8.url.opened"
        public const val RENDER_FAILED: String = "app8.render.failed"
        public const val RENDER_FALLBACK: String = "app8.render.fallback"
    }

    public companion object {
        public val reservedNames: Set<String> = setOf(
            Auto.SCREEN_APPEARED, Auto.SCREEN_DISMISSED, Auto.SCREEN_RENDERED, Auto.SCREEN_SHORTCIRCUIT,
            Auto.COMPONENT_TAPPED, Auto.NAVIGATION_PUSHED, Auto.URL_OPENED, Auto.RENDER_FAILED, Auto.RENDER_FALLBACK,
        )

        /** Keys the bus merges into [properties]; omitted when their source field is null. */
        public val canonicalKeys: Set<String> =
            setOf("screen_id", "component_id", "component_type", "locale", "engine_version", "cloud_version")
    }
}

/** Per-instance toggles for auto-fired analytics (iOS `App8AnalyticsConfig`). */
public data class App8AnalyticsConfig(
    val autoScreenEvents: Boolean = true,
    val autoComponentTaps: Boolean = true,
    val autoNavigationEvents: Boolean = true,
    val autoUrlEvents: Boolean = true,
    val autoCloudEvents: Boolean = true,
)
