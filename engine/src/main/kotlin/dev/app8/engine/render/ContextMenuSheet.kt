package dev.app8.engine.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.Action
import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.core.model.ComponentType
import dev.app8.engine.core.model.ContextMenu
import dev.app8.engine.core.model.EntityContent
import dev.app8.engine.icons.MaterialSymbols
import dev.app8.engine.render.collection.Cell
import dev.app8.engine.render.collection.CellView
import kotlinx.coroutines.launch

/** Open / closed state of one component's context menu. */
@Stable
internal class ContextMenuState {
    var open by mutableStateOf(false)
}

/** Component types that take a `contextMenu` (app8-dsl `docs/context-menu.md`). */
private val CONTEXT_MENU_TYPES = setOf(
    ComponentType.Key.view, ComponentType.Key.stackView, ComponentType.Key.image, ComponentType.Key.button,
)

/** The node's enabled `contextMenu`, or null. */
internal fun RenderNode.activeContextMenu(content: EntityContent): ContextMenu? {
    val menu = (content as? ComponentContent<*, *>)?.contextMenu ?: return null
    if (typeKey !in CONTEXT_MENU_TYPES) {
        runtime.diagnostics.warn("contextMenu.unsupported", "contextMenu on '$id' ($type) is ignored")
        return null
    }
    return menu.takeIf { bool(it.isEnabled) ?: true }
}

/** Opens the menu with the system long-press haptic. */
@Composable
internal fun rememberOpenMenu(state: ContextMenuState): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(state, haptic) {
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            state.open = true
        }
    }
}

/**
 * A long-press detector that leaves taps alone, so a parent `clickable` (a collection cell's
 * `onItemTap`) still gets them. Only a press held past the long-press timeout is consumed.
 */
internal fun Modifier.longPressOnly(key: Any, onLongPress: () -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val timedOut = try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
            false
        } catch (e: PointerEventTimeoutCancellationException) {
            true
        }
        if (!timedOut) return@awaitEachGesture
        onLongPress()
        // Swallow the rest of this press so nothing below treats the release as a tap.
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** Accessibility: "Show actions" on long click, and each visible item as a custom action. */
internal fun Modifier.contextMenuSemantics(
    node: RenderNode,
    menu: ContextMenu,
    open: () -> Unit,
    run: (ContextMenu.Item) -> Unit,
): Modifier = semantics {
    onLongClick(label = "Show actions") { open(); true }
    customActions = menu.items
        .filter { node.bool(it.hidden) != true && node.bool(it.isEnabled) != false }
        .map { item -> CustomAccessibilityAction(node.string(item.title).orEmpty()) { run(item); true } }
}

/** Runs [actions] in [node]'s scope (collection cells: the cell's scope, with `item`). */
internal fun runMenuActions(dispatcher: dev.app8.engine.actions.ActionDispatcher, node: RenderNode, actions: List<Action>?) {
    for (a in actions.orEmpty()) dispatcher.execute(node, a)
}

/**
 * The Android form of a context menu: a Material 3 [ModalBottomSheet] with the preview on top
 * (taps run `onPreviewTap` and close the sheet) and the items as list rows with a leading
 * Material icon, title and subtitle. Choosing a row closes the sheet, then runs its actions.
 * Titles are evaluated when the menu opens, in the owning component's scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextMenuSheet(node: RenderNode, menu: ContextMenu, state: ContextMenuState) {
    if (!state.open) return
    val env = LocalRenderEnv.current
    val dispatcher = LocalDispatcher.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            state.open = false
            then()
        }
    }
    val preview = remember(node, menu) { menu.preview?.let { p -> Cell.inParentScope(node, p.component)?.let { it to p } } }
    androidx.compose.runtime.DisposableEffect(preview) { onDispose { preview?.first?.dispose() } }
    ModalBottomSheet(onDismissRequest = { state.open = false }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp),
        ) {
            if (preview != null) {
                val (cell, p) = preview
                val config = LocalConfiguration.current
                val width = p.width ?: (config.screenWidthDp - 32.0)
                val maxHeight = config.screenHeightDp * 0.7
                val solved = cell.layout(env, width, p.height)
                val height = (p.height ?: solved.value.contentHeight).coerceAtMost(maxHeight)
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Not interactive: any tap inside runs `onPreviewTap`.
                    Box(
                        Modifier.width(width.dp).height(height.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { close { runMenuActions(dispatcher, node, menu.onPreviewTap) } },
                    ) {
                        CompositionLocalProvider(LocalInteractionEnabled provides false) {
                            CellView(cell, solved, Modifier, height)
                        }
                    }
                }
            }
            for (item in menu.items) {
                if (node.bool(item.hidden) == true) continue
                val enabled = node.bool(item.isEnabled) ?: true
                val color = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                val glyph = item.icon?.let { node.string(it) }?.takeIf { it.isNotEmpty() }
                    ?.let { MaterialSymbols.resolve(context, it, env.runtime.diagnostics, node.id) }
                val subtitle = item.subtitle?.let { node.string(it) }?.takeIf { it.isNotEmpty() }
                ListItem(
                    headlineContent = { Text(node.string(item.title).orEmpty()) },
                    supportingContent = subtitle?.let { s -> { Text(s) } },
                    leadingContent = glyph?.let { g ->
                        { Text(g.text, fontFamily = MaterialSymbols.family(g.filled), fontSize = env.textSize(24f)) }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        headlineColor = color,
                        leadingIconColor = color,
                    ),
                    modifier = Modifier
                        .clickable(enabled = enabled) { close { runMenuActions(dispatcher, node, item.actions) } }
                        .alpha(if (enabled) 1f else 0.38f),
                )
            }
        }
    }
}
