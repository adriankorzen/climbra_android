package app.climbra.android.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.climbra.android.data.api.DailyLoad
import app.climbra.android.data.api.WeekLoad
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.extendedColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

private val CHART_HEIGHT = 240.dp

/** Labels of the x axis by index, so the axis shows dates instead of positions. */
private fun labels(values: List<String>) = CartesianValueFormatter { _, x, _ ->
    values.getOrNull(x.toInt()) ?: " "
}

@Composable
private fun marker() = rememberDefaultCartesianMarker(
    label = rememberTextComponent(
        style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    ),
)

/**
 * Performance management: CTL (area), ATL and TSB (dashed) lines on the left axis, daily TSS bars on the right one —
 * the web's `pmcOption`. The whole range always fits the width.
 */
@Composable
fun PmcChart(days: List<DailyLoad>, fmt: Fmt, description: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.extendedColors
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(days) {
        if (days.isEmpty()) return@LaunchedEffect
        producer.runTransaction {
            columnModel { series(days.map { it.tss }) }
            lineModel {
                series(days.map { it.ctl })
                series(days.map { it.atl })
                series(days.map { it.tsb })
            }
        }
    }
    val dates = remember(days, fmt) { days.map { fmt.date(it.date, DateStyle.DAY_MONTH) } }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                ColumnCartesianLayer.ColumnProvider.series(
                    rememberLineComponent(fill = Fill(colors.altitude.copy(alpha = 0.55f)), thickness = 4.dp),
                ),
                verticalAxisPosition = Axis.Position.Vertical.End,
            ),
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(colors.speed)),
                        areaFill = LineCartesianLayer.AreaFill.single(Fill(colors.speed.copy(alpha = 0.12f))),
                    ),
                    LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(Fill(colors.heartRate))),
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(colors.powerZones[3])),
                        stroke = LineCartesianLayer.LineStroke.Dashed(),
                    ),
                ),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
            startAxis = VerticalAxis.rememberStart(),
            endAxis = VerticalAxis.rememberEnd(guideline = null),
            bottomAxis = HorizontalAxis.rememberBottom(guideline = null, valueFormatter = labels(dates)),
            marker = marker(),
        ),
        modelProducer = producer,
        modifier = modifier.fillMaxWidth().height(CHART_HEIGHT).testTag("chart-pmc")
            .semantics { contentDescription = description },
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
    )
}

/** TSS bars and the hours line per ISO week — the web's `weeklyOption`. */
@Composable
fun WeeklyChart(weeks: List<WeekLoad>, fmt: Fmt, description: String, modifier: Modifier = Modifier) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(weeks) {
        if (weeks.isEmpty()) return@LaunchedEffect
        producer.runTransaction {
            columnModel { series(weeks.map { it.tss }) }
            lineModel { series(weeks.map { it.seconds / 3600.0 }) }
        }
    }
    val dates = remember(weeks, fmt) { weeks.map { fmt.date(it.weekStart, DateStyle.DAY_MONTH) } }
    val hours: Color = MaterialTheme.extendedColors.speed
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                ColumnCartesianLayer.ColumnProvider.series(
                    rememberLineComponent(fill = Fill(MaterialTheme.colorScheme.primary), thickness = 12.dp),
                ),
                verticalAxisPosition = Axis.Position.Vertical.Start,
            ),
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(Fill(hours))),
                ),
                verticalAxisPosition = Axis.Position.Vertical.End,
            ),
            startAxis = VerticalAxis.rememberStart(),
            endAxis = VerticalAxis.rememberEnd(guideline = null),
            bottomAxis = HorizontalAxis.rememberBottom(guideline = null, valueFormatter = labels(dates)),
            marker = marker(),
        ),
        modelProducer = producer,
        modifier = modifier.fillMaxWidth().height(CHART_HEIGHT).testTag("chart-weekly")
            .semantics { contentDescription = description },
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
    )
}
