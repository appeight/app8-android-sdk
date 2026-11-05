package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.ContentMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

// Port of NewComponentModelTests.swift. Skipped: `videoGravity` (AVFoundation mapping) is
// replaced by asserting the decoded `contentMode` it derives from.

private fun componentJSON(type: String, properties: String, style: String = "{}"): String = """
    {
        "type": "$type",
        "id": "test-$type",
        "content": {
            "properties": $properties,
            "style": $style,
            "layout": {}
        }
    }
"""

@Suppress("UNCHECKED_CAST")
private fun <P : Any, S : Any> decode(json: String): ComponentContent<P, S> {
    val component = DslDecoder().decodeComponent(json)
    val entity = assertIs<ComponentEntity<*>>(component)
    return entity.content as ComponentContent<P, S>
}

private fun <P : Any> props(type: String, properties: String): P =
    decode<P, Any>(componentJSON(type, properties)).properties

class NewComponentModelTest {

    // ActivityIndicator

    @Test
    fun activityIndicatorDecodesAllProperties() {
        val p = props<ActivityIndicatorProperties>("activityIndicator", """{ "isAnimating": "{{isLoading}}", "hidesWhenStopped": false }""")
        assertEquals("{{isLoading}}", p.isAnimating)
        assertEquals(false, p.hidesWhenStopped)
    }

    @Test
    fun activityIndicatorDecodesWithDefaults() {
        val p = props<ActivityIndicatorProperties>("activityIndicator", "{}")
        assertNull(p.isAnimating)
        assertNull(p.hidesWhenStopped)
    }

    @Test
    fun activityIndicatorDecodesLiteralTrue() {
        assertEquals("true", props<ActivityIndicatorProperties>("activityIndicator", """{ "isAnimating": "true" }""").isAnimating)
    }

    // Toggle

    @Test
    fun toggleDecodesAllProperties() {
        val p = props<ToggleProperties>("toggle", """{ "isOn": "{{darkMode}}", "bindVariable": "darkMode", "isEnabled": "{{canToggle}}" }""")
        assertEquals("{{darkMode}}", p.isOn)
        assertEquals("darkMode", p.bindVariable)
        assertEquals("{{canToggle}}", p.isEnabled)
    }

    @Test
    fun toggleDecodesMinimal() {
        assertEquals(ToggleProperties(), props<ToggleProperties>("toggle", "{}"))
    }

    @Test
    fun toggleDecodesLiteralIsOn() {
        assertEquals("true", props<ToggleProperties>("toggle", """{ "isOn": "true" }""").isOn)
    }

    // Slider

    @Test
    fun sliderDecodesAllProperties() {
        val p = props<SliderProperties>(
            "slider",
            """{ "value": "{{volume}}", "minimumValue": 0, "maximumValue": 100, "step": 5, "bindVariable": "volume", "isEnabled": "true" }""",
        )
        assertEquals("{{volume}}", p.value)
        assertEquals(0f, p.minimumValue)
        assertEquals(100f, p.maximumValue)
        assertEquals(5f, p.step)
        assertEquals("volume", p.bindVariable)
        assertEquals("true", p.isEnabled)
    }

    @Test
    fun sliderDecodesDefaults() {
        val p = props<SliderProperties>("slider", "{}")
        assertNull(p.value)
        assertNull(p.minimumValue)
        assertNull(p.maximumValue)
        assertNull(p.step)
    }

    @Test
    fun sliderDecodesFloatRange() {
        val p = props<SliderProperties>("slider", """{ "minimumValue": 0.0, "maximumValue": 1.0, "step": 0.1 }""")
        assertEquals(0f, p.minimumValue)
        assertEquals(1f, p.maximumValue)
        assertEquals(0.1f, p.step)
    }

    // PageControl

    @Test
    fun pageControlDecodesAllProperties() {
        val p = props<PageControlProperties>(
            "pageControl",
            """{ "numberOfPages": "{{totalPhotos}}", "currentPage": "{{currentIndex}}", "bindVariable": "currentIndex", "hidesForSinglePage": false }""",
        )
        assertEquals("{{totalPhotos}}", p.numberOfPages)
        assertEquals("{{currentIndex}}", p.currentPage)
        assertEquals("currentIndex", p.bindVariable)
        assertEquals(false, p.hidesForSinglePage)
    }

