package dev.app8.engine.render

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.layout.LayoutBounds

/**
 * Draws a screen's component tree, laid out by the Cassowary solver like iOS Auto Layout.
 *
 * The layout lives in one derived state: building the solver tree reads exactly the
 * variables that affect layout (text, `isHidden`, `{{…}}` dimensions, …) and the
 * container size / safe area, so it re-solves only when one of those changes (and the
 * [SolveCache] skips the solver when the result is known). Frames are read only in measure
 * blocks, so a new solve re-lays-out without recomposing. Visual properties are resolved
 * in each node's composable with the same read tracking, so a variable change recomposes
 * only the nodes that display it.
 *
 * @param safeArea the screen's safe area in points (status bar / cutout / navigation bar +
 *   top app bar or tab bar), mapped onto the solver like `safeAreaLayoutGuide`.
 */
@Composable
internal fun ScreenCanvas(session: ScreenSession, safeArea: EdgeInsets, modifier: Modifier = Modifier) {
    val env = LocalRenderEnv.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bounds = rememberUpdatedState(LayoutBounds(maxWidth.value.toDouble(), maxHeight.value.toDouble()))
        val safe = rememberUpdatedState(safeArea)
        val solved = remember(session, env) {
            derivedStateOf(structuralEqualityPolicy()) {
                try {
                    val (tree, signature) = LayoutTree.build(session.root, env, isRoot = true)
                    session.runtime.solveCache.solve(tree, signature, bounds.value, safe.value, env.density.density.toDouble())
                } catch (e: Exception) {
                    session.runtime.diagnostics.warn("layout", "screen '${session.screenId}' failed to lay out: ${e.message}")
                    Solved.EMPTY
                }
            }
        }
        CompositionLocalProvider(LocalSolved provides solved) {
            // The root fills the screen; its children are placed by ChildrenLayout.
            Layout(content = { NodeView(session.root) }) { measurables, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
                layout(w, h) { placeables.forEach { it.place(0, 0) } }
            }
        }
    }
}
