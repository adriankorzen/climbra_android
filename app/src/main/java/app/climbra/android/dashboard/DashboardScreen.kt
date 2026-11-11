package app.climbra.android.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.data.api.AnalysisSummary
import app.climbra.android.data.api.FormRange
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors

@Composable
fun DashboardRoute(config: AppConfig, onOpenRide: (Long) -> Unit, viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }
    DashboardScreen(
        state = state,
        fmt = rememberFmt(),
        onRefresh = viewModel::refresh,
        onSelectRange = viewModel::selectRange,
        onOpenWeb = { openWebPage(context, config.webPage("")) },
        onOpenRide = onOpenRide,
    )
}

/** The dashboard of the web app (`dashboard-page`), with the backend's figures (cyclingapp spec 051). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    fmt: Fmt,
    onRefresh: () -> Unit,
    onSelectRange: (Int) -> Unit,
    onOpenWeb: () -> Unit,
    onOpenRide: (Long) -> Unit = {},
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(
                    stringResource(R.string.nav_dashboard),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    fmt.date(state.load?.today, DateStyle.LONG).takeIf { state.load != null }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("dashboard-today"),
                )
            }
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("dashboard-loading"))
            }
            if (state.failed) {
                ErrorBanner(stringResource(R.string.dashboard_load_failed), Modifier.testTag("dashboard-error"))
                OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("dashboard-retry")) {
                    Text(stringResource(R.string.retry))
                }
            }
            when {
                state.noRider -> Hint(
                    title = stringResource(R.string.dashboard_no_rider_title),
                    text = stringResource(R.string.dashboard_no_rider_hint),
                    onOpenWeb = onOpenWeb,
                    tag = "dashboard-no-rider",
                )
                state.rider != null || state.failed -> Content(state, fmt, onSelectRange, onOpenWeb, onOpenRide)
            }
        }
    }
}

@Composable
private fun Content(
    state: DashboardUiState,
    fmt: Fmt,
    onSelectRange: (Int) -> Unit,
    onOpenWeb: () -> Unit,
    onOpenRide: (Long) -> Unit,
) {
    if (state.noRidesAtAll) {
        Hint(
            title = stringResource(R.string.dashboard_empty_title),
            text = stringResource(R.string.dashboard_empty_hint),
            onOpenWeb = onOpenWeb,
            tag = "dashboard-empty",
        )
    }
    BoxWithConstraints {
        val wide = maxWidth >= WIDE_LAYOUT
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tiles(state, fmt, columns = if (wide) 4 else 2)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PmcCard(state, fmt, onSelectRange, Modifier.weight(2f))
                    WeeklyCard(state, fmt, Modifier.weight(1f))
                }
            } else {
                PmcCard(state, fmt, onSelectRange)
                WeeklyCard(state, fmt)
            }
            RecentRides(state, fmt, onOpenRide)
        }
    }
}

@Composable
private fun Tiles(state: DashboardUiState, fmt: Fmt, columns: Int) {
    val load = state.load
    val current = load?.last7Days
    val changes = load?.changes
    val fitness = load?.fitness
    val colors = MaterialTheme.extendedColors
    val days = load?.days.orEmpty().takeLast(SPARK_DAYS)
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { modifier ->
            KpiTile(
                label = stringResource(R.string.dashboard_tss7),
                value = fmt.num(current?.tss),
                change = change(fmt, changes?.tss),
                spark = load?.weeks.orEmpty().map { it.tss },
                modifier = modifier,
                tag = "kpi-tss",
            )
        },
        { modifier ->
            KpiTile(
                label = stringResource(R.string.dashboard_hours7),
                value = fmt.hm(current?.seconds),
                unit = stringResource(R.string.unit_hours),
                change = change(fmt, changes?.seconds),
                modifier = modifier,
                tag = "kpi-hours",
            )
        },
        { modifier ->
            KpiTile(
                label = stringResource(R.string.dashboard_distance7),
                value = fmt.km(current?.meters, 0),
                unit = stringResource(R.string.unit_km),
                change = change(fmt, changes?.meters),
                modifier = modifier,
                tag = "kpi-distance",
            )
        },
        { modifier ->
            KpiTile(
                label = stringResource(R.string.dashboard_rides7),
                value = fmt.num(current?.rides),
                change = change(fmt, changes?.rides),
                modifier = modifier,
                tag = "kpi-rides",
            )
        },
        { modifier ->
            KpiTile(
                label = stringResource(R.string.dashboard_ftp),
                value = fmt.num(state.rider?.currentThresholds?.ftpWatts),
                unit = stringResource(R.string.unit_watts),
                hint = state.rider?.ftpWattsPerKg?.let { stringResource(R.string.unit_watts_per_kg, fmt.num(it, 2)) },
                modifier = modifier,
                tag = "kpi-ftp",
            )
        },
        { modifier ->
            KpiTile(
                label = stringResource(R.string.dashboard_ctl_long),
                value = fmt.num(fitness?.ctl),
                change = change(fmt, fitness?.ctlChange),
                tone = colors.speed,
                spark = days.map { it.ctl },
                modifier = modifier,
                tag = "kpi-ctl",
            )
        },
        { modifier ->
            KpiTile(
                label = stringResource(R.string.dashboard_atl_long),
                value = fmt.num(fitness?.atl),
                change = change(fmt, fitness?.atlChange),
                tone = colors.heartRate,
                spark = days.map { it.atl },
                modifier = modifier,
                tag = "kpi-atl",
            )
        },
        { modifier ->
            KpiTile(
                label = stringResource(R.string.dashboard_tsb_long),
                value = fmt.signed(fitness?.tsb),
                hint = fitness?.let { stringResource(formName(it.form)) },
                tone = fitness?.let { formColor(it.form) },
                modifier = modifier,
                tag = "kpi-tsb",
            )
        },
    )
    val description = stringResource(R.string.dashboard_kpis)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        tiles.chunked(columns).forEach { row ->
            // Tiles of a row are as tall as the tallest one
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.height(IntrinsicSize.Min),
            ) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The text of a change and whether it is a rise (`null` for no change), or nothing to show. */
