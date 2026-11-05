package dev.app8.engine.render.controls

import dev.app8.engine.core.model.DatePickerProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** iOS `TextInputValidation`: input masks and per-edit filtering. Pure, for tests. */
internal object TextInputValidation {
    /** `#` is a digit slot; other mask characters are copied while digits remain. */
    fun formatWithMask(text: String, mask: String): String {
        val digits = text.filter { it.isDigit() }
        val out = StringBuilder()
        var i = 0
        for (c in mask) {
            if (i >= digits.length) break
            if (c == '#') out.append(digits[i++]) else out.append(c)
        }
        return out.toString()
    }

    fun extractDigits(text: String): String = text.filter { it.isDigit() }

    /**
     * `shouldChangeCharactersIn`: [input] replaces [removedLength] characters of [currentText].
     * Rejects edits past [maxLength] and insertions that don't match [allowedPattern] (an
     * invalid pattern allows everything, like iOS).
     */
    fun isInputAllowed(input: String, removedLength: Int, currentText: String, maxLength: Int?, allowedPattern: String?): Boolean {
        if (maxLength != null && currentText.length + input.length - removedLength > maxLength) return false
        if (allowedPattern != null && input.isNotEmpty()) {
            val regex = runCatching { Regex(allowedPattern) }.getOrNull() ?: return true
            return regex.containsMatchIn(input)
        }
        return true
    }

    /** The edit that turns [old] into [new]: (inserted text, number of replaced characters). */
    fun edit(old: String, new: String): Pair<String, Int> {
        var prefix = 0
        val max = minOf(old.length, new.length)
        while (prefix < max && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return new.substring(prefix, new.length - suffix) to (old.length - prefix - suffix)
    }
}

/** UISlider value handling (`CSliderViewModel.snapToStep`). */
internal object SliderMath {
    fun snap(value: Float, min: Float, max: Float, step: Float?): Float {
        if (step == null || step <= 0f) return value.coerceIn(min, maxOf(min, max))
        val snapped = min + Math.round((value - min) / step) * step
        return snapped.coerceIn(min, maxOf(min, max))
    }

    /**
     * M3 Slider `steps`: the stops between the ends. Only coarse steps get tick marks (UISlider
     * has none); fine steps (more than [MAX_TICKS] stops) slide continuously and snap in code.
     */
    fun steps(min: Float, max: Float, step: Float?): Int {
        if (step == null || step <= 0f || max <= min) return 0
        val n = ((max - min) / step).roundToLong() - 1
        return if (n in 1..MAX_TICKS.toLong()) n.toInt() else 0
    }

    const val MAX_TICKS = 10
}

/**
 * `datePicker` values (`CDatePickerViewModel`): reads ISO 8601 date-times or `yyyy-MM-dd`;
 * writes `yyyy-MM-dd` (local date) in `date` mode, and a local date-time with the UTC offset
 * (`yyyy-MM-dd'T'HH:mm:ssXXX`, e.g. `2026-03-04T14:30:00+01:00`) in the time modes.
 */
internal object DateValues {
    private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val localDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    fun parse(text: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val s = text?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        runCatching { return ZonedDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return LocalDate.parse(s, shortDate).atStartOfDay(zone).toInstant().toEpochMilli() }
        return null
    }

    fun format(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(shortDate)

    /** The bound-variable value for [mode]: the date only in `date` mode, else date-time + offset (no fraction). */
    fun format(epochMillis: Long, mode: DatePickerProperties.Mode, zone: ZoneId = ZoneId.systemDefault()): String =
        when (mode) {
            DatePickerProperties.Mode.date -> format(epochMillis, zone)
            DatePickerProperties.Mode.time, DatePickerProperties.Mode.dateAndTime, DatePickerProperties.Mode.countdownTimer ->
                Instant.ofEpochMilli(epochMillis).atZone(zone).format(localDateTime)
        }

    /** The variable → view filter: a bound change of a second or less is not a new date. */
    fun differs(a: Long, b: Long): Boolean = abs(a - b) > 1000
}

/**
 * Forward-only video marks (`VideoPlaybackObserver`): a mark fires once when playback
 * crosses its time; a loop re-arms every mark; a replay resets them.
 */
internal class VideoMarkTracker(private val marks: List<Pair<String, Double>>) {
    private val fired = HashSet<String>()
    private var last = -1.0

    /** Marks crossed moving from the previous position to [position] (seconds). */
    fun advance(position: Double): List<String> {
        if (marks.isEmpty()) return emptyList()
        if (position < last) {
            // Jumped back without a loop signal (seek): marks behind us re-arm.
            fired.removeAll { id -> marks.first { it.first == id }.second > position }
        }
        val from = last
        last = position
        return marks.filter { (id, t) -> id !in fired && t > from && t <= position }
            .map { it.first }
            .onEach { fired += it }
    }

    fun loop() {
        fired.clear()
        last = -1.0
    }

    fun reset() = loop()
}

/** The shimmer band (iOS gradient `locations` animating from [-1,-0.5,0] to [1,1.5,2]). */
internal object ShimmerMath {
    /** Gradient stops (start, peak, end) along the sweep axis, in unit lengths, at progress [t] ∈ [0,1]. */
    fun stops(t: Float): Triple<Float, Float, Float> {
        val s = -1f + 2f * t
        return Triple(s, s + 0.5f, s + 1f)
    }
}

/** Wheel picker geometry: which row sits under the selection band. */
internal object WheelMath {
    fun centeredIndex(firstVisible: Int, firstVisibleOffsetPx: Int, itemHeightPx: Int, count: Int): Int {
        if (count <= 0 || itemHeightPx <= 0) return 0
        val extra = if (firstVisibleOffsetPx * 2 >= itemHeightPx) 1 else 0
        return (firstVisible + extra).coerceIn(0, count - 1)
    }
}
