package dev.app8.engine.style

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import dev.app8.engine.UserInterfaceStyle
import dev.app8.engine.core.model.App

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

/** A DSL `userInterfaceStyle` (`light` / `dark`) as an appearance; null for none. */
internal fun appearanceOf(style: App.UserInterfaceStyle?): App8Appearance? = when (style) {
    App.UserInterfaceStyle.light -> App8Appearance.Light
    App.UserInterfaceStyle.dark -> App8Appearance.Dark
    null -> null
}

/**
 * The app's appearance: the host override (`App8Config` / `App8Instance.userInterfaceStyle`),
 * then the app's `defaultUserInterfaceStyle`, then the system ([systemDark]).
 */
internal fun appAppearance(host: UserInterfaceStyle, appDefault: App.UserInterfaceStyle?, systemDark: Boolean): App8Appearance = when (host) {
    UserInterfaceStyle.LIGHT -> App8Appearance.Light
    UserInterfaceStyle.DARK -> App8Appearance.Dark
    UserInterfaceStyle.UNSPECIFIED -> appearanceOf(appDefault) ?: if (systemDark) App8Appearance.Dark else App8Appearance.Light
}

/**
 * The appearance a screen draws in (iOS `overrideUserInterfaceStyle` on its view
 * controller): its own `userInterfaceStyle`, else [inherited] (the app's, or the
 * presenting screen's for a screen in a sheet or cover).
 */
internal fun screenAppearance(inherited: App8Appearance, own: App.UserInterfaceStyle?): App8Appearance = appearanceOf(own) ?: inherited
