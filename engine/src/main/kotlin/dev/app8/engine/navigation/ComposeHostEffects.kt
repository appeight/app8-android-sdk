package dev.app8.engine.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import dev.app8.engine.actions.AlertRequest
import dev.app8.engine.actions.HostEffects
import dev.app8.engine.core.model.Action
import dev.app8.engine.runtime.Diagnostics

/** [HostEffects] on Android: Custom Tabs / ACTION_VIEW, view haptics, a Material 3 alert. */
internal class ComposeHostEffects(
    private val context: Context,
    private val view: View,
    private val diagnostics: Diagnostics,
) : HostEffects {
    val alert: MutableState<AlertRequest?> = mutableStateOf(null)

    override fun openUrl(url: String, presentation: Action.URLPresentation) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val web = uri.scheme == "http" || uri.scheme == "https"
        try {
            if (web && presentation != Action.URLPresentation.external) {
                CustomTabsIntent.Builder().build().launchUrl(context, uri)
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            diagnostics.warn("openURL", "no app can open $url")
        }
    }

    /** Nearest `HapticFeedbackConstants` to each `UIFeedbackGenerator` style. */
    override fun haptic(style: Action.HapticStyle) {
        val constant = when (style) {
            Action.HapticStyle.light -> HapticFeedbackConstants.CLOCK_TICK
            Action.HapticStyle.medium -> HapticFeedbackConstants.VIRTUAL_KEY
            Action.HapticStyle.heavy -> HapticFeedbackConstants.LONG_PRESS
            Action.HapticStyle.success -> HapticFeedbackConstants.CONFIRM
            Action.HapticStyle.warning -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            Action.HapticStyle.error -> HapticFeedbackConstants.REJECT
            Action.HapticStyle.selection -> HapticFeedbackConstants.SEGMENT_TICK
        }
        view.performHapticFeedback(constant)
    }

    override fun alert(request: AlertRequest) {
        alert.value = request
    }
}

/** `showAlert` as a Material 3 AlertDialog. Cancel / default buttons dismiss; destructive is red. */
@Composable
internal fun AlertHost(effects: ComposeHostEffects) {
    val request by effects.alert
    val r = request ?: return
    val confirm = r.buttons.lastOrNull { it.style != Action.AlertActionStyle.cancel } ?: r.buttons.last()
    val dismiss = r.buttons.filter { it !== confirm }
    fun press(b: AlertRequest.Button) {
        effects.alert.value = null
        b.onClick()
    }
    AlertDialog(
        onDismissRequest = { effects.alert.value = null },
        title = r.title?.let { { Text(it) } },
        text = r.message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = { press(confirm) }) {
                Text(confirm.title, color = if (confirm.style == Action.AlertActionStyle.destructive) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = if (dismiss.isEmpty()) null else ({
            dismiss.forEach { b ->
                TextButton(onClick = { press(b) }) {
                    Text(b.title, color = if (b.style == Action.AlertActionStyle.destructive) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary)
                }
            }
        }),
    )
}