    @Test
    fun pageControlDecodesLiteralNumbers() {
        val p = props<PageControlProperties>("pageControl", """{ "numberOfPages": "5", "currentPage": "0" }""")
        assertEquals("5", p.numberOfPages)
        assertEquals("0", p.currentPage)
    }

    // Picker

    @Test
    fun pickerDecodesMenuMode() {
        val p = props<PickerProperties>(
            "picker",
            """
            {
                "options": [
                    { "value": "red", "label": "Red" },
                    { "value": "blue", "label": "Blue", "icon": "circle.fill" }
                ],
                "selectedValue": "{{color}}",
                "bindVariable": "color",
                "displayMode": "menu",
                "placeholder": "Choose color"
            }
            """,
        )
        assertEquals(2, p.options?.size)
        assertEquals("red", p.options!![0].value)
        assertEquals("Red", p.options!![0].label)
        assertNull(p.options!![0].icon)
        assertEquals("circle.fill", p.options!![1].icon)
        assertEquals("{{color}}", p.selectedValue)
        assertEquals("color", p.bindVariable)
        assertEquals(PickerProperties.DisplayMode.menu, p.displayMode)
        assertEquals("Choose color", p.placeholder)
    }

    @Test
    fun pickerDecodesSegmentedMode() {
        val p = props<PickerProperties>(
            "picker",
            """
            {
                "options": [{ "value": "s", "label": "S" }, { "value": "m", "label": "M" }, { "value": "l", "label": "L" }],
                "selectedValue": "m",
                "displayMode": "segmented"
            }
            """,
        )
        assertEquals(3, p.options?.size)
        assertEquals(PickerProperties.DisplayMode.segmented, p.displayMode)
        assertEquals("m", p.selectedValue)
    }

    @Test
    fun pickerDefaultsToNoMode() {
        assertNull(props<PickerProperties>("picker", """{ "options": [{ "value": "a", "label": "A" }] }""").displayMode)
    }

    // Shimmer

    @Test
    fun shimmerDecodesAllProperties() {
        val p = props<ShimmerProperties>("shimmer", """{ "isAnimating": "{{isLoading}}", "duration": 2.0, "direction": "rightToLeft" }""")
        assertEquals("{{isLoading}}", p.isAnimating)
        assertEquals(2.0, p.duration)
        assertEquals(ShimmerProperties.Direction.rightToLeft, p.direction)
    }

    @Test
    fun shimmerDecodesDefaults() {
        assertEquals(ShimmerProperties(), props<ShimmerProperties>("shimmer", "{}"))
    }

    @Test
    fun shimmerDecodesAllDirections() {
        for (dir in listOf("leftToRight", "rightToLeft", "topToBottom")) {
            assertEquals(dir, props<ShimmerProperties>("shimmer", """{ "direction": "$dir" }""").direction?.name)
        }
    }

    // Polyline (Shape)

    @Test
    fun shapePolylineDecodesPoints() {
        val p = props<ShapeProperties>(
            "shape",
            """
            {
                "kind": "polyline", "lineWidth": 2, "strokeColor": "#34C759", "smooth": true, "closed": false,
                "points": [ { "x": "0", "y": "0.8" }, { "x": "0.5", "y": "0.3" }, { "x": "1.0", "y": "0.2" } ]
            }
            """,
        )
        assertEquals(ShapeProperties.Kind.polyline, p.kind)
        assertEquals(2.0, p.lineWidth)
        assertEquals("#34C759", p.strokeColor)
        assertEquals(true, p.smooth)
        assertEquals(false, p.closed)
        assertEquals(3, p.points?.size)
        assertEquals(ShapeProperties.Point("0", "0.8"), p.points!![0])
        assertEquals(ShapeProperties.Point("1.0", "0.2"), p.points!![2])
    }

    @Test
    fun shapePolylineDecodesWithExpressionPoints() {
        val p = props<ShapeProperties>(
            "shape",
            """{ "kind": "polyline", "points": [ { "x": "{{p1x}}", "y": "{{p1y}}" }, { "x": "{{p2x}}", "y": "{{p2y}}" } ] }""",
        )
        assertEquals(ShapeProperties.Point("{{p1x}}", "{{p1y}}"), p.points!![0])
    }

