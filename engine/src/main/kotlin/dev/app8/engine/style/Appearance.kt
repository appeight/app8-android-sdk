package dev.app8.engine.style

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Light or dark, as decided by the engine (iOS: the view's `userInterfaceStyle`),
 * not by `isSystemInDarkTheme()`: an app can force one appearance, and previews
 * render both. Every themed DSL color resolves against this.
 */
public enum class App8Appearance {
    Light,
    Dark,
    ;

    public val isDark: Boolean get() = this == Dark
}

/** The appearance DSL content renders in. The engine provides it at the screen root. */
public val LocalApp8Appearance: ProvidableCompositionLocal<App8Appearance> =
    staticCompositionLocalOf { App8Appearance.Light }
