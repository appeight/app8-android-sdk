package dev.app8.engine

import dev.app8.engine.events.App8AnalyticsConfig

/** Instance options fixed at creation. Mutable per-instance settings live on [App8Instance]. */
public data class App8Config(
    /** Which auto analytics events fire. */
    val analytics: App8AnalyticsConfig = App8AnalyticsConfig(),
    /** Initial appearance override; [UserInterfaceStyle.UNSPECIFIED] follows the app, then the system. */
    val userInterfaceStyle: UserInterfaceStyle = UserInterfaceStyle.UNSPECIFIED,
    /** Initial locale override (BCP 47); null follows the device. */
    val locale: String? = null,
    /** Engine log level. */
    val logLevel: App8LogLevel = App8LogLevel.NONE,
    /** Device floor check. Tests and tooling may relax it; production hosts keep the default. */
    val enforcePlatformFloor: Boolean = true,
    /** Draws `map` components (the `engine-maps` module's `GoogleMapsProvider`, or your own); null shows a static placeholder. */
    val mapProvider: dev.app8.engine.maps.App8MapProvider? = null,
    /**
     * Material You wallpaper colors for system chrome (sheets, dialogs, pickers, the
     * navigation bar indicator). Off by default: chrome follows the app's own DSL colors
     * (app8-dsl platforms.md, "Android system colors"), so a brand app never shows
     * the user's wallpaper palette.
     */
    val dynamicColor: Boolean = false,
    /**
     * Host views for DSL `native` components, keyed by their `module` (`"signaturePad"`). A module
     * missing here renders the component's `fallback`, or nothing.
     */
    val nativeComponents: Map<String, dev.app8.engine.nativecomponent.App8NativeComponent> = emptyMap(),
)

/** iOS `UIUserInterfaceStyle`. */
public enum class UserInterfaceStyle { UNSPECIFIED, LIGHT, DARK }

public enum class App8LogLevel { NONE, ERROR, WARNING, DEBUG }
