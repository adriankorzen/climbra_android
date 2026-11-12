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
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(color)),
                        areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.12f))),
                    ),
                ),
            ),
            startAxis = VerticalAxis.rememberStart(valueFormatter = CartesianValueFormatter { _, value, _ -> fmt.num(value) }),
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
