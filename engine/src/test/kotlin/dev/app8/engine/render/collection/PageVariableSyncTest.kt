package dev.app8.engine.render.collection

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pagers bound to one `currentPageVariable`, as in the Facetheory demo: the product gallery
 * and a product screen further back with fewer images both bind `galleryPage`.
 */
class PageVariableSyncTest {
    /** One store variable; a write notifies every pager later, as snapshotFlow does in Compose. */
    private class World {
        var value: Int? = null
        var writes = 0
        val pagers = mutableListOf<Pager>()
        private val queue = ArrayDeque<() -> Unit>()

        fun write(p: Int) {
            value = p
            writes += 1
            for (pager in pagers) queue.addLast { value?.let { pager.variableChanged(it) } }
        }

        /** Runs queued notifications; a fight between pagers never settles. */
        fun settle() {
            var steps = 0
            while (queue.isNotEmpty()) {
                check(++steps < 1_000) { "pagers keep rewriting the variable" }
                queue.removeFirst()()
            }
        }
    }

    private class Pager(private val world: World, val count: Int) {
        var shown = (world.value ?: 0).coerceIn(0, count - 1)
        val sync = PageVariableSync(read = { world.value }, write = world::write)

        init {
            world.pagers += this
            sync.report(shown)
        }

        /** The user swipes to [p]. */
        fun swipe(p: Int) {
            shown = p
            sync.report(p)
        }

        /** The variable changed: an animated scroll through the pages in between. */
        fun variableChanged(target: Int) {
            val t = sync.follow(target, count) ?: return
            val token = sync.beginScroll()
            val step = if (t > shown) 1 else -1
            while (shown != t) {
                shown += step
                sync.report(shown)
            }
            sync.endScroll(token)
        }
    }

    @Test
    fun aShortPagerDoesNotDragALongerOneBackToItsLastPage() {
        val world = World().apply { value = 0 }
        val gallery = Pager(world, count = 5)
        val behind = Pager(world, count = 2)
        for (p in 1..4) {
            gallery.swipe(p)
            world.settle()
            assertEquals(p, world.value)
            assertEquals(p, gallery.shown)
            assertEquals(minOf(p, 1), behind.shown)
        }
        assertEquals(4, world.writes) // one write per swipe, none echoed back
        for (p in 3 downTo 0) {
            gallery.swipe(p)
            world.settle()
            assertEquals(p, world.value)
            assertEquals(minOf(p, 1), behind.shown)
        }
    }

    @Test
    fun theShortPagerStillDrivesTheLongOneWhenSwipedItself() {
        val world = World().apply { value = 3 }
        val gallery = Pager(world, count = 5)
        val behind = Pager(world, count = 2)
        assertEquals(3, world.value) // the clamped short pager does not overwrite on first report
        assertEquals(1, behind.shown)
        behind.swipe(0)
        world.settle()
        assertEquals(0, world.value)
        assertEquals(0, gallery.shown)
    }

    @Test
    fun firstReportOnlyRecords() {
        var writes = 0
        val sync = PageVariableSync(read = { 4 }, write = { writes += 1 })
        sync.report(1)
        assertEquals(0, writes)
        assertEquals(1, sync.page)
    }

    @Test
    fun writesOnlyWhenTheVariableDiffers() {
        var value: Int? = 2
        val written = mutableListOf<Int>()
        val sync = PageVariableSync(read = { value }, write = { written += it; value = it })
        sync.report(0)
        sync.report(2) // the variable already holds 2
        sync.report(3)
        assertEquals(listOf(3), written)
    }

    @Test
    fun followClampsToTheLastPageAndSkipsThePageShown() {
        val sync = PageVariableSync(read = { null }, write = {})
        sync.report(1)
        assertEquals(2, sync.follow(7, pageCount = 3))
        assertEquals(2, sync.page)
        assertNull(sync.follow(9, pageCount = 3))
        assertNull(sync.follow(0, pageCount = 0))
    }

    @Test
    fun theMuteOfAVariableDrivenScrollExpiresAndIsTokenGuarded() {
        var clock = 0L
        val written = mutableListOf<Int>()
        val sync = PageVariableSync(read = { null }, write = { written += it }, now = { clock })
        sync.report(0)
        val first = sync.beginScroll()
        sync.report(1)
        val second = sync.beginScroll()
        sync.endScroll(first) // a stale end does not unmute the newer scroll
        sync.report(2)
        assertTrue(written.isEmpty())
        clock += 1_001 // the end of the second scroll was never seen
        sync.report(3)
        assertEquals(listOf(3), written)
        sync.endScroll(second)
        sync.report(4)
        assertEquals(listOf(3, 4), written)
    }
}