    @Test
    fun shapePolylineDefaultsNilForNonPolyline() {
        val p = props<ShapeProperties>("shape", """{ "kind": "arc", "progress": "0.5" }""")
        assertEquals(ShapeProperties.Kind.arc, p.kind)
        assertNull(p.points)
        assertNull(p.smooth)
        assertNull(p.closed)
    }

    // Video

    @Test
    fun videoDecodesLocalAsset() {
        val p = props<VideoProperties>("video", """{ "type": "localAsset", "name": "intro.mp4" }""")
        assertEquals(VideoProperties.Model.LocalAsset("intro.mp4"), p.model)
        assertEquals(true, p.autoplay)
        assertEquals(true, p.loop)
        assertEquals(true, p.muted)
        assertEquals(VideoProperties.AudioMix.auto, p.audioMix)
    }

    @Test
    fun videoDecodesFlagOverrides() {
        val p = props<VideoProperties>("video", """{ "type": "localAsset", "name": "clip", "autoplay": false, "loop": false, "muted": false }""")
        assertEquals(false, p.autoplay)
        assertEquals(false, p.loop)
        assertEquals(false, p.muted)
    }

    @Test
    fun videoDecodesAudioMix() {
        val cases = listOf(
            "mix" to VideoProperties.AudioMix.mix,
            "duck" to VideoProperties.AudioMix.duck,
            "interrupt" to VideoProperties.AudioMix.interrupt,
            "auto" to VideoProperties.AudioMix.auto,
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, props<VideoProperties>("video", """{ "type": "localAsset", "name": "clip", "audioMix": "$raw" }""").audioMix)
        }
    }

    @Test
    fun videoDecodesNoneType() {
        assertEquals(VideoProperties.Model.None, props<VideoProperties>("video", """{ "type": "none" }""").model)
    }

    @Test
    fun videoDecodesGravityStyle() {
        val content = decode<VideoProperties, dev.app8.engine.core.model.style.VideoStyle>(
            componentJSON("video", """{ "type": "localAsset", "name": "intro" }""", """{ "contentMode": "scaleAspectFit" }"""),
        )
        assertEquals(ContentMode.scaleAspectFit, content.style?.contentMode)
    }

    @Test
    fun videoEndBehaviorDefaultsToFreeze() {
        val p = props<VideoProperties>("video", """{ "type": "localAsset", "name": "intro" }""")
        assertEquals(VideoProperties.EndBehavior.freezeLastFrame, p.endBehavior)
        assertNull(p.poster)
        assertNull(p.endPoster)
        assertNull(p.marks)
        assertNull(p.startDelay)
        assertNull(p.startTime)
        assertNull(p.rate)
        assertEquals(true, p.loops)
    }

    @Test
    fun videoDecodesPosterTimingAndMarks() {
        val p = props<VideoProperties>(
            "video",
            """
            {
                "type": "localAsset", "name": "intro", "loop": false,
                "endBehavior": "showPoster",
                "poster": { "type": "remoteAsset", "id": "p1", "name": "poster.png" },
                "endPoster": { "type": "url", "url": "https://cdn.example/end.png" },
                "startDelay": 1.5, "startTime": 2, "rate": 0.5,
                "marks": [ { "id": "m1", "time": 0.35 }, { "id": "m2", "time": 0.57 } ]
            }
            """,
        )
        assertEquals(VideoProperties.EndBehavior.showPoster, p.endBehavior)
        assertEquals(false, p.loops)
        assertEquals(VideoProperties.PosterSource.Kind.remoteAsset, p.poster?.kind)
        assertEquals("p1", p.poster?.id)
        assertEquals("poster.png", p.poster?.name)
        assertEquals(VideoProperties.PosterSource.Kind.url, p.endPoster?.kind)
        assertEquals("https://cdn.example/end.png", p.endPoster?.url)
        assertEquals(1.5, p.startDelay)
        assertEquals(2.0, p.startTime)
        assertEquals(0.5f, p.rate)
        assertEquals(2, p.marks?.size)
        assertEquals("m1", p.marks!!.first().id)
        assertEquals(0.35, p.marks!!.first().time)
    }

    @Test
    fun videoEndBehaviorLoopImpliesLooping() {
        val p = props<VideoProperties>("video", """{ "type": "localAsset", "name": "intro", "loop": false, "endBehavior": "loop" }""")
        assertEquals(VideoProperties.EndBehavior.loop, p.endBehavior)
        assertEquals(true, p.loops)
    }

    @Test
    fun videoPosterRemoteAssetHelper() {
        assertEquals("p1", VideoProperties.PosterSource(VideoProperties.PosterSource.Kind.remoteAsset, name = "p.png", id = "p1").remoteAsset?.id)
        assertEquals("https://x/y.png", VideoProperties.PosterSource(VideoProperties.PosterSource.Kind.url, url = "https://x/y.png").remoteAsset?.url)
        assertNull(VideoProperties.PosterSource(VideoProperties.PosterSource.Kind.firstFrame).remoteAsset)
        assertNull(VideoProperties.PosterSource(VideoProperties.PosterSource.Kind.localAsset, name = "bundled").remoteAsset)
    }

    @Test
    fun videoDecodesPlaybackActionsAndAnalytics() {
        val content = decode<VideoProperties, Any>(
            """
            {
                "type": "video", "id": "test-video",
                "content": {
                    "properties": { "type": "localAsset", "name": "intro", "loop": false },
                    "style": {}, "layout": {},
                    "actions": {
                        "onVideoComplete": [ { "type": "emit", "name": "intro.finished" } ],
                        "onTimeMark": [ { "type": "updateVariable", "variableName": "step", "value": 1 } ]
                    },
                    "analytics": { "onVideoComplete": "introDone" }
                }
            }
            """,
        )
        assertEquals(Action.Type.emit, content.actions?.get(ActionTrigger.onVideoComplete)?.first()?.type)
        assertEquals("intro.finished", content.actions?.get(ActionTrigger.onVideoComplete)?.first()?.name)
        assertEquals(Action.Type.updateVariable, content.actions?.get(ActionTrigger.onTimeMark)?.first()?.type)
        assertEquals("introDone", content.analytics?.get(ActionTrigger.onVideoComplete)?.name)
    }

    // DatePicker

    @Test
    fun datePickerDecodesAllProperties() {
        val p = props<DatePickerProperties>(
            "datePicker",
            """
            {
                "selectedDate": "{{birthDate}}", "bindVariable": "birthDate", "datePickerMode": "date",
                "displayStyle": "inline", "minimumDate": "1900-01-01", "maximumDate": "2026-04-04", "isEnabled": "true"
            }
            """,
        )
        assertEquals("{{birthDate}}", p.selectedDate)
        assertEquals("birthDate", p.bindVariable)
        assertEquals(DatePickerProperties.Mode.date, p.datePickerMode)
        assertEquals(DatePickerProperties.DisplayStyle.inline, p.displayStyle)
        assertEquals("1900-01-01", p.minimumDate)
        assertEquals("2026-04-04", p.maximumDate)
        assertEquals("true", p.isEnabled)
    }

    @Test
    fun datePickerDecodesDefaults() {
        assertEquals(DatePickerProperties(), props<DatePickerProperties>("datePicker", "{}"))
    }

    @Test
    fun datePickerDecodesAllModes() {
        for (mode in listOf("date", "time", "dateAndTime", "countdownTimer")) {
            assertEquals(mode, props<DatePickerProperties>("datePicker", """{ "datePickerMode": "$mode" }""").datePickerMode?.name)
        }
    }

    @Test
    fun datePickerDecodesAllStyles() {
        for (style in listOf("compact", "inline", "wheels")) {
            assertEquals(style, props<DatePickerProperties>("datePicker", """{ "displayStyle": "$style" }""").displayStyle?.name)
        }
    }

    @Test
    fun datePickerDecodesCompactWithBinding() {
        val p = props<DatePickerProperties>("datePicker", """{ "bindVariable": "eventDate", "displayStyle": "compact" }""")
        assertEquals("eventDate", p.bindVariable)
        assertEquals(DatePickerProperties.DisplayStyle.compact, p.displayStyle)
    }
}
