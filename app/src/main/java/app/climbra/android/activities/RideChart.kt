package app.climbra.android.activities

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.data.api.IntervalBlock
import app.climbra.android.data.api.Recording
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Series of the ride chart, as the web's `RideSeries`. */
enum class RideSeries(val label: Int, val unit: String) {
    POWER(R.string.ride_chart_power, "W"),
    HEART_RATE(R.string.ride_chart_heart_rate, "bpm"),
    CADENCE(R.string.ride_chart_cadence, "rpm"),
    SPEED(R.string.ride_chart_speed, "km/h"),
    ALTITUDE(R.string.ride_chart_altitude, "m"),
}

/** The samples of one series; speed shown in km/h as on the web. */
fun Recording.values(series: RideSeries): List<Double?> = when (series) {
    RideSeries.POWER -> samples.power
    RideSeries.HEART_RATE -> samples.heartRate
    RideSeries.CADENCE -> samples.cadence
    RideSeries.SPEED -> samples.speed.map { it?.times(3.6) }
    RideSeries.ALTITUDE -> samples.altitude
}

/** Series with at least one recorded value. */
fun Recording.availableSeries(): List<RideSeries> = RideSeries.entries.filter { series -> values(series).any { it != null } }

/** The visible part of the ride, in seconds from the start. */
@Immutable
data class ChartWindow(val start: Double, val end: Double) {
    val length get() = end - start

    companion object {
        /** At least this many seconds stay visible when zooming in. */
        const val MIN_SECONDS = 30.0
    }
}

/** Keeps a window inside the ride and at least [ChartWindow.MIN_SECONDS] long. */
fun ChartWindow.clampedTo(duration: Double): ChartWindow {
    val length = min(max(length, min(ChartWindow.MIN_SECONDS, duration)), duration)
    val start = start.coerceIn(0.0, max(duration - length, 0.0))
    return ChartWindow(start, start + length)
}

/**
 * The window after one step of a pinch / drag: zoomed by [zoom] around the point at [focus] (0–1 of the plot width)
 * and moved by [pan] (a fraction of the plot width, positive to the right, so earlier parts come into view).
 */
fun ChartWindow.transformed(focus: Float, zoom: Float, pan: Float, duration: Double): ChartWindow {
    val second = start + length * focus
    val newLength = length / zoom
    val newStart = second - (second - start) / zoom - pan * newLength
    return ChartWindow(newStart, newStart + newLength).clampedTo(duration)
}

/** The window set by the range slider's thumbs, whole seconds, at least [ChartWindow.MIN_SECONDS] long. */
fun windowFromRange(start: Float, end: Float, duration: Double): ChartWindow {
    val from = kotlin.math.round(start.toDouble())
    val to = min(kotlin.math.round(end.toDouble()), duration)
    return if (to - from >= min(ChartWindow.MIN_SECONDS, duration)) ChartWindow(from, to).clampedTo(duration)
    else ChartWindow(from, from + ChartWindow.MIN_SECONDS).clampedTo(duration)
}

/** A window around an interval with a margin of a fifth of its length on both sides. */
fun windowAround(block: IntervalBlock, duration: Double): ChartWindow {
    val margin = max((block.endSecond - block.startSecond) * 0.2, 10.0)
    return ChartWindow(block.startSecond - margin, block.endSecond + margin).clampedTo(duration)
}

/** The value range a series is drawn on, and the step of its axis labels. */
@Immutable
data class AxisScale(val low: Double, val high: Double, val step: Double) {
    /** Label values from [low] to [high], every [step]. */
    val ticks: List<Double>
        get() = generateSequence(low) { it + step }.takeWhile { it <= high + step * 1e-6 }.toList()
}

/** A round step (1, 2, 2.5 or 5 times a power of ten) of at least [raw]. */
internal fun niceStep(raw: Double): Double {
    if (raw <= 0 || raw.isNaN()) return 1.0
    val magnitude = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw * (1 - 1e-9) }
}

