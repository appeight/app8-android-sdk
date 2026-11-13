package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonElement

/** `app.json` (iOS `DSL.Model.App`). */
public data class App(
    val title: String? = null,
    val bundleId: String? = null,
    val defaultUserInterfaceStyle: UserInterfaceStyle? = null,
    val navigation: Navigation? = null,
    /** Reusable animation registry (wrapped or flat inline entries). */
    val animations: List<Animation>? = null,
    /** Reusable screen-transition registry. */
    val transitions: List<ScreenTransition>? = null,
    /** App-wide default transition (lowest-priority fallback). */
    val defaultTransition: ScreenTransition? = null,
    /** App-scope variables. TODO: `Map<String, dev.app8.engine.core.vars.VariableDefinition>`. */
    val variables: Map<String, JsonElement>? = null,
    /** DSL 1.1 platform floors (`platforms.ios.minVersion`, `platforms.android.minSdk`). */
    val platforms: Platforms? = null,
    /** DSL version the app needs (`"dsl": "1.1"`). */
    val dsl: String? = null,
    /** The app's id (`"id"`): names per-app storage such as persisted variables. */
    val id: String? = null,
) {
    /** The start screen of the start flow. */
    public val initialScreenId: String?
        get() = navigation?.let { nav -> nav.flows.firstOrNull { it.id == nav.startFlow }?.startScreen }

    @Suppress("EnumEntryName")
    public enum class UserInterfaceStyle { light, dark }

    public data class Navigation(val startFlow: String, val flows: List<Flow> = emptyList()) {
        public data class Flow(val id: String, val startScreen: String)
    }

    public data class Platforms(val ios: Ios? = null, val android: Android? = null) {
        public data class Ios(val minVersion: String? = null)
        public data class Android(val minSdk: Int? = null)
    }
}

internal fun decodeApp(c: Cursor): App {
    c.obj()
    return App(
        title = c.optString("title"),
        bundleId = c.optString("bundleId"),
        defaultUserInterfaceStyle = c.optSafeEnum("defaultUserInterfaceStyle", App.UserInterfaceStyle.light),
        navigation = c.opt("navigation") { n ->
            n.obj()
            App.Navigation(
                startFlow = n.reqString("startFlow"),
                flows = n.safeList("flows", "App.Navigation.Flow") { f ->
                    f.obj()
                    App.Navigation.Flow(f.reqString("id"), f.reqString("startScreen"))
                },
            )
        },
        animations = c.opt("animations") { a -> a.list(::decodeAnimation) },
        transitions = c.opt("transitions") { a -> a.list(::decodeScreenTransition) },
        defaultTransition = c.opt("defaultTransition", ::decodeScreenTransition),
        variables = c.opt("variables", ::decodeVariables),
        // New in DSL 1.1 and not on iOS yet: decoded leniently so a malformed block never rejects the app.
        platforms = c.attempt { it.opt("platforms", ::decodePlatforms) },
        dsl = c.attempt { it.opt("dsl") { v -> v.stringOrNull() ?: v.doubleOrNull()?.toString() } },
        id = c.attempt { it.optString("id") },
    )
}

private fun decodePlatforms(c: Cursor): App.Platforms {
    c.obj()
    return App.Platforms(
        ios = c.attempt { it.opt("ios") { i -> i.obj(); App.Platforms.Ios(i.attempt { x -> x.optString("minVersion") }) } },
        android = c.attempt { it.opt("android") { a -> a.obj(); App.Platforms.Android(a.attempt { x -> x.optInt("minSdk") }) } },
    )
}
