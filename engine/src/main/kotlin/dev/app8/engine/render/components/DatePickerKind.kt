package dev.app8.engine.render.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.DatePickerProperties
import dev.app8.engine.core.model.DatePickerProperties.DisplayStyle
import dev.app8.engine.core.model.DatePickerProperties.Mode
import dev.app8.engine.core.model.style.DatePickerStyle
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.LocalInteractionEnabled
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.bool
import dev.app8.engine.render.controls.DateValues
import dev.app8.engine.render.controls.WheelMath
import dev.app8.engine.render.string
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as JTextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * `datePicker` → Material 3 pickers. `compact` (default): date / time pills that open the
 * M3 [DatePickerDialog] or a [TimePicker] dialog; `inline`: the M3 [DatePicker] in place
 * (time mode stays a pill, as iOS's inline time is); `wheels`: snapping wheel columns
 * (month/day/year, hour/minute, or day + time). A change writes `bindVariable`
 * (`yyyy-MM-dd`, as iOS) and fires `tap`.
 */
internal object DatePickerKind : ComponentKind {
    override val handlesTaps: Boolean get() = true

    private const val PILL_H = 34.0

    private fun props(node: RenderNode) = node.content.properties as? DatePickerProperties ?: DatePickerProperties()

    private fun zone(): ZoneId = ZoneId.systemDefault()

    /**
     * The shown date: the control's own value, unless the bound variable now names a different
     * day (an external write); then the bound date (iOS `externalDateUpdate`).
     */
    private fun current(node: RenderNode, p: DatePickerProperties): Long {
        val own = node.ui["date"] as? Long
        val boundRaw = node.bound(p.bindVariable) as? String
        val bound = DateValues.parse(boundRaw)
        if (own != null) {
            return if (bound != null && DateValues.format(own) != boundRaw?.take(10) && DateValues.differs(own, bound)) bound else own
        }
        return bound ?: DateValues.parse(node.string(p.selectedDate)) ?: System.currentTimeMillis()
    }

    private fun dateLabel(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

    private fun timeLabel(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))

    private fun pills(mode: Mode, ms: Long): List<Pair<String, Boolean>> = when (mode) {
        Mode.date -> listOf(dateLabel(ms) to true)
        Mode.time, Mode.countdownTimer -> listOf(timeLabel(ms) to false)
        Mode.dateAndTime -> listOf(dateLabel(ms) to true, timeLabel(ms) to false)
    }

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val p = props(node)
        val mode = p.datePickerMode ?: Mode.date
        val style = p.displayStyle ?: DisplayStyle.compact
        if (style == DisplayStyle.wheels) return LayoutTraits(intrinsic = IntrinsicMeasure { Size(320.0, 216.0) }, signature = "dp:wheels")
        if (style == DisplayStyle.inline && mode != Mode.time && mode != Mode.countdownTimer) {
            val extra = if (mode == Mode.dateAndTime) PILL_H + 8 else 0.0
            return LayoutTraits(intrinsic = IntrinsicMeasure { Size(Size.NO_INTRINSIC_METRIC, 380.0 + extra) }, signature = "dp:inline:$mode")
        }
        val labels = pills(mode, current(node, p)).map { it.first }
        val t = env.text(null, 17f, env.labelColor, 1)
        return LayoutTraits(
            intrinsic = IntrinsicMeasure {
                val w = labels.sumOf { env.measure(AnnotatedString(it), t, null).width + 22.0 } + 6.0 * (labels.size - 1)
                Size(w, PILL_H)
            },
            signature = "dp:$labels",
        )
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val dispatcher = LocalDispatcher.current
        val p = props(node)
        val mode = p.datePickerMode ?: Mode.date
        val display = p.displayStyle ?: DisplayStyle.compact
        val ms = current(node, p)
        val enabled = (node.bool(p.isEnabled) ?: true) && LocalInteractionEnabled.current
        val tint = hexColor((node.content.style as? DatePickerStyle)?.tintColor) ?: MaterialTheme.colorScheme.primary
        val min = DateValues.parse(node.string(p.minimumDate))
        val max = DateValues.parse(node.string(p.maximumDate))
        val set: (Long) -> Unit = { raw ->
            val v = raw.coerceIn(min ?: Long.MIN_VALUE, max ?: Long.MAX_VALUE)
            if (v != ms) {
                node.ui["date"] = v
                node.writeBound(p.bindVariable, DateValues.format(v))
                dispatcher.dispatchTrigger(node, ActionTrigger.tap)
            }
        }
        val ldt = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDateTime()
        val withDate: (LocalDate) -> Unit = { d -> set(LocalDateTime.of(d, ldt.toLocalTime()).atZone(zone()).toInstant().toEpochMilli()) }
        val withTime: (LocalTime) -> Unit = { t -> set(LocalDateTime.of(ldt.toLocalDate(), t).atZone(zone()).toInstant().toEpochMilli()) }
        val m = modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f }

        when {
            display == DisplayStyle.wheels -> Box(m) { Wheels(mode, ldt, min, max, enabled, withDate, withTime, set) }
            display == DisplayStyle.inline && mode != Mode.time && mode != Mode.countdownTimer -> Box(m) {
                InlineCalendar(ldt.toLocalDate(), min, max, tint, enabled, mode == Mode.dateAndTime, ms, withDate, withTime)
            }
            else -> Box(m, contentAlignment = Alignment.CenterEnd) {
                Pills(mode, ms, ldt, min, max, tint, enabled, withDate, withTime)
            }
        }
    }

    // MARK: - compact

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Pills(
        mode: Mode, ms: Long, ldt: LocalDateTime, min: Long?, max: Long?, tint: Color, enabled: Boolean,
        withDate: (LocalDate) -> Unit, withTime: (LocalTime) -> Unit,
    ) {
        var dialog by remember { mutableStateOf<Boolean?>(null) } // true = date, false = time
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((label, isDate) in pills(mode, ms)) {
                Box(
                    Modifier.height(PILL_H.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                        .clickable(enabled) { dialog = isDate }
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val env = LocalRenderEnv.current
                    // Same text style the intrinsic size was measured with.
                    val t = env.text(null, 17f, MaterialTheme.colorScheme.onSurface, 1).style
                    androidx.compose.foundation.text.BasicText(label, style = t, maxLines = 1, softWrap = false)
                }
            }
        }
        when (dialog) {
            true -> {
                val state = rememberDatePickerState(
                    initialSelectedDateMillis = ldt.toLocalDate().utcMillis(),
                    selectableDates = selectable(min, max),
                )
                DatePickerDialog(
                    onDismissRequest = { dialog = null },
                    confirmButton = {
                        TextButton({ state.selectedDateMillis?.let { withDate(it.utcDate()) }; dialog = null }) { Text(stringOk()) }
                    },
                    dismissButton = { TextButton({ dialog = null }) { Text(stringCancel()) } },
                ) { DatePicker(state, colors = DatePickerDefaults.colors(selectedDayContainerColor = tint, selectedDayContentColor = Color.White, todayDateBorderColor = tint, todayContentColor = tint)) }
            }
            false -> {
                val is24 = DateFormat.is24HourFormat(LocalContext.current)
                val state = rememberTimePickerState(ldt.hour, ldt.minute, is24)
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    confirmButton = { TextButton({ withTime(LocalTime.of(state.hour, state.minute)); dialog = null }) { Text(stringOk()) } },
                    dismissButton = { TextButton({ dialog = null }) { Text(stringCancel()) } },
                    text = { TimePicker(state) },
                )
            }
            null -> Unit
        }
    }

    @Composable
    private fun stringOk() = androidx.compose.ui.res.stringResource(android.R.string.ok)

    @Composable
    private fun stringCancel() = androidx.compose.ui.res.stringResource(android.R.string.cancel)

    // MARK: - inline

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun InlineCalendar(
        date: LocalDate, min: Long?, max: Long?, tint: Color, enabled: Boolean, withTimePill: Boolean, ms: Long,
        withDate: (LocalDate) -> Unit, withTime: (LocalTime) -> Unit,
    ) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.utcMillis(), selectableDates = selectable(min, max))
        // View → value.
        LaunchedEffect(state) {
            snapshotFlow { state.selectedDateMillis }.distinctUntilChanged().filter { it != null }.collect { sel ->
                val d = sel!!.utcDate()
                if (d != date) withDate(d)
            }
        }
        // Value → view (bound variable changed elsewhere).
        LaunchedEffect(date) {
            if (state.selectedDateMillis?.utcDate() != date) {
                state.selectedDateMillis = date.utcMillis()
                state.displayedMonthMillis = date.withDayOfMonth(1).utcMillis()
            }
        }
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                DatePicker(
                    state = state,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    colors = DatePickerDefaults.colors(containerColor = Color.Transparent, selectedDayContainerColor = tint, selectedDayContentColor = Color.White, todayDateBorderColor = tint, todayContentColor = tint),
                    modifier = Modifier.fillMaxSize().let { if (enabled) it else it.clickable(enabled = false) {} },
                )
            }
            if (withTimePill) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    Pills(Mode.time, ms, Instant.ofEpochMilli(ms).atZone(zone()).toLocalDateTime(), min, max, tint, enabled, withDate, withTime)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun selectable(min: Long?, max: Long?): SelectableDates {
        val lo = min?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }
        val hi = max?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }
        return object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val d = utcTimeMillis.utcDate()
                return (lo == null || !d.isBefore(lo)) && (hi == null || !d.isAfter(hi))
            }

            override fun isSelectableYear(year: Int): Boolean = (lo == null || year >= lo.year) && (hi == null || year <= hi.year)
        }
    }

    private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

    // MARK: - wheels

    @Composable
    private fun Wheels(
        mode: Mode, ldt: LocalDateTime, min: Long?, max: Long?, enabled: Boolean,
        withDate: (LocalDate) -> Unit, withTime: (LocalTime) -> Unit, set: (Long) -> Unit,
    ) {
        val is24 = DateFormat.is24HourFormat(LocalContext.current)
        val locale = Locale.getDefault()
        val date = ldt.toLocalDate()
        val lo = min?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }
        val hi = max?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val height = maxHeight
            // The selection band behind the centered row.
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth().height(ROW_H)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)),
            )
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mode == Mode.date) {
                    val years = ((lo?.year ?: 1900)..(hi?.year ?: 2100)).toList()
                    Wheel(Modifier.weight(1.6f), height, (1..12).map { java.time.Month.of(it).getDisplayName(JTextStyle.FULL, locale) }, date.monthValue - 1, enabled, TextAlign.Start) { i ->
                        withDate(date.withDayOfMonth(1).withMonth(i + 1).let { it.withDayOfMonth(minOf(date.dayOfMonth, it.lengthOfMonth())) })
                    }
                    Wheel(Modifier.weight(0.7f), height, (1..date.lengthOfMonth()).map { it.toString() }, date.dayOfMonth - 1, enabled, TextAlign.Center) { i ->
                        withDate(date.withDayOfMonth(i + 1))
                    }
                    Wheel(Modifier.weight(1f), height, years.map { it.toString() }, years.indexOf(date.year).coerceAtLeast(0), enabled, TextAlign.Center) { i ->
                        val y = years[i]
                        withDate(LocalDate.of(y, date.monthValue, minOf(date.dayOfMonth, java.time.YearMonth.of(y, date.monthValue).lengthOfMonth())))
                    }
                    return@Row
                }
                if (mode == Mode.dateAndTime) {
                    val today = LocalDate.now(zone())
                    val first = lo ?: today.minusDays(DAY_SPAN)
                    val last = hi ?: today.plusDays(DAY_SPAN)
                    val count = (last.toEpochDay() - first.toEpochDay() + 1).toInt().coerceAtLeast(1)
                    val fmt = DateTimeFormatter.ofPattern("EEE MMM d", locale)
                    val labels = List(count) { i -> first.plusDays(i.toLong()).let { if (it == today) "Today" else it.format(fmt) } }
                    Wheel(Modifier.weight(1.8f), height, labels, (date.toEpochDay() - first.toEpochDay()).toInt().coerceIn(0, count - 1), enabled, TextAlign.End) { i ->
                        withDate(first.plusDays(i.toLong()))
                    }
                }
                TimeWheels(this, height, ldt.toLocalTime(), is24, enabled, withTime)
            }
        }
    }

    @Composable
    private fun TimeWheels(row: RowScope, height: Dp, time: LocalTime, is24: Boolean, enabled: Boolean, withTime: (LocalTime) -> Unit) = with(row) {
        if (is24) {
            Wheel(Modifier.weight(1f), height, (0..23).map { "%02d".format(it) }, time.hour, enabled, TextAlign.Center) { withTime(time.withHour(it)) }
        } else {
            val h12 = (time.hour + 11) % 12
            Wheel(Modifier.weight(1f), height, (1..12).map { it.toString() }, h12, enabled, TextAlign.Center) { i ->
                withTime(time.withHour((i + 1) % 12 + if (time.hour >= 12) 12 else 0))
            }
        }
        Wheel(Modifier.weight(1f), height, (0..59).map { "%02d".format(it) }, time.minute, enabled, TextAlign.Center) { withTime(time.withMinute(it)) }
        if (!is24) {
            Wheel(Modifier.weight(1f), height, listOf("AM", "PM"), if (time.hour >= 12) 1 else 0, enabled, TextAlign.Center) { i ->
                withTime(time.withHour(time.hour % 12 + i * 12))
            }
        }
    }

    private val ROW_H = 34.dp
    private const val DAY_SPAN = 1000L

    /**
     * One snapping wheel column: a lazy list whose centered row is the selection, rows
     * fading and tilting away from the center like a drum.
     */
    @Composable
    private fun Wheel(modifier: Modifier, height: Dp, items: List<String>, selected: Int, enabled: Boolean, align: TextAlign, onSelect: (Int) -> Unit) {
        val state = rememberLazyListState(selected)
        val rowPx = with(LocalDensity.current) { ROW_H.roundToPx() }
        val pad = ((height - ROW_H) / 2).coerceAtLeast(0.dp)
        // Value → view.
        LaunchedEffect(selected, items.size) {
            val at = WheelMath.centeredIndex(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, rowPx, items.size)
            if (at != selected && !state.isScrollInProgress) state.animateScrollToItem(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        }
        // View → value, once the fling settles.
        LaunchedEffect(state, items.size) {
            snapshotFlow { state.isScrollInProgress }.filter { !it }.collect {
                val at = WheelMath.centeredIndex(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, rowPx, items.size)
                if (at != selected) onSelect(at)
            }
        }
        LazyColumn(
            modifier = modifier.fillMaxHeight(),
            state = state,
            contentPadding = PaddingValues(vertical = pad),
            flingBehavior = rememberSnapFlingBehavior(state),
            userScrollEnabled = enabled,
        ) {
            items(items.size, key = { it }, contentType = { "wheelRow" }) { i ->
                Box(
                    Modifier.fillMaxWidth().height(ROW_H).graphicsLayer {
                        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
                        val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2f
                        val dist = info?.let { (it.offset + it.size / 2f - center) / it.size } ?: 0f
                        val f = (abs(dist) / 3.5f).coerceIn(0f, 1f)
                        alpha = 1f - 0.75f * f
                        rotationX = (-dist * 14f).coerceIn(-70f, 70f)
                        cameraDistance = 24f * density
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(items[i], color = MaterialTheme.colorScheme.onSurface, fontSize = 21.sp, textAlign = align, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))
                }
            }
        }
    }
}