/**
 * The scale of a series: from 0 up to a round value above the maximum with about five labels; altitude from its
 * minimum to its maximum (it has no axis, as on the web).
 */
fun seriesScale(series: RideSeries, values: List<Double>): AxisScale {
    val max = values.max()
    if (series == RideSeries.ALTITUDE) {
        val min = values.min()
        val high = if (max > min) max else min + 1
        return AxisScale(min, high, high - min)
    }
    val step = niceStep(max(max, 1.0) / 5)
    return AxisScale(0.0, max(ceil(max / step), 1.0) * step, step)
}

/** Steps of the time axis, in seconds. */
private val TIME_STEPS = listOf(5, 10, 15, 30, 60, 120, 300, 600, 900, 1200, 1800, 3600, 7200, 14400)

/** Label times of the visible [window]: the smallest round step that gives at most [maxLabels] labels. */
fun timeTicks(window: ChartWindow, maxLabels: Int): List<Double> {
    val step = TIME_STEPS.firstOrNull { window.length / it <= max(maxLabels, 1) } ?: TIME_STEPS.last()
    val first = ceil(window.start / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= window.end }.toList()
}

/** A time label: `m:ss` (or `h:mm:ss`) while the labels are under a minute apart or the ride is shorter than an hour. */
internal fun timeLabel(fmt: Fmt, second: Double, step: Double, duration: Double): String =
    if (step >= 60 && duration >= 3600) fmt.hm(second) else fmt.duration(second)

private val LEFT_AXIS = 36.dp
private val RIGHT_AXIS = 32.dp
private val BOTTOM_AXIS = 18.dp
private val NO_AXIS = 4.dp
private val TIME_LABEL_SPACING = 64.dp

@Composable
private fun seriesColor(series: RideSeries): Color = when (series) {
    RideSeries.POWER -> MaterialTheme.extendedColors.power
    RideSeries.HEART_RATE -> MaterialTheme.extendedColors.heartRate
    RideSeries.CADENCE -> MaterialTheme.extendedColors.cadence
    RideSeries.SPEED -> MaterialTheme.extendedColors.speed
    RideSeries.ALTITUDE -> MaterialTheme.extendedColors.altitude
}

/**
 * Power, heart rate, cadence, speed and altitude over the ride, each on its own scale (as the web's separate axes),
 * work blocks shaded and unders / recoveries lighter. Pinch to zoom, drag to pan, tap for the values at a time.
 *
 * @param window the visible part, hoisted so a tapped interval can zoom the chart
 */
