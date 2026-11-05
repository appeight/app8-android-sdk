package dev.app8.engine.core.layout

import dev.app8.engine.core.layout.LayoutAttribute.BOTTOM
import dev.app8.engine.core.layout.LayoutAttribute.TOP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutConflictTest {
    @Test
    fun satisfiableLayoutReportsNoConflict() {
        val a = LayoutNode("a", layout = Layout(height = fixed(100), constraints = listOf(c(TOP, "superview"))))
        assertTrue(solveLayout(screen(a), PHONE, SAFE).conflicts.isEmpty())
    }

    @Test
    fun brokenRequiredConstraintNamesTheViews() {
        // Fixed height 100 between edges 10pt apart: one required constraint must break.
        val a = LayoutNode(
            "a",
            layout = Layout(
                height = fixed(100),
                constraints = listOf(c(TOP, "superview", constant = 10), c(BOTTOM, "superview", constant = -(874 - 20))),
            ),
        )
        val r = solveLayout(screen(a), PHONE, SAFE)
        assertEquals(1, r.conflicts.size, r.conflicts.toString())
        assertTrue("a" in r.conflicts.single().views, r.conflicts.toString())
        assertTrue(r.conflicts.single().constraint.contains("a."), r.conflicts.toString())
    }
}
