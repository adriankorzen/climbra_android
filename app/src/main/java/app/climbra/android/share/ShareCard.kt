package app.climbra.android.share

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.data.api.ActivityDetail
import app.climbra.android.data.api.AnalysisResponse
import app.climbra.android.data.api.CalendarRide
import app.climbra.android.data.api.CalendarWeek
import app.climbra.android.data.api.Samples
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors
import java.time.LocalDate

/** The size of the share image in dp of [SHARE_DENSITY]: 1080 × 1350 px, portrait 4:5 (spec 028). */
val SHARE_CARD_WIDTH = 360.dp
val SHARE_CARD_HEIGHT = 450.dp
const val SHARE_DENSITY = 3f

/** Lines of the week's ride list at most; the rest is "+ N more". */
const val SHARE_WEEK_RIDE_LINES = 7

/** A ride as the image shows it: the backend's figures of the ride detail, nothing computed (spec 028). */
data class RideCard(
    val name: String?,
    /** The local start, ISO. */
    val start: String,
    val durationSeconds: Int?,
    val distanceMeters: Double?,
    val elevationMeters: Double?,
    /** Power TSS, else hrTSS ([tssFromHeartRate]), as the ride detail shows them. */
    val tss: Double?,
    val tssFromHeartRate: Boolean,
    val intensityFactor: Double?,
    val np: Double?,
    val avgPower: Double?,
    val avgHr: Double?,
    val samples: Samples?,
) {
    val hasPower: Boolean get() = avgPower != null || samples?.power?.any { it != null } == true
    val hasHeartRate: Boolean get() = avgHr != null || samples?.heartRate?.any { it != null } == true

    companion object {
        fun of(activity: ActivityDetail, analysis: AnalysisResponse?, samples: Samples?): RideCard {
            val a = analysis?.analysis
            val powerTss = a?.power?.trainingStressScore
            val hrTss = a?.heartRate?.stressScore
            return RideCard(
                name = activity.name,
                start = activity.startTimeLocal ?: activity.startTime,
                durationSeconds = a?.durationSeconds ?: activity.durationSeconds,
                distanceMeters = a?.distanceMeters ?: activity.distanceMeters,
                elevationMeters = activity.elevationGainMeters,
                tss = powerTss ?: hrTss,
                tssFromHeartRate = powerTss == null && hrTss != null,
                intensityFactor = a?.power?.intensityFactor,
                np = a?.power?.normalizedPower,
                avgPower = a?.power?.averagePower,
                avgHr = a?.heartRate?.average,
                samples = samples,
            )
        }
    }
}

/** A calendar week as the image shows it: the backend's week figures and its rides (spec 028). */
data class WeekCard(val monday: LocalDate, val figures: CalendarWeek?, val rides: List<CalendarRide>)

/** One figure of the card; [key] names its test tag. */
private data class CardFigure(val key: String, val label: String, val value: String)

