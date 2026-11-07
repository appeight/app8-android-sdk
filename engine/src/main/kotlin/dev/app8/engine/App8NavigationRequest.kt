package dev.app8.engine

/**
 * Navigation the host asks of a running app (iOS `NavigationRequest` posted as
 * `.app8NavigationRequest`). Pass to [App8Instance.navigate]. Requests run in order;
 * after [CompleteFlow] the next ones wait (briefly) for the new flow's tab bar, so
 * `CompleteFlow` → `SelectTab` → `Push` lands in the selected tab's stack.
 */
public sealed interface App8NavigationRequest {
    /** Pushes [screenId] on the active stack (the selected tab's, inside a tab bar). Param values may be `JsonElement`s. */
    public data class Push(public val screenId: String, public val params: Map<String, Any?> = emptyMap()) : App8NavigationRequest

    /** Pops the active stack one screen. */
    public data object Pop : App8NavigationRequest

    /** Pops the active stack to its root. */
    public data object PopToRoot : App8NavigationRequest

    /** Dismisses the top presented modal. */
    public data object Dismiss : App8NavigationRequest

    /** Selects a tab of the base flow's `tabBarScreen` by [id] or [index]. */
    public data class SelectTab(public val index: Int? = null, public val id: String? = null) : App8NavigationRequest

    /** Switches to the flow [destination] (DSL `completeFlow`); the current flow is ignored. */
    public data class CompleteFlow(public val destination: String) : App8NavigationRequest
}
