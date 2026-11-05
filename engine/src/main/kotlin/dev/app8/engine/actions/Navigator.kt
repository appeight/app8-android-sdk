package dev.app8.engine.actions

import androidx.compose.runtime.Stable
import dev.app8.engine.core.model.Action

/** Where a screen's navigation actions go (iOS `NavigationRequest` handled by the containers). */
@Stable
internal interface Navigator {
    fun push(screenId: String, params: Map<String, Any?>)
    fun pop(toRoot: Boolean)
    fun present(screenId: String, params: Map<String, Any?>, presentation: Action.PresentationStyle, detents: List<Action.SheetDetent>?, grabber: Boolean?)
    fun dismiss()
    fun selectTab(index: Int?, id: String?)
    fun completeFlow(destination: String)

    /** Navigation that goes nowhere (standalone previews, tests). */
    object None : Navigator {
        override fun push(screenId: String, params: Map<String, Any?>) = Unit
        override fun pop(toRoot: Boolean) = Unit
        override fun present(screenId: String, params: Map<String, Any?>, presentation: Action.PresentationStyle, detents: List<Action.SheetDetent>?, grabber: Boolean?) = Unit
        override fun dismiss() = Unit
        override fun selectTab(index: Int?, id: String?) = Unit
        override fun completeFlow(destination: String) = Unit
    }
}

/** Platform side effects of actions; the Compose host implements them, tests record them. */
internal interface HostEffects {
    fun openUrl(url: String, presentation: Action.URLPresentation)
    fun haptic(style: Action.HapticStyle)
    fun alert(request: AlertRequest)
}

internal data class AlertRequest(
    val title: String?,
    val message: String?,
    val buttons: List<Button>,
) {
    data class Button(val title: String, val style: Action.AlertActionStyle, val onClick: () -> Unit)
}
