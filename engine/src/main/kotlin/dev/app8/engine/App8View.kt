package dev.app8.engine

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * View-system host for hosts built without Compose: shows an [App8Instance] app, flow or
 * screen in a [ComposeView]. Set [instance] (and optionally [screenId]) after inflation.
 */
public class App8View @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var instanceState by mutableStateOf<App8Instance?>(null)
    private var screenState by mutableStateOf<String?>(null)
    private var paramsState by mutableStateOf<Map<String, Any?>>(emptyMap())

    /** Called with load errors (including [App8Error.UnsupportedPlatform]). */
    public var onError: (App8Error) -> Unit = {}

    public var instance: App8Instance?
        get() = instanceState
        set(value) {
            instanceState = value
        }

    /** Null shows the whole app; otherwise this screen. */
    public var screenId: String?
        get() = screenState
        set(value) {
            screenState = value
        }

    public fun setScreen(screenId: String, params: Map<String, Any?> = emptyMap()) {
        paramsState = params
        screenState = screenId
    }

    private val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val instance = instanceState ?: return@setContent
            val screen = screenState
            if (screen == null) instance.App(onError = { onError(it) }) else instance.Screen(screen, paramsState, onError = { onError(it) })
        }
    }

    init {
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
