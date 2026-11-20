package dev.app8.engine.navigation

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.app8.engine.core.model.DslDecoder
import dev.app8.engine.core.vars.ScopedVariableStore
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.collection.Cell
import dev.app8.engine.render.runtime
import dev.app8.engine.render.session
import dev.app8.engine.style.App8Appearance
import dev.app8.engine.style.App8FontProvider
import dev.app8.engine.style.TextSizer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `barStyle.component`: the template lays out in the bar like iOS `StyledTabBarView`. */
class ComponentTabBarLayoutTest {
    private val rt = runtime(mapOf("s" to """{"type":"screen","id":"s","content":{}}"""))
    private val session = session(rt)
    private val env = Density(3f).let { d ->
        RenderEnv(rt, App8Appearance.Light, d, TextMeasurer(createFontFamilyResolver(android.content.ContextWrapper(null)), d, LayoutDirection.Ltr), App8FontProvider.System, TextSizer.FollowSystem)
    }

    private fun cell(json: String) = Cell.create(session.root, DslDecoder().decodeComponent(json), ScopedVariableStore(session.root.scope.store), null, null)!!

    @Test
    fun `a template inset from the bar keeps its insets, without conflicts`() {
        val bar = cell(
            """{"type":"stackView","id":"Bar","content":{"properties":{"axis":"horizontal","distribution":"fillEqually"},
              "layout":{"constraints":[{"type":"top","target":"superview"},{"type":"leading","target":"superview","constant":12},
                {"type":"trailing","target":"superview","constant":-12},{"type":"bottom","target":"superview"}]},
              "children":[{"type":"view","id":"a","content":{}},{"type":"view","id":"b","content":{}}]}}""",
        )
        assertFalse(pinsToBar(bar))
        val frame = bar.layout(env, width = 400.0, height = 56.0, edges = pinsToBar(bar)).value.absolute.getValue(bar.root.key)
        assertEquals(12.0, frame.x)
        assertEquals(376.0, frame.width)
        assertEquals(56.0, frame.height)
        assertTrue(rt.diagnostics.snapshot().none { it.startsWith("layout.conflict") }, rt.diagnostics.snapshot().toString())
    }

    @Test
    fun `a template without a layout fills the bar`() {
        val bar = cell("""{"type":"view","id":"Plain","content":{}}""")
        assertTrue(pinsToBar(bar))
        val frame = bar.layout(env, width = 400.0, height = 56.0, edges = pinsToBar(bar)).value.absolute.getValue(bar.root.key)
        assertEquals(0.0, frame.x)
        assertEquals(400.0, frame.width)
    }
}