/** The share image of a ride: drawn off screen into the PNG, in the light theme (spec 028). */
@Composable
fun RideShareCard(card: RideCard, fmt: Fmt, hidePower: Boolean, hideHeartRate: Boolean) {
    CardFrame(
        title = card.name ?: stringResource(R.string.activity_unnamed),
        subtitle = fmt.date(card.start, DateStyle.LONG),
    ) {
        val figures = listOfNotNull(
            card.durationSeconds?.let { CardFigure("duration", stringResource(R.string.activity_duration), fmt.duration(it)) },
            card.distanceMeters?.let { CardFigure("distance", stringResource(R.string.activities_column_distance), "${fmt.km(it)} km") },
            card.elevationMeters?.let { CardFigure("elevation", stringResource(R.string.activities_column_elevation), "${fmt.num(it)} m") },
            card.tss?.let {
                CardFigure("tss", stringResource(if (card.tssFromHeartRate) R.string.share_card_hr_tss else R.string.activities_column_tss), fmt.num(it))
            },
            card.intensityFactor?.let { CardFigure("if", stringResource(R.string.share_card_if), fmt.num(it, 2)) },
            card.np?.takeUnless { hidePower }?.let { CardFigure("np", stringResource(R.string.activities_column_np), "${fmt.num(it)} W") },
            card.avgPower?.takeUnless { hidePower }?.let { CardFigure("avg-power", stringResource(R.string.activity_avg_power), "${fmt.num(it)} W") },
            card.avgHr?.takeUnless { hideHeartRate }?.let { CardFigure("avg-hr", stringResource(R.string.activity_hr), "${fmt.num(it)} bpm") },
        )
        Figures(figures)
        card.samples?.let { samples ->
            RideLines(samples, showPower = !hidePower, showHeartRate = !hideHeartRate, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** The share image of a week (spec 028). */
@Composable
fun WeekShareCard(card: WeekCard, fmt: Fmt) {
    val sunday = card.monday.plusDays(6)
    CardFrame(
        title = stringResource(
            R.string.share_card_week_title,
            fmt.date(card.monday.toString(), DateStyle.DAY_MONTH),
            fmt.date(sunday.toString(), DateStyle.DAY_MONTH),
        ),
        subtitle = pluralStringResource(R.plurals.share_card_rides, card.rides.size, card.rides.size),
    ) {
        val figures = card.figures
        Figures(
            listOf(
                CardFigure(
                    "tss",
                    stringResource(R.string.activities_column_tss),
                    if ((figures?.plannedTss ?: 0) > 0) "${fmt.num(figures?.riddenTss ?: 0)} / ${fmt.num(figures?.plannedTss)}" else fmt.num(figures?.riddenTss ?: 0),
                ),
                CardFigure(
                    "time",
                    stringResource(R.string.activity_duration),
                    if ((figures?.plannedSeconds ?: 0) > 0) "${fmt.hm(figures?.riddenSeconds ?: 0)} / ${fmt.hm(figures?.plannedSeconds)}" else fmt.hm(figures?.riddenSeconds ?: 0),
                ),
            ),
        )
        WeekBars(card, fmt, Modifier.fillMaxWidth().height(110.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("card-rides")) {
            card.rides.take(SHARE_WEEK_RIDE_LINES).forEach { ride ->
                Row(Modifier.fillMaxWidth().testTag("card-ride")) {
                    Text(
                        fmt.date(ride.date, DateStyle.WEEKDAY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.extendedColors.textFaint,
                        modifier = Modifier.width(84.dp),
                    )
                    Text(
                        ride.name ?: stringResource(R.string.activity_unnamed),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${fmt.duration(ride.durationSeconds)} · ${fmt.num(ride.trainingLoad)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                    )
                }
            }
            val more = card.rides.size - SHARE_WEEK_RIDE_LINES
            if (more > 0) {
                Text(
                    pluralStringResource(R.plurals.share_card_more, more, more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.extendedColors.textFaint,
                    modifier = Modifier.testTag("card-more"),
                )
            }
        }
    }
}

@Composable
private fun CardFrame(title: String, subtitle: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(20.dp).testTag("share-card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(colorResource(R.color.climbra_accent))) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.share_card_site), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("card-title"))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint)
        }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Figures(figures: List<CardFigure>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        figures.forEach { figure ->
            Column(Modifier.width(96.dp).semantics(mergeDescendants = true) {}.testTag("card-figure-${figure.key}")) {
                Text(figure.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint)
                Text(figure.value, style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily)
            }
        }
    }
}

/**
 * Power (else speed) and heart rate of the recording, each scaled to its own highest sample: drawn only, no figure
 * is computed from the samples.
 */
@Composable
private fun RideLines(samples: Samples, showPower: Boolean, showHeartRate: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.extendedColors
    val power = samples.power.takeIf { showPower && it.any { value -> value != null } }
    val main = power ?: samples.speed.takeIf { it.any { value -> value != null } }
    val mainColor = if (power != null) colors.power else colors.speed
    val heartRate = samples.heartRate.takeIf { showHeartRate && it.any { value -> value != null } }
    val tag = buildString {
        append("card-chart")
        if (power != null) append("-power") else if (main != null) append("-speed")
        if (heartRate != null) append("-hr")
    }
    Canvas(modifier.testTag(tag)) {
        main?.let { line(it, mainColor, 1.5.dp.toPx()) }
        heartRate?.let { line(it, colors.heartRate, 1.5.dp.toPx()) }
    }
}

private fun DrawScope.line(values: List<Double?>, color: Color, width: Float) {
    val highest = values.maxOf { it ?: 0.0 }
    if (highest <= 0 || values.size < 2) return
    val path = Path()
    var drawing = false
    values.forEachIndexed { index, value ->
        if (value == null) {
            drawing = false
            return@forEachIndexed
        }
        val x = size.width * index / (values.size - 1)
        val y = size.height * (1 - (value / highest).toFloat() * 0.92f)
        if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
        drawing = true
    }
    drawPath(path, color, style = Stroke(width))
}

/**
 * A bar per day with each ride of the day stacked by its own `trainingLoad`, scaled to the week's ridden TSS from the
 * backend: the stack is drawn, no total is computed on the device.
 */
@Composable
private fun WeekBars(card: WeekCard, fmt: Fmt, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val scale = (card.figures?.riddenTss ?: 0).toFloat()
    Column(modifier.testTag("card-days")) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val slot = size.width / 7
            val barWidth = slot * 0.55f
            for (day in 0 until 7) {
                val date = card.monday.plusDays(day.toLong()).toString()
                val left = slot * day + (slot - barWidth) / 2
                drawRect(track, Offset(left, size.height - 2f), Size(barWidth, 2f))
                if (scale <= 0) continue
                var bottom = size.height
                card.rides.filter { it.date == date }.forEach { ride ->
                    val height = size.height * ((ride.trainingLoad ?: 0.0).toFloat() / scale).coerceAtMost(1f)
                    drawRect(color, Offset(left, bottom - height), Size(barWidth, height))
                    bottom -= height + 2f
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (day in 0 until 7) {
                Text(
                    fmt.date(card.monday.plusDays(day.toLong()).toString(), DateStyle.WEEKDAY).substringBefore(','),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.extendedColors.textFaint,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
