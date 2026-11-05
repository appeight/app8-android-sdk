package dev.app8.engine

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import dev.app8.engine.core.model.App
import dev.app8.engine.events.App8AnalyticsBus
import dev.app8.engine.events.App8AnalyticsConfig
import dev.app8.engine.events.App8EventBus
import dev.app8.engine.navigation.AlertHost
import dev.app8.engine.navigation.ComposeHostEffects
import dev.app8.engine.navigation.FlowController
import dev.app8.engine.navigation.FlowHost
import dev.app8.engine.navigation.LocalAppScope
import dev.app8.engine.navigation.LocalHostEffects
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.Scope
import dev.app8.engine.runtime.App8Runtime
import dev.app8.engine.style.App8Appearance
import dev.app8.engine.style.App8FontProvider
import dev.app8.engine.style.App8TextScaling
import dev.app8.engine.style.LocalApp8Appearance
import dev.app8.engine.style.LocalApp8FontProvider
import dev.app8.engine.style.LocalApp8TextScaling
import dev.app8.engine.style.TextSizer
import kotlinx.coroutines.flow.StateFlow

/**
 * A running App8 app (iOS `App8.Instance`). Create with [App8.instance]; draw with [App]
 * (the whole app: flows, navigation, tabs, sheets) or [Screen] (one screen and whatever it
 * navigates to). Confine to the main thread.
 */
public class App8Instance internal constructor(
    context: Context,
    dataSource: App8DataSource,
    config: App8Config,
) {
    internal val runtime = App8Runtime(context, dataSource, config)
    private val appScope = Scope(runtime.appStore, null)

    /** Host-facing `emit` events. */
    public val eventBus: App8EventBus get() = runtime.eventBus

    /** Analytics events (author-declared and auto). */
    public val analyticsBus: App8AnalyticsBus get() = runtime.analyticsBus

    public var analyticsConfig: App8AnalyticsConfig
        get() = runtime.analyticsConfig
        set(value) {
            runtime.analyticsConfig = value
        }

    /** Appearance override for every screen; [UserInterfaceStyle.UNSPECIFIED] follows the app's `defaultUserInterfaceStyle`, then the system. */
    public var userInterfaceStyle: UserInterfaceStyle by mutableStateOf(config.userInterfaceStyle)

    /** Fonts the host ships, by DSL font name. */
    public var fontProvider: App8FontProvider by mutableStateOf(App8FontProvider.System)

    /** Whether DSL text follows the system font size ([App8TextScaling.FollowSystem], default) or matches iOS exactly. */
    public var textScaling: App8TextScaling by mutableStateOf(App8TextScaling.FollowSystem)

    public var logLevel: App8LogLevel
        get() = runtime.diagnostics.level
        set(value) {
            runtime.diagnostics.level = value
        }

    /** Locale for `$i18n` lookups and formatters; null reverts to the device locale. */
    public fun setLocale(locale: String?) {
        runtime.translations.setActive(locale)
    }

    /** Override → device → the app's `defaultLocale`. */
    public val currentLocale: String get() = runtime.translations.activeLocaleTag

    /** Outstanding screen loads and image requests (0 = idle). Tooling waits for it. */
    public val pendingWork: StateFlow<Int> get() = runtime.pending

    /** Engine warnings so far (`code: message`): unmapped icons, placeholders, decode drops, layout failures. */
    public fun diagnostics(): List<String> = runtime.diagnostics.snapshot()

    public fun clearDiagnostics(): Unit = runtime.diagnostics.clear()

    /** Loads the app manifest, styles, translations and templates. @throws App8Error */
    public suspend fun prefetch() {
        val app = runtime.ensureReady()
        checkFloor(app)?.let { throw it }
    }

    /** The whole app from `navigation.startFlow` (iOS `startApp()`). Errors go to [onError]. */
    @Composable
    public fun App(modifier: Modifier = Modifier, onError: (App8Error) -> Unit = {}) {
        Root(modifier, onError, flowId = { it.navigation?.startFlow }) { app -> app.initialScreenId }
    }

    /** One flow of the manifest, from its start screen (iOS `renderFlow(flowId:)`). */
    @Composable
    public fun Flow(flowId: String, modifier: Modifier = Modifier, onError: (App8Error) -> Unit = {}) {
        Root(modifier, onError, flowId = { flowId }) { app -> app.navigation?.flows?.firstOrNull { it.id == flowId }?.startScreen }
    }

    /** A single screen with [params] (iOS `renderScreen(screenId:options:)`); it can still navigate. */
    @Composable
    public fun Screen(screenId: String, params: Map<String, Any?> = emptyMap(), modifier: Modifier = Modifier, onError: (App8Error) -> Unit = {}) {
        Root(modifier, onError, params) { screenId }
    }

    @Composable
    private fun Root(
        modifier: Modifier,
        onError: (App8Error) -> Unit,
        params: Map<String, Any?> = emptyMap(),
        flowId: (App) -> String? = { null },
        start: (App) -> String?,
    ) {
        val loaded by produceState<Result<App>?>(null) {
            runtime.beginWork()
            value = runCatching { runtime.ensureReady() }
            runtime.endWork()
        }
        val result = loaded ?: return Box(modifier.fillMaxSize())
        val app = result.getOrElse { e ->
            val error = e as? App8Error ?: App8Error.AppDecodeFailed(e)
            LaunchedEffect(error) { onError(error) }
            return Box(modifier.fillMaxSize())
        }
        val floor = checkFloor(app)
        val startScreen = start(app)
        if (floor != null || startScreen.isNullOrEmpty()) {
            LaunchedEffect(app) { onError(floor ?: App8Error.AppMissingInitialScreenId()) }
            return Box(modifier.fillMaxSize())
        }

        val dark = when (userInterfaceStyle) {
            UserInterfaceStyle.LIGHT -> false
            UserInterfaceStyle.DARK -> true
            UserInterfaceStyle.UNSPECIFIED -> when (app.defaultUserInterfaceStyle) {
                App.UserInterfaceStyle.light -> false
                App.UserInterfaceStyle.dark -> true
                null -> isSystemInDarkTheme()
            }
        }
        val appearance = if (dark) App8Appearance.Dark else App8Appearance.Light
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer(cacheSize = 128)
        val fonts = fontProvider
        val scaling = textScaling
        val env = remember(appearance, density, measurer, fonts, scaling) {
            RenderEnv(runtime, appearance, density, measurer, fonts, TextSizer.of(scaling, density))
        }
        val context = LocalContext.current
        val view = LocalView.current
        val effects = remember(context, view) { ComposeHostEffects(context, view, runtime.diagnostics) }
        val flow = remember(app, startScreen) {
            FlowController(app, startScreen, params, flowId(app)) { runtime.diagnostics.warn("navigation", it) }
        }
        // Readable system bar icons over DSL content: light icons in dark appearance.
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            CompositionLocalProvider(
                LocalApp8Appearance provides appearance,
                LocalApp8FontProvider provides fonts,
                LocalApp8TextScaling provides scaling,
                LocalRenderEnv provides env,
                LocalAppScope provides appScope,
                LocalHostEffects provides effects,
            ) {
                FlowHost(flow, modifier)
                AlertHost(effects)
            }
        }
    }

    private fun checkFloor(app: App): App8Error? {
        val required = app.platforms?.android?.minSdk ?: return null
        return if (Build.VERSION.SDK_INT < required) App8Error.UnsupportedPlatform(Build.VERSION.SDK_INT, required) else null
    }
}
