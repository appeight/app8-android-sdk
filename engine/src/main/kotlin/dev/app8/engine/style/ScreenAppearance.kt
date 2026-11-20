package dev.app8.engine.style

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.runtime.App8Runtime

/**
 * The app's Material 3 scheme for one appearance: built from the app's own colors
 * ([systemColorScheme] with the chrome accent), or the wallpaper's with `dynamicColor`.
 */
@Composable
internal fun appColorScheme(runtime: App8Runtime, dark: Boolean): ColorScheme {
    if (runtime.dynamicColor) {
        val context = LocalContext.current
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return systemColorScheme(runtime.chromeAccent, dark)
}

/**
 * Draws [content] in [appearance] (iOS `overrideUserInterfaceStyle`): DSL colors resolve for
 * it ([LocalRenderEnv], [LocalApp8Appearance]) and Material chrome (top app bar, dialogs,
 * sheets, pickers) takes the app's scheme for it. Null, or the enclosing appearance, keeps the
 * enclosing theme (and any accent re-theming), with the same composition structure either way
 * so a change of appearance never resets the content's state.
 */
@Composable
internal fun ProvideAppearance(appearance: App8Appearance?, content: @Composable () -> Unit) {
    val env = LocalRenderEnv.current
    val target = appearance ?: env.appearance
    val same = target == env.appearance
    val scoped = remember(env, target) { env.withAppearance(target) }
    val scheme = if (same) MaterialTheme.colorScheme else appColorScheme(env.runtime, target.isDark)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalRenderEnv provides scoped,
            LocalApp8Appearance provides target,
            content = content,
        )
    }
}