@Composable
fun RideChart(
    recording: Recording,
    blocks: List<IntervalBlock>,
    gaps: List<IntervalBlock>,
    window: ChartWindow,
    onWindowChange: (ChartWindow) -> Unit,
    fmt: Fmt,
    modifier: Modifier = Modifier,
) {
    val available = remember(recording) { recording.availableSeries() }
    var hidden by rememberSaveable { mutableStateOf(setOf<String>()) }
    val shown = available.filter { it.name !in hidden }
    var probe by remember(recording) { mutableStateOf<Int?>(null) }
    val times = recording.samples.t
    val duration = (times.lastOrNull() ?: 0) + recording.resolutionSeconds.toDouble()
    val colors = RideSeries.entries.associateWith { seriesColor(it) }
    val blockColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val gapColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    val grid = MaterialTheme.extendedColors.chartGrid
    val label = stringResource(R.string.ride_chart_label)
    val rangeLabel = stringResource(R.string.ride_chart_range)
    // Read by the gestures, which outlive recompositions; a pinch step updates it before the next frame
    val windowRef = remember { mutableStateOf(window) }
    windowRef.value = window
    val currentOnWindowChange by rememberUpdatedState(onWindowChange)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            available.forEach { series ->
                FilterChip(
                    selected = series.name !in hidden,
                    onClick = { hidden = if (series.name in hidden) hidden - series.name else hidden + series.name },
                    label = { Text(stringResource(series.label)) },
                    leadingIcon = {
                        Surface(color = colors.getValue(series), shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                    },
                    modifier = Modifier.testTag("series-${series.name.lowercase()}"),
                )
            }
        }
        val scales = remember(recording) {
            available.associateWith { series -> seriesScale(series, recording.values(series).filterNotNull()) }
        }
        // As the web: the power axis on the left, the heart rate one on the right, the other series without an axis
        val leftAxis = RideSeries.POWER.takeIf { it in shown }
        val rightAxis = RideSeries.HEART_RATE.takeIf { it in shown }
        val leftGutter = if (leftAxis != null) LEFT_AXIS else NO_AXIS
        val rightGutter = if (rightAxis != null) RIGHT_AXIS else NO_AXIS
        val axisColor = MaterialTheme.extendedColors.chartAxis
        val labelStyle = MaterialTheme.typography.labelSmall.copy(
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val measurer = rememberTextMeasurer()
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .testTag("ride-chart")
                    .semantics { contentDescription = label }
                    .pointerInput(duration, leftGutter, rightGutter) {
                        // The gesture outlives recompositions: every step starts from the window as it is now
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val plotLeft = leftGutter.toPx()
                            val plotWidth = (size.width - plotLeft - rightGutter.toPx()).coerceAtLeast(1f)
                            val focus = ((centroid.x - plotLeft) / plotWidth).coerceIn(0f, 1f)
                            val next = windowRef.value.transformed(focus, zoom, pan.x / plotWidth, duration)
                            windowRef.value = next
                            currentOnWindowChange(next)
                        }
                    }
                    .pointerInput(times, leftGutter, rightGutter) {
                        detectTapGestures { offset ->
                            val plotLeft = leftGutter.toPx()
                            val plotWidth = (size.width - plotLeft - rightGutter.toPx()).coerceAtLeast(1f)
                            val shownWindow = windowRef.value
                            val second = shownWindow.start + shownWindow.length * ((offset.x - plotLeft) / plotWidth)
                            probe = times.indices.minByOrNull { kotlin.math.abs(times[it] - second) }
                        }
                    },
            ) {
                val left = leftGutter.toPx()
                val right = size.width - rightGutter.toPx()
                val bottom = size.height - BOTTOM_AXIS.toPx()
                val width = (right - left).coerceAtLeast(1f)
                fun x(second: Double) = (left + (second - window.start) / window.length * width).toFloat()
                fun y(value: Double, scale: AxisScale) = (bottom * (1 - (value - scale.low) / (scale.high - scale.low))).toFloat()
                fun span(block: IntervalBlock, color: Color) {
                    val start = x(block.startSecond.toDouble()).coerceIn(left, right)
                    val end = x(block.endSecond.toDouble()).coerceIn(left, right)
                    if (end > start) drawRect(color, Offset(start, 0f), Size(end - start, bottom))
                }
                // Intervals behind the lines
                gaps.forEach { span(it, gapColor) }
                blocks.forEach { span(it, blockColor) }
                // Grid lines on the labels of the left axis, quarters without it
                val gridLines = leftAxis?.let { axis -> scales.getValue(axis).let { s -> s.ticks.map { y(it, s) } } }
                    ?: (1..3).map { bottom * it / 4 }
                gridLines.filter { it > 0.5f && it < bottom - 0.5f }
                    .forEach { drawLine(grid, Offset(left, it), Offset(right, it), strokeWidth = 1f) }
                drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1f)
                clipRect(left = left, top = 0f, right = right, bottom = bottom) {
                    shown.forEach { series ->
                        val values = recording.values(series)
                        val scale = scales[series] ?: return@forEach
                        val path = Path()
                        var drawing = false
                        times.forEachIndexed { index, second ->
                            val value = values.getOrNull(index)
                            if (value == null || second < window.start - recording.resolutionSeconds ||
                                second > window.end + recording.resolutionSeconds
                            ) {
                                drawing = false
                                return@forEachIndexed
                            }
                            val point = Offset(x(second.toDouble()), y(value, scale))
                            if (drawing) path.lineTo(point.x, point.y) else path.moveTo(point.x, point.y)
                            drawing = true
                        }
                        drawPath(path, colors.getValue(series), style = Stroke(width = if (series == RideSeries.POWER) 1.5f else 2f))
                    }
                }
                // Value axes: labels in the series' color, centred on their value
                fun valueLabels(series: RideSeries?, atLeft: Boolean) {
                    val scale = series?.let { scales.getValue(it) } ?: return
                    val style = labelStyle.copy(color = colors.getValue(series))
                    scale.ticks.forEach { value ->
                        val text = measurer.measure(fmt.num(value), style)
                        val top = (y(value, scale) - text.size.height / 2f).coerceIn(0f, bottom - text.size.height)
                        val gap = 4.dp.toPx()
                        val textX = if (atLeft) left - gap - text.size.width else right + gap
                        drawText(text, topLeft = Offset(textX, top))
                    }
                }
                valueLabels(leftAxis, atLeft = true)
                valueLabels(rightAxis, atLeft = false)
                // Time axis
                val ticks = timeTicks(window, (width / TIME_LABEL_SPACING.toPx()).toInt())
                val step = if (ticks.size > 1) ticks[1] - ticks[0] else window.length
                ticks.forEach { second ->
                    val text = measurer.measure(timeLabel(fmt, second, step, duration), labelStyle)
                    val center = x(second)
                    drawLine(axisColor, Offset(center, bottom), Offset(center, bottom + 3.dp.toPx()), strokeWidth = 1f)
                    val textX = (center - text.size.width / 2f).coerceIn(left, size.width - text.size.width)
                    drawText(text, topLeft = Offset(textX, bottom + 3.dp.toPx()))
                }
                probe?.let { index ->
                    val px = x(times[index].toDouble())
                    if (px in left..right) {
                        drawLine(
                            grid.copy(alpha = 1f),
                            Offset(px, 0f),
                            Offset(px, bottom),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                        )
                    }
                }
            }
            probe?.let { index ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).testTag("ride-chart-probe"),
                ) {
                    Column(Modifier.padding(6.dp)) {
                        Text(fmt.duration(times[index]), style = MaterialTheme.typography.labelSmall, fontFamily = MonoFamily)
                        shown.forEach { series ->
                            val value = recording.values(series).getOrNull(index)
                            Text(
                                "${stringResource(series.label)} ${fmt.num(value, if (series == RideSeries.SPEED) 1 else 0)} ${series.unit}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.getValue(series),
                            )
                        }
                    }
                }
            }
        }
        if (duration > ChartWindow.MIN_SECONDS) {
            // The web's slider under the chart: both ends of the shown part, in step with pinch and drag
            RangeSlider(
                value = window.start.toFloat()..window.end.toFloat(),
                onValueChange = { range -> onWindowChange(windowFromRange(range.start, range.endInclusive, duration)) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = leftGutter, end = rightGutter)
                    .height(32.dp)
                    .testTag("ride-chart-range")
                    .semantics { contentDescription = rangeLabel },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${fmt.duration(window.start)} – ${fmt.duration(window.end)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("ride-chart-window"),
            )
            if (window.start > 0.5 || window.end < duration - 0.5) {
                TextButton(onClick = { onWindowChange(ChartWindow(0.0, duration)) }, modifier = Modifier.testTag("ride-chart-reset")) {
                    Icon(painterResource(R.drawable.ic_zoom_out_map), contentDescription = null, Modifier.size(16.dp))
                    Text(stringResource(R.string.ride_chart_reset_zoom), Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}
