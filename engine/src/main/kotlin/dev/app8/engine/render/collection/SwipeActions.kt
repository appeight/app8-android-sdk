package dev.app8.engine.render.collection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app8.engine.core.model.CollectionProperties
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.icons.MaterialSymbols
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.style.IosSystemColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val BUTTON_WIDTH_DP = 74f

/**
 * Leading / trailing swipe actions on a vertical collection row (iOS
 * `UISwipeActionsConfiguration` on a list layout): the row slides to reveal its action
 * buttons; a full swipe runs the first action when `allowFullSwipe` (default true).
 */
@Composable
internal fun SwipeActionsBox(
    swipe: CollectionProperties.SwipeActions,
    modifier: Modifier,
    onAction: (CollectionProperties.SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val leading = swipe.leading.orEmpty()
    val trailing = swipe.trailing.orEmpty()
    val density = LocalDensity.current.density
    val button = BUTTON_WIDTH_DP * density
    val maxLead = leading.size * button
    val maxTrail = trailing.size * button
    val fullSwipe = swipe.allowFullSwipe ?: true
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var rowWidth by remember { mutableIntStateOf(0) }
    val limitLead = if (fullSwipe && leading.isNotEmpty()) rowWidth.toFloat() else maxLead
    val limitTrail = if (fullSwipe && trailing.isNotEmpty()) rowWidth.toFloat() else maxTrail

    fun settle(target: Float) = scope.launch { offset.animateTo(target, spring(stiffness = 600f)) }
    fun run(action: CollectionProperties.SwipeAction) {
        onAction(action)
        settle(0f)
    }

    val drag = rememberDraggableState { delta ->
        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-limitTrail, limitLead)) }
    }
    Box(
        modifier
            .onSizeChanged { rowWidth = it.width }
            .draggable(
                drag,
                Orientation.Horizontal,
                enabled = leading.isNotEmpty() || trailing.isNotEmpty(),
                onDragStopped = { velocity ->
                    val x = offset.value
                    when {
                        fullSwipe && x > 0 && x > rowWidth * 0.6f -> run(leading.first())
                        fullSwipe && x < 0 && -x > rowWidth * 0.6f -> run(trailing.first())
                        x > 0 -> settle(if (x > maxLead / 2 || velocity > 1000f) maxLead else 0f)
                        x < 0 -> settle(if (-x > maxTrail / 2 || velocity < -1000f) -maxTrail else 0f)
                    }
                },
            ),
    ) {
        if (offset.value != 0f) {
            Row(Modifier.matchParentSize(), horizontalArrangement = if (offset.value > 0) Arrangement.Start else Arrangement.End) {
                val actions = if (offset.value > 0) leading else trailing.reversed()
                val each = (abs(offset.value) / actions.size.coerceAtLeast(1) / density).dp
                for (a in actions) ActionButton(a, Modifier.width(each).fillMaxHeight()) { run(a) }
            }
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

@Composable
private fun ActionButton(action: CollectionProperties.SwipeAction, modifier: Modifier, onClick: () -> Unit) {
    val env = LocalRenderEnv.current
    val bg = action.backgroundColor?.let { Color(ColorHex.parseOrFallback(it).argb) }
        ?: if (action.style == CollectionProperties.SwipeAction.Style.destructive) IosSystemColors.of("systemRed", env.appearance)
        else IosSystemColors.of("systemGray", env.appearance)
    Box(modifier.background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            action.systemImage?.let { name ->
                val glyph = MaterialSymbols.resolve(name, MaterialSymbols.load(LocalContext.current))
                if (glyph != null) {
                    BasicText(glyph.name, style = TextStyle(color = Color.White, fontSize = 22.sp, fontFamily = MaterialSymbols.family(glyph.filled, 400, 22f)), maxLines = 1)
                }
            }
            action.title?.let { BasicText(it, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1) }
        }
    }
}
