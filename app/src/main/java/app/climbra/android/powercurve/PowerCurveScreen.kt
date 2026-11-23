package app.climbra.android.powercurve

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.R
import app.climbra.android.data.api.PowerCurvePoint
import app.climbra.android.ui.common.DetailBackButton
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors

/** The durations of the table, as on the web. */
val KEY_DURATIONS = listOf(5, 60, 300, 1200, 3600)

@Composable
fun PowerCurveRoute(
    onBack: () -> Unit,
    onOpenRide: (Long) -> Unit,
    viewModel: PowerCurveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }
    // Back online, or rides changed elsewhere while shown (spec 018)
    val dataVersion by viewModel.dataVersion.collectAsStateWithLifecycle()
    LaunchedEffect(dataVersion) { viewModel.onShown() }
    PowerCurveScreen(
        state = state,
        fmt = rememberFmt(),
        onBack = onBack,
        onSelect = viewModel::select,
        onRefresh = viewModel::refresh,
        onOpenRide = onOpenRide,
    )
}

/**
 * The power curve (spec 026), the web's `power-curve-page`: the period's best power over durations against the
 * period before, and the key durations with their rides. Every figure is the backend's (cyclingapp spec 061).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerCurveScreen(
    state: PowerCurveUiState,
    fmt: Fmt,
    onBack: () -> Unit,
    onSelect: (CurvePeriod) -> Unit,
    onRefresh: () -> Unit,
    onOpenRide: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.power_curve_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = { DetailBackButton(onBack, "power-curve-back") },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.power_curve_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Periods(state.period, onSelect)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("power-curve-loading"))
                state.error?.let { error ->
                    ErrorBanner(error.resolve(), Modifier.testTag("power-curve-error"))
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("power-curve-retry")) {
                        Text(stringResource(R.string.retry))
                    }
                }
                if (state.noRider) {
                    Note(stringResource(R.string.power_curve_empty), "power-curve-empty")
                }
                state.curve?.let { curve ->
                    curve.previous?.let { previous ->
                        Text(
                            stringResource(
                                R.string.power_curve_compared,
                                fmt.date(previous.from, DateStyle.DAY_MONTH_YEAR),
                                fmt.date(previous.to, DateStyle.DAY_MONTH_YEAR),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("power-curve-compared"),
                        )
                    }
                    if (curve.ridesWithoutCurve > 0) {
                        Note(
                            pluralStringResource(R.plurals.power_curve_pending, curve.ridesWithoutCurve, curve.ridesWithoutCurve),
                            "power-curve-pending",
                        )
                    }
                    if (curve.current.points.isEmpty()) {
                        Note(stringResource(R.string.power_curve_empty), "power-curve-empty")
                    } else {
                        BoxWithConstraints {
                            val chart = @Composable { modifier: Modifier ->
                                OutlinedCard(modifier) {
                                    Column(Modifier.padding(12.dp)) {
                                        PowerCurveChart(curve.current.points, curve.previous?.points, fmt)
                                        Legend(curve.previous != null)
                                    }
                                }
                            }
                            val table = @Composable { modifier: Modifier ->
                                KeyDurations(curve.current.points, curve.previous != null, fmt, onOpenRide, modifier)
                            }
                            if (maxWidth >= SIDE_BY_SIDE) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    chart(Modifier.weight(3f))
                                    table(Modifier.weight(2f))
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    chart(Modifier.fillMaxWidth())
                                    table(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Periods(selected: CurvePeriod, onSelect: (CurvePeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("power-curve-periods")) {
        CurvePeriod.entries.forEachIndexed { index, period ->
            SegmentedButton(
                selected = period == selected,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(index, CurvePeriod.entries.size),
                modifier = Modifier.testTag("period-${period.name}"),
            ) {
                Text(stringResource(period.label), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Legend(withPrevious: Boolean) {
    val colors = MaterialTheme.extendedColors
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
        LegendEntry(stringResource(R.string.power_curve_current), colors.power)
        if (withPrevious) LegendEntry(stringResource(R.string.power_curve_previous), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendEntry(text: String, color: Color) {
    Text("— $text", style = MaterialTheme.typography.labelSmall, color = color)
}

/** 5 s, 1, 5, 20 and 60 min: watts, W/kg, the backend's change and the ride's date, a tap opens the ride. */
@Composable
private fun KeyDurations(
    points: List<PowerCurvePoint>,
    withChange: Boolean,
    fmt: Fmt,
    onOpenRide: (Long) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.extendedColors
    OutlinedCard(modifier.testTag("power-curve-table")) {
        Column(Modifier.padding(vertical = 4.dp)) {
            KEY_DURATIONS.forEachIndexed { index, duration ->
                if (index > 0) HorizontalDivider()
                val point = points.firstOrNull { it.durationSeconds == duration }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                        .then(if (point != null) Modifier.clickable { onOpenRide(point.activityId) } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("key-$duration"),
                ) {
                    Text(fmt.shortDuration(duration), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        point?.let { "${fmt.num(it.watts)} W" } ?: Fmt.DASH,
                        fontFamily = MonoFamily,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).testTag("key-$duration-watts"),
                    )
                    Text(
                        point?.wattsPerKg?.let { stringResource(R.string.unit_watts_per_kg, fmt.num(it, 2)) } ?: Fmt.DASH,
                        fontFamily = MonoFamily,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.2f),
                    )
                    if (withChange) {
                        val change = point?.changeWatts
                        Text(
                            change?.let { (if (it > 0) "+" else "") + "${fmt.num(it)} W" } ?: Fmt.DASH,
                            fontFamily = MonoFamily,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                change == null || change == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                change > 0 -> colors.success
                                else -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.weight(1f).testTag("key-$duration-change"),
                        )
                    }
                    Text(
                        point?.let { fmt.date(it.date, DateStyle.DAY_MONTH) } ?: Fmt.DASH,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}

private val SIDE_BY_SIDE = 840.dp
