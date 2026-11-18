package dev.app8.engine.actions

import androidx.compose.runtime.Stable
import dev.app8.engine.core.model.Action

/** Where a screen's navigation actions go (iOS `NavigationRequest` handled by the containers). */
@Stable
internal interface Navigator {
    /** [transition]: the action's `transition` (null: the screen's default, then the app's). */
    fun push(screenId: String, params: Map<String, Any?>, transition: dev.app8.engine.core.model.ScreenTransition? = null, zoom: dev.app8.engine.navigation.transitions.ZoomOrigin? = null)
    fun pop(toRoot: Boolean)

    /** [transition]: a custom modal transition (`mode: modal`); null for a system presentation. */
    fun present(screenId: String, params: Map<String, Any?>, presentation: Action.PresentationStyle, detents: List<Action.SheetDetent>?, grabber: Boolean?, transition: dev.app8.engine.core.model.ScreenTransition? = null, zoom: dev.app8.engine.navigation.transitions.ZoomOrigin? = null)
    fun dismiss()

    /**
     * `dismiss` with more actions after it in the same list: dismisses, waits until the
     * dismissal has finished, and returns the navigator the rest of the list uses (the
     * presenting screen's stack).
     */
    suspend fun dismissAndAwait(): Navigator {
        dismiss()
        return this
    }

    fun selectTab(index: Int?, id: String?)
    fun completeFlow(destination: String)

    /** Navigation that goes nowhere (standalone previews, tests). */
    object None : Navigator {
        override fun push(screenId: String, params: Map<String, Any?>, transition: dev.app8.engine.core.model.ScreenTransition?, zoom: dev.app8.engine.navigation.transitions.ZoomOrigin?) = Unit
        override fun pop(toRoot: Boolean) = Unit
        override fun present(screenId: String, params: Map<String, Any?>, presentation: Action.PresentationStyle, detents: List<Action.SheetDetent>?, grabber: Boolean?, transition: dev.app8.engine.core.model.ScreenTransition?, zoom: dev.app8.engine.navigation.transitions.ZoomOrigin?) = Unit
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
    /** `requestNotificationPermission`: the system prompt once, else the stored answer. */
    fun requestNotificationPermission(onResult: (Boolean) -> Unit)
    /** `capturePhoto` (photos.md): the chooser, camera or library; answers once. */
    fun capturePhoto(request: PhotoCaptureRequest, onResult: (PhotoPick) -> Unit) {
        onResult(PhotoPick.Error("unavailable", "no photo UI"))
    }
}

/** What `capturePhoto` asks the host for. */
internal data class PhotoCaptureRequest(
    val source: Source = Source.cameraOrLibrary,
    val front: Boolean = false,
    val cameraLabel: String = "Take Photo",
    val libraryLabel: String = "Choose from Library",
    val cancelLabel: String = "Cancel",
) {
    @Suppress("EnumEntryName")
    enum class Source { camera, library, cameraOrLibrary }
}

/** The host's answer: an image to store (its [uri] readable once), a cancel, or an error code. */
internal sealed interface PhotoPick {
    data class Image(val uri: android.net.Uri, val source: String, val cleanup: () -> Unit = {}) : PhotoPick
    data object Cancel : PhotoPick
    data class Error(val code: String, val message: String) : PhotoPick
}

internal data class AlertRequest(
    val title: String?,
    val message: String?,
    val buttons: List<Button>,
) {
    data class Button(val title: String, val style: Action.AlertActionStyle, val onClick: () -> Unit)
}
