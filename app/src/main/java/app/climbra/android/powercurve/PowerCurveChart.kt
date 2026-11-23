package app.climbra.android.powercurve

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.data.api.PowerCurvePoint
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors
import kotlin.math.abs
import kotlin.math.log10

/** The longest duration of a curve (cyclingapp spec 061), the right end of the axis. */
const val MAX_SECONDS = 18_000

/** Where the duration axis has its labels, as on the web. */
val AXIS_DURATIONS = listOf(5, 60, 300, 1200, 3600, 18_000)

/** The place of a duration on the logarithmic axis, 0 (1 s) to 1 (5 h): a display transform, not a figure. */
fun xFraction(seconds: Int): Float = (log10(seconds.coerceIn(1, MAX_SECONDS).toDouble()) / log10(MAX_SECONDS.toDouble())).toFloat()

/** The point nearest [fraction] of the chart's width; `null` outside the chart or without points. */
fun pointAt(points: List<PowerCurvePoint>, fraction: Float): PowerCurvePoint? {
    if (fraction < 0f || fraction > 1f) return null
    return points.minByOrNull { abs(xFraction(it.durationSeconds) - fraction) }
}

/**
 * The period's curve, solid in the power color, and the previous period's, dashed (spec 026), over a logarithmic
 * duration axis. A tap or drag picks the nearest duration and shows its figures above the chart; a tap on it again,
 * or outside, clears it. Drawn as the workout chart of spec 023.
 */
@Composable
fun PowerCurveChart(
    current: List<PowerCurvePoint>,
    previous: List<PowerCurvePoint>?,
    fmt: Fmt,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.extendedColors
    val power = colors.power
    val before = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = colors.chartGrid
    val axis = colors.chartAxis
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    var picked by remember(current) { mutableStateOf<PowerCurvePoint?>(null) }
    val pickedBefore = picked?.let { point -> previous?.firstOrNull { it.durationSeconds == point.durationSeconds } }
    val pickedLabel = picked?.let { point ->
        val perKg = point.wattsPerKg?.let { stringResource(R.string.unit_watts_per_kg, fmt.num(it, 2)) }
        val parts = listOfNotNull(
            fmt.shortDuration(point.durationSeconds),
            "${fmt.num(point.watts)} W",
            perKg,
            fmt.date(point.date, DateStyle.DAY_MONTH_YEAR),
            pickedBefore?.let { stringResource(R.string.power_curve_picked_before, fmt.num(it.watts)) },
        )
        parts.joinToString(" · ")
    }
    val top = ((listOfNotNull(current.maxOfOrNull { it.watts }, previous?.maxOfOrNull { it.watts }).maxOrNull() ?: 100) * 1.1f)
        .coerceAtLeast(1f)
    val description = stringResource(R.string.power_curve_chart)
    Column(modifier.fillMaxWidth()) {
        Text(
            pickedLabel.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = MonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("power-curve-picked"),
        )
        Box(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
            Canvas(
                Modifier.fillMaxWidth().height(CHART_HEIGHT).testTag("power-curve-chart")
                    .semantics { contentDescription = description }
                    .pointerInput(current) {
                        detectTapGestures { offset ->
                            val point = pointAt(current, offset.x / size.width)
                            picked = if (point == picked) null else point
                        }
                    }
                    .pointerInput(current) {
                        detectHorizontalDragGestures { change, _ -> picked = pointAt(current, change.position.x / size.width) }
                    },
            ) {
                val bottom = size.height - LABEL_SPACE.toPx()
                // Watts guides at quarters of the top
                for (step in 1..3) {
                    val y = bottom - bottom * step / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    drawLabel(measurer, fmt.num(top * step / 4f), Offset(2.dp.toPx(), y - 14.dp.toPx()), labelStyle)
                }
                drawLine(axis, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
                AXIS_DURATIONS.forEach { seconds ->
                    val x = size.width * xFraction(seconds)
                    val label = fmt.shortDuration(seconds)
                    val width = measurer.measure(label, labelStyle).size.width
                    drawLabel(
                        measurer, label,
                        Offset((x - width / 2f).coerceIn(0f, size.width - width), bottom + 2.dp.toPx()), labelStyle,
                    )
                }
                fun line(points: List<PowerCurvePoint>, color: Color, dashed: Boolean) {
                    if (points.isEmpty()) return
                    val path = Path()
                    points.forEachIndexed { index, point ->
                        val x = size.width * xFraction(point.durationSeconds)
                        val y = bottom - bottom * point.watts / top
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path, color,
                        style = Stroke(
                            width = (if (dashed) 1.5f else 2.5f) * density,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f * density, 6f * density)) else null,
                        ),
                    )
                }
                previous?.let { line(it, before, dashed = true) }
                line(current, power, dashed = false)
                picked?.let { point ->
                    val x = size.width * xFraction(point.durationSeconds)
                    drawLine(axis, Offset(x, 0f), Offset(x, bottom), strokeWidth = 1.dp.toPx())
                    drawCircle(power, radius = 4.dp.toPx(), center = Offset(x, bottom - bottom * point.watts / top))
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    style: androidx.compose.ui.text.TextStyle,
) = drawText(measurer, text, topLeft = at, style = style)

private val CHART_HEIGHT = 240.dp
private val LABEL_SPACE = 18.dp
