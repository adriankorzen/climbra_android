package app.climbra.android.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.data.api.ThresholdEntry
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.extendedColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A point of the trend: from [day] on, the FTP is [watts]. */
data class FtpStep(val day: LocalDate, val watts: Int)

/**
 * The steps of the FTP trend, oldest first: each entry's FTP as the backend resolves it (cyclingapp spec 053), held
 * until [today]. Entries without any FTP are left out; nothing is computed but the order.
 */
fun ftpSteps(entries: List<ThresholdEntry>, today: LocalDate): List<FtpStep> {
    val steps = entries.mapNotNull { entry ->
        val watts = entry.resolved.ftpWatts ?: return@mapNotNull null
        val day = runCatching { LocalDate.parse(entry.effectiveFrom) }.getOrNull() ?: return@mapNotNull null
        FtpStep(day, watts)
    }.sortedBy { it.day }
    val last = steps.lastOrNull() ?: return steps
    return if (last.day.isBefore(today)) steps + FtpStep(today, last.watts) else steps
}

/** The FTP axis: from [min] to [max] watts, labelled every [step]. */
data class FtpAxis(val min: Int, val max: Int, val step: Int)

private val AXIS_STEPS = listOf(10, 20, 25, 50, 100, 200)

/**
 * An axis around the trend's watts rather than from zero: headroom of a tenth of the span (at least 10 W) on both
 * sides, rounded out to the smallest step that leaves at most five intervals, so the line never runs along the top
 * edge (299 W reads under the 300 W label).
 */
fun ftpAxis(steps: List<FtpStep>): FtpAxis {
    val low = steps.minOf { it.watts }
    val high = steps.maxOf { it.watts }
    val pad = maxOf(10, (high - low) / 10)
    fun around(step: Int) = FtpAxis(
        min = maxOf(0, Math.floorDiv(low - pad, step) * step),
        max = Math.floorDiv(high + pad + step - 1, step) * step,
        step = step,
    )
    return AXIS_STEPS.map(::around).firstOrNull { (it.max - it.min) / it.step <= 5 } ?: around(AXIS_STEPS.last())
}

@Composable
internal fun FtpTrendCard(section: Section<List<ThresholdEntry>>, fmt: Fmt, today: LocalDate) {
    ProfileCard(stringResource(R.string.profile_ftp_trend), "profile-ftp-trend") {
        val entries = section.value
        when {
            entries == null && !section.failed -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> {
                val steps = remember(entries, today) { ftpSteps(entries.orEmpty(), today) }
                if (steps.isEmpty()) {
                    Faint(stringResource(R.string.profile_no_thresholds), Modifier.testTag("ftp-trend-empty"))
                } else {
                    FtpChart(steps, fmt, stringResource(R.string.profile_ftp_trend))
                }
            }
        }
    }
}

/** A step line of FTP in watts over time, the web's `ftpChart` (`step: 'end'`). */
@Composable
private fun FtpChart(steps: List<FtpStep>, fmt: Fmt, description: String) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(steps) {
        // A step holds until the next one: each change is a jump from the day before (Vico keeps one value per x)
        val x = mutableListOf<Long>()
        val y = mutableListOf<Int>()
        steps.forEachIndexed { index, step ->
            val previous = steps.getOrNull(index - 1)
            if (previous != null && step.day.toEpochDay() - 1 > previous.day.toEpochDay()) {
                x += step.day.toEpochDay() - 1
                y += previous.watts
            }
            x += step.day.toEpochDay()
            y += step.watts
        }
        producer.runTransaction { lineModel { series(x, y) } }
    }
    val monthYear = remember(fmt) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(fmt.locale, "MMM yy"), fmt.locale)
    }
    val color = MaterialTheme.extendedColors.power
    val axis = remember(steps) { ftpAxis(steps) }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(color)),
                        areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.12f))),
                    ),
                ),
                rangeProvider = remember(axis) {
                    CartesianLayerRangeProvider.fixed(minY = axis.min.toDouble(), maxY = axis.max.toDouble())
                },
            ),
            startAxis = VerticalAxis.rememberStart(
                valueFormatter = CartesianValueFormatter { _, value, _ -> fmt.num(value) },
                itemPlacer = remember(axis) { VerticalAxis.ItemPlacer.step({ axis.step.toDouble() }) },
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                guideline = null,
                valueFormatter = CartesianValueFormatter { _, value, _ -> monthYear.format(LocalDate.ofEpochDay(value.toLong())) },
            ),
            marker = rememberDefaultCartesianMarker(
                label = rememberTextComponent(
                    style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                ),
            ),
        ),
        modelProducer = producer,
        modifier = Modifier.fillMaxWidth().height(200.dp).testTag("chart-ftp")
            .semantics { contentDescription = description },
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
    )
}
