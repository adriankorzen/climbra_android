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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.max
import kotlin.math.min

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

/** A window around an interval with a margin of a fifth of its length on both sides. */
fun windowAround(block: IntervalBlock, duration: Double): ChartWindow {
    val margin = max((block.endSecond - block.startSecond) * 0.2, 10.0)
    return ChartWindow(block.startSecond - margin, block.endSecond + margin).clampedTo(duration)
}

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
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .testTag("ride-chart")
                    .semantics { contentDescription = label }
                    .pointerInput(duration) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val current = window
                            val focus = current.start + current.length * (centroid.x / size.width)
                            val length = current.length / zoom
                            val shift = -pan.x / size.width * length
                            val start = focus - (focus - current.start) / zoom + shift
                            onWindowChange(ChartWindow(start, start + length).clampedTo(duration))
                        }
                    }
                    .pointerInput(times, window) {
                        detectTapGestures { offset ->
                            val second = window.start + window.length * (offset.x / size.width)
                            probe = times.indices.minByOrNull { kotlin.math.abs(times[it] - second) }
                        }
                    },
            ) {
                fun x(second: Double) = ((second - window.start) / window.length * size.width).toFloat()
                // Intervals behind the lines
                gaps.forEach { drawRect(gapColor, Offset(x(it.startSecond.toDouble()), 0f), Size(x(it.endSecond.toDouble()) - x(it.startSecond.toDouble()), size.height)) }
                blocks.forEach { drawRect(blockColor, Offset(x(it.startSecond.toDouble()), 0f), Size(x(it.endSecond.toDouble()) - x(it.startSecond.toDouble()), size.height)) }
                for (line in 1..3) {
                    val y = size.height * line / 4
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                shown.forEach { series ->
                    val values = recording.values(series)
                    val present = values.filterNotNull()
                    if (present.isEmpty()) return@forEach
                    val low = if (series == RideSeries.ALTITUDE) present.min() else 0.0
                    val high = present.max().takeIf { it > low } ?: (low + 1)
                    val path = Path()
                    var drawing = false
                    times.forEachIndexed { index, second ->
                        val value = values.getOrNull(index)
                        if (value == null || second < window.start - recording.resolutionSeconds || second > window.end) {
                            drawing = false
                            return@forEachIndexed
                        }
                        val point = Offset(x(second.toDouble()), (size.height * (1 - (value - low) / (high - low))).toFloat())
                        if (drawing) path.lineTo(point.x, point.y) else path.moveTo(point.x, point.y)
                        drawing = true
                    }
                    drawPath(path, colors.getValue(series), style = Stroke(width = if (series == RideSeries.POWER) 1.5f else 2f))
                }
                probe?.let { index ->
                    val px = x(times[index].toDouble())
                    drawLine(
                        grid.copy(alpha = 1f),
                        Offset(px, 0f),
                        Offset(px, size.height),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                    )
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
