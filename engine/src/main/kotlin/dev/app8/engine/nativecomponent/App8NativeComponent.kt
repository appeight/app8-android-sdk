package dev.app8.engine.nativecomponent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/**
 * Draws a DSL `native` component whose `module` it is registered under in
 * `App8Config.nativeComponents`. The SDK lays the component out with its DSL `layout`, passes
 * that frame as [Content]'s `modifier`, and recomposes with fresh [App8NativeContext.props]
 * whenever a variable a `{{…}}` prop reads changes.
 *
 * The context says whether the component is on screen ([App8NativeContext.isVisible]); start
 * and stop cameras, players, sensors and timers from it:
 *
 * ```kotlin
 * App8NativeComponent { context, modifier ->
 *     val camera = rememberCamera()
 *     LaunchedEffect(context.isVisible) { if (context.isVisible) camera.start() else camera.stop() }
 *     CameraPreview(camera, modifier)
 * }
 * ```
 *
 * A module the host has not registered renders the component's `fallback` subtree, or an empty
 * view with a `native.module.missing` warning when it has none.
 */
public fun interface App8NativeComponent {
    @Composable
    public fun Content(context: App8NativeContext, modifier: Modifier)
}

/**
 * What a [App8NativeComponent] gets from the DSL.
 *
 * @property props the DSL `props`, `{{…}}` strings resolved for the current variable values
 *   (JSON values: String, Boolean, Long, Double, List, Map; JSON null as Kotlin null).
 * @property emit sends a module event: runs the component's `actions[event]` with [payload] in
 *   scope as `event` (`{{event.signature.id}}`), and forwards it to the instance event bus as
 *   `native.<module>.<event>`. Safe to call from any thread; it runs on the main thread.
 * @property appId the DSL app's id (`app.json` `id`, else `bundleId`), empty when it has neither.
 * @property screenId the screen that shows the component.
 * @property module the registered module name (the DSL `module`).
 * @property isVisible whether the user can see the component (app8-dsl
 *   `components/native.md#visibility`): its screen is the topmost presented screen of the
 *   selected tab (not under a sheet or any modal, not lower in a back stack), the app is in the
 *   foreground (lifecycle between `ON_START` and `ON_STOP`), the component is composed and not
 *   hidden, and it is not in an off-screen render or an off-screen collection cell (prefetched,
 *   recycled, scrolled out). A component in a `scrollView` scrolled out of view stays visible.
 *   `false` on first composition; `true` once an appearing transition has finished, `false` as
 *   soon as a covering or leaving one starts. Each change arrives as a new context
 *   (recomposition), never twice in a row with the same value.
 */
@Immutable
public data class App8NativeContext @JvmOverloads constructor(
    val props: Map<String, Any?>,
    val emit: (event: String, payload: Map<String, Any?>) -> Unit,
    val appId: String,
    val screenId: String,
    val module: String,
    val isVisible: Boolean = false,
)
