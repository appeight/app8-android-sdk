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
 */
@Immutable
public data class App8NativeContext(
    val props: Map<String, Any?>,
    val emit: (event: String, payload: Map<String, Any?>) -> Unit,
    val appId: String,
    val screenId: String,
    val module: String,
)
