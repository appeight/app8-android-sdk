package dev.app8.engine.navigation

import dev.app8.engine.core.model.Action
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DSL 0.6.1 native visibility: navigation's part (app8-dsl `components/native.md#visibility`). */
class ScreenVisibilityTest {
    private val flow = FlowController(null, "home")
    private val base get() = flow.layers.first()
    private val home get() = base.stack.entries.first()

    @Test
    fun theBaseScreenIsOnTopUntilSomethingCoversIt() {
        assertTrue(isScreenOnTop(flow, base, base.stack, home))
        flow.push(base.stack, "detail", emptyMap(), transition = null)
        assertFalse(isScreenOnTop(flow, base, base.stack, home), "a pushed screen covers it")
        val detail = base.stack.entries.last()
        assertTrue(isScreenOnTop(flow, base, base.stack, detail))
        base.stack.pop(toRoot = false)
        assertTrue(isScreenOnTop(flow, base, base.stack, home))
    }

    @Test
    fun anySheetCoversTheScreenBelowAndIsOnTopOnlyOncePresented() {
        flow.present("sheet", emptyMap(), Action.PresentationStyle.sheet, listOf(Action.SheetDetent.Medium), grabber = null)
        val sheet = flow.layers.last()
        val sheetRoot = sheet.stack.entries.single()
        // Still composed under the sheet, but not visible (at any detent).
        assertFalse(isScreenOnTop(flow, base, base.stack, home))
        // Presenting: not yet at rest.
        assertFalse(isScreenOnTop(flow, sheet, sheet.stack, sheetRoot))
        sheet.presented = true
        assertTrue(isScreenOnTop(flow, sheet, sheet.stack, sheetRoot))
        // Dismissal starts: false at once; the base comes back when the layer is gone.
        sheet.dismissing = true
        assertFalse(isScreenOnTop(flow, sheet, sheet.stack, sheetRoot))
        flow.remove(sheet)
        assertTrue(isScreenOnTop(flow, base, base.stack, home))
    }

    @Test
    fun theBaseLayerIsPresentedFromTheStart() {
        assertTrue(base.presented)
        flow.present("cover", emptyMap(), Action.PresentationStyle.fullScreen, null, null)
        assertFalse(flow.layers.last().presented)
    }
}
