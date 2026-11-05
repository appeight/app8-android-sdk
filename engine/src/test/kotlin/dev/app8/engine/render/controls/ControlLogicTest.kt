package dev.app8.engine.render.controls

import dev.app8.engine.maps.App8Coordinate
import org.junit.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlLogicTest {
    @Test
    fun masksFillDigitSlotsAndStopWhenDigitsRunOut() {
        assertEquals("(555) 123-4567", TextInputValidation.formatWithMask("5551234567", "(###) ###-####"))
        assertEquals("(555) 1", TextInputValidation.formatWithMask("(555) 1", "(###) ###-####"))
        assertEquals("", TextInputValidation.formatWithMask("abc", "##"))
        assertEquals("5551234567", TextInputValidation.extractDigits("(555) 123-4567"))
    }

    @Test
    fun inputFilterChecksMaxLengthAndPattern() {
        assertTrue(TextInputValidation.isInputAllowed("a", 0, "abc", 4, null))
        assertFalse(TextInputValidation.isInputAllowed("ab", 0, "abc", 4, null))
        assertTrue(TextInputValidation.isInputAllowed("xy", 1, "abc", 4, null)) // replaces one character
        assertTrue(TextInputValidation.isInputAllowed("7", 0, "", null, "[0-9]"))
        assertFalse(TextInputValidation.isInputAllowed("x", 0, "", null, "^[0-9]+$"))
        assertTrue(TextInputValidation.isInputAllowed("", 1, "a", null, "^[0-9]+$")) // deletion
        assertTrue(TextInputValidation.isInputAllowed("x", 0, "", null, "[")) // invalid regex allows
    }

    @Test
    fun editFindsTheReplacedRange() {
        assertEquals("d" to 0, TextInputValidation.edit("abc", "abcd"))
        assertEquals("" to 1, TextInputValidation.edit("abc", "ac"))
        assertEquals("XY" to 1, TextInputValidation.edit("abc", "aXYc"))
        assertEquals("hello" to 0, TextInputValidation.edit("", "hello"))
        assertEquals("a" to 0, TextInputValidation.edit("aa", "aaa"))
    }

    @Test
    fun sliderSnapsToStepsWithinRange() {
        assertEquals(50f, SliderMath.snap(49.6f, 0f, 100f, 1f))
        assertEquals(0.25f, SliderMath.snap(0.3f, 0f, 1f, 0.25f))
        assertEquals(100f, SliderMath.snap(140f, 0f, 100f, 10f))
        assertEquals(0.37f, SliderMath.snap(0.37f, 0f, 1f, null))
        assertEquals(3, SliderMath.steps(0f, 1f, 0.25f))
        assertEquals(0, SliderMath.steps(0f, 100f, 1f)) // fine steps: no tick marks
        assertEquals(0, SliderMath.steps(0f, 1f, null))
    }

    @Test
    fun datesParseIsoAndShortAndFormatShort() {
        val utc = ZoneId.of("UTC")
        val short = DateValues.parse("2026-04-04", utc)
        assertEquals(1775260800000L, short)
        assertEquals(1775305800000L, DateValues.parse("2026-04-04T12:30:00Z", utc))
        assertEquals(1775305800000L, DateValues.parse("2026-04-04T14:30:00+02:00", utc))
        assertNull(DateValues.parse("tomorrow", utc))
        assertNull(DateValues.parse("", utc))
        assertEquals("2026-04-04", DateValues.format(1775305800000L, utc))
        assertFalse(DateValues.differs(1000, 1500))
        assertTrue(DateValues.differs(0, 86_400_000))
    }

    @Test
    fun marksFireOnceForwardAndReArmOnLoop() {
        val t = VideoMarkTracker(listOf("q" to 1.25, "h" to 2.5))
        assertEquals(emptyList(), t.advance(0.5))
        assertEquals(listOf("q"), t.advance(1.3))
        assertEquals(emptyList(), t.advance(1.4))
        assertEquals(listOf("h"), t.advance(3.0))
        t.loop()
        assertEquals(listOf("q", "h"), t.advance(2.6))
        // A seek back re-arms only the marks behind the new position.
        assertEquals(emptyList(), t.advance(2.0).filter { it == "q" })
        assertEquals(listOf("h"), t.advance(2.7))
    }

    @Test
    fun shimmerBandSweepsAcrossTheFrame() {
        assertEquals(Triple(-1f, -0.5f, 0f), ShimmerMath.stops(0f))
        assertEquals(Triple(1f, 1.5f, 2f), ShimmerMath.stops(1f))
        assertEquals(0.5f, ShimmerMath.stops(0.5f).second)
    }

    @Test
    fun wheelPicksTheRowUnderTheBand() {
        assertEquals(3, WheelMath.centeredIndex(3, 10, 100, 10))
        assertEquals(4, WheelMath.centeredIndex(3, 60, 100, 10))
        assertEquals(9, WheelMath.centeredIndex(12, 0, 100, 10))
        assertEquals(0, WheelMath.centeredIndex(0, 0, 0, 10))
    }

    @Test
    fun focusFollowsTreeOrderAndResolvesTargets() {
        val log = mutableListOf<String>()
        val fm = FocusManager()
        fun entry(key: String, order: Int) = FocusManager.Entry(key, key.substringAfterLast('.'), order, { log += "focus:$key" }, { log += "release:$key" })
        fm.register(entry("s.form.password", 5))
        fm.register(entry("s.form.email", 2))
        fm.register(entry("s.notes", 9))
        assertEquals(listOf("s.form.email", "s.form.password", "s.notes"), fm.registeredKeys)

        fm.focusNext() // nothing focused → first
        assertEquals("s.form.email", fm.currentKey)
        fm.focusNext()
        fm.focusNext()
        assertEquals("s.notes", fm.currentKey)
        fm.focusNext() // past the last → dismiss
        assertNull(fm.currentKey)
        assertEquals("release:s.notes", log.last())

        fm.focusPrevious() // nothing focused → last
        assertEquals("s.notes", fm.currentKey)

        assertEquals("s.form.password", fm.resolve("password", "s.button"))
        assertEquals("s.form.email", fm.resolve("form.email", null))
        assertEquals("s.notes", fm.resolve("s.notes", null))
        assertNull(fm.resolve("missing", null))

        fm.didFocus("s.form.email")
        fm.didBlur("s.form.password")
        assertEquals("s.form.email", fm.currentKey)
        fm.unregister("s.form.email")
        assertNull(fm.currentKey)
    }

    @Test
    fun mapAnnotationsAcceptTheIosShapes() {
        val a = assertNotNull(MapModel.annotation(mapOf("id" to "x", "coordinate" to mapOf("latitude" to 1.0, "longitude" to 2L), "name" to "Cafe"), 0))
        assertEquals(App8Coordinate(1.0, 2.0), a.coordinate)
        assertEquals("Cafe", a.title)
        val b = assertNotNull(MapModel.annotation(mapOf("location" to mapOf("latitude" to "3.5", "longitude" to 4), "address" to "Main St", "icon" to "pin"), 7))
        assertEquals("7", b.id)
        assertEquals("Main St", b.subtitle)
        assertEquals("pin", b.image)
        assertNotNull(MapModel.annotation(mapOf("latitude" to 1, "longitude" to 1), 0))
        assertNull(MapModel.annotation(mapOf("title" to "no coordinate"), 0))
        assertEquals("selectedPin", MapModel.bindingName("{{ selectedPin }}"))
    }

    @Test
    fun mapProjectionAndFit() {
        val c = App8Coordinate(40.0, -74.0)
        val mid = assertNotNull(MapModel.project(c, c, 0.1, 0.1, 100f, 200f))
        assertEquals(50f, mid.first, 0.01f)
        assertEquals(100f, mid.second, 0.5f)
        assertNull(MapModel.project(App8Coordinate(41.0, -74.0), c, 0.1, 0.1, 100f, 200f))
        val (center, span) = assertNotNull(MapModel.fit(listOf(App8Coordinate(0.0, 0.0), App8Coordinate(1.0, 2.0))))
        assertEquals(App8Coordinate(0.5, 1.0), center)
        assertEquals(1.4, span.first, 1e-9)
        assertEquals(2.8, span.second, 1e-9)
    }
}