private fun change(fmt: Fmt, fraction: Double?): Pair<String, Boolean?>? =
    fmt.change(fraction)?.let { it to fraction?.let { value -> if (value == 0.0) null else value > 0 } }

internal fun formName(form: FormRange): Int = when (form) {
    FormRange.TRANSITION -> R.string.dashboard_form_transition
    FormRange.FRESH -> R.string.dashboard_form_fresh
    FormRange.NEUTRAL -> R.string.dashboard_form_neutral
    FormRange.OPTIMAL -> R.string.dashboard_form_optimal
    FormRange.OVERLOAD -> R.string.dashboard_form_overload
}

/** As `FORM_COLORS` of the web dashboard. */
@Composable
internal fun formColor(form: FormRange): Color = when (form) {
    FormRange.TRANSITION -> MaterialTheme.extendedColors.powerZones[3]
    FormRange.FRESH -> MaterialTheme.extendedColors.success
    FormRange.NEUTRAL -> MaterialTheme.extendedColors.textFaint
    FormRange.OPTIMAL -> MaterialTheme.extendedColors.info
    FormRange.OVERLOAD -> MaterialTheme.colorScheme.error
}

@Composable
private fun PmcCard(state: DashboardUiState, fmt: Fmt, onSelectRange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.dashboard_pmc)
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(title)
            val rangeLabel = stringResource(R.string.dashboard_range)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = rangeLabel },
            ) {
                PMC_RANGES.forEachIndexed { index, days ->
                    SegmentedButton(
                        selected = state.range == days,
                        onClick = { onSelectRange(days) },
                        shape = SegmentedButtonDefaults.itemShape(index, PMC_RANGES.size),
                        icon = {},
                        modifier = Modifier.testTag("range-$days"),
                    ) {
                        Text(stringResource(R.string.dashboard_days, days), maxLines = 1)
                    }
                }
            }
            Legend(
                stringResource(R.string.dashboard_ctl) to MaterialTheme.extendedColors.speed,
                stringResource(R.string.dashboard_atl) to MaterialTheme.extendedColors.heartRate,
                stringResource(R.string.dashboard_tsb) to MaterialTheme.extendedColors.powerZones[3],
                stringResource(R.string.chart_tss) to MaterialTheme.extendedColors.altitude,
            )
            PmcChart(state.visibleDays, fmt, description = title)
        }
    }
}

@Composable
private fun WeeklyCard(state: DashboardUiState, fmt: Fmt, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.dashboard_weekly)
    val weeks = state.load?.weeks.orEmpty()
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle(title, Modifier.weight(1f))
                Text(
                    pluralStringResource(R.plurals.dashboard_weeks, weeks.size, weeks.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.extendedColors.textFaint,
                )
            }
            Legend(
                stringResource(R.string.chart_tss) to MaterialTheme.colorScheme.primary,
                stringResource(R.string.dashboard_hours) to MaterialTheme.extendedColors.speed,
            )
            WeeklyChart(weeks, fmt, description = title)
        }
    }
}

@Composable
private fun RecentRides(state: DashboardUiState, fmt: Fmt, onOpenRide: (Long) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            CardTitle(stringResource(R.string.dashboard_recent_rides))
            if (state.rides.isEmpty() && !state.loading) {
                Text(
                    stringResource(R.string.dashboard_no_rides),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp).testTag("rides-none"),
                )
            }
            state.rides.forEachIndexed { index, ride ->
                if (index > 0) HorizontalDivider()
                RideRow(ride, fmt, onOpenRide)
            }
        }
    }
}

@Composable
private fun RideRow(ride: AnalysisSummary, fmt: Fmt, onOpenRide: (Long) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenRide(ride.activityId) }
            .padding(vertical = 10.dp)
            .testTag("ride-${ride.activityId}"),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ride.name ?: stringResource(R.string.activity_unnamed),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    fmt.date(ride.startTimeLocal ?: ride.startTime, DateStyle.WEEKDAY),
                    fmt.duration(ride.durationSeconds),
                    stringResource(R.string.ride_np, fmt.num(ride.normalizedPower)),
                    stringResource(R.string.ride_if, fmt.num(ride.intensityFactor, 2)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                fmt.num(ride.trainingLoad),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = MonoFamily,
            )
            if (ride.loadFromHeartRate) {
                val tooltip = stringResource(R.string.hr_tss_tooltip)
                Surface(
                    color = MaterialTheme.extendedColors.heartRate.copy(alpha = 0.15f),
                    contentColor = MaterialTheme.extendedColors.heartRate,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.testTag("hr-marker-${ride.activityId}")
                        .semantics { contentDescription = tooltip },
                ) {
                    Text(
                        stringResource(R.string.hr_tss_marker),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.chart_tss),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.extendedColors.textFaint,
                )
            }
        }
    }
}

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

@Composable
private fun Legend(vararg entries: Pair<String, Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(color = color, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.width(10.dp)) {
                    Spacer(Modifier.padding(vertical = 5.dp))
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Hint(title: String, text: String, onOpenWeb: () -> Unit, tag: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpenWeb) { Text(stringResource(R.string.dashboard_open_web)) }
        }
    }
}

private val WIDE_LAYOUT = 600.dp
private const val SPARK_DAYS = 42
