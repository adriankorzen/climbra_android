package app.climbra.android.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import app.climbra.android.dashboard.emptyHint
import app.climbra.android.intervals.SyncProgress
import app.climbra.android.intervals.SyncState
import app.climbra.android.data.api.RideRow
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun ActivitiesRoute(config: AppConfig, onOpenRide: (Long) -> Unit, viewModel: ActivitiesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }
    ActivitiesScreen(
        state = state,
        sync = sync,
        fmt = rememberFmt(),
        onPreset = viewModel::selectPreset,
        onCustom = viewModel::selectCustom,
        onTextChange = viewModel::onTextChange,
        onLoadMore = viewModel::loadMore,
        onRefresh = viewModel::refresh,
        onOpenRide = onOpenRide,
        onOpenWeb = { openWebPage(context, config.webPage("")) },
    )
}

/** The training log (spec 003), the phone form of the web's `activities-page`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(
    state: ActivitiesUiState,
    fmt: Fmt,
    onPreset: (RangePreset) -> Unit,
    onCustom: (LocalDate, LocalDate) -> Unit,
    onTextChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRide: (Long) -> Unit,
    onOpenWeb: () -> Unit,
    sync: SyncState = SyncState(),
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    // The next page when the last rows come into view
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - LOAD_MORE_AHEAD
        }
    }
    LaunchedEffect(nearEnd, state.rows.size) {
        if (nearEnd) onLoadMore()
    }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag("activities-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item(key = "header") {
                Text(
                    stringResource(R.string.nav_activities),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (sync.running) {
                item(key = "sync") { SyncProgress(sync, fmt) }
            }
            item(key = "filters") {
                Filters(state, fmt, onPreset, onTextChange, onPickCustom = { picking = true })
            }
            if (state.loading) {
                item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("activities-loading")) }
            }
            state.error?.let { error ->
                item(key = "error") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ErrorBanner(error.toUiText().resolve(), Modifier.testTag("activities-error"))
                        OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            when {
                state.noRider -> item(key = "no-rider") {
                    Hint(
                        stringResource(R.string.dashboard_no_rider_title),
                        stringResource(R.string.dashboard_no_rider_hint),
                        onOpenWeb,
                        "activities-no-rider",
                    )
                }
                !state.loading && !state.hasRides -> item(key = "empty-all") {
                    Hint(
                        stringResource(R.string.dashboard_empty_title),
                        emptyHint(syncConnected = sync.connected == true),
                        onOpenWeb,
                        "activities-empty-all",
                    )
                }
                else -> {
                    state.totals?.let { totals -> item(key = "totals") { Totals(totals, fmt) } }
                    if (!state.loading && state.rows.isEmpty() && state.error == null) {
                        item(key = "empty") {
                            Text(
                                stringResource(
                                    if (state.text.isBlank()) R.string.activities_empty else R.string.activities_empty_search,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("activities-empty"),
                            )
                        }
                    }
                    items(state.rows, key = { it.id }) { row -> RideListItem(row, fmt, onOpenRide) }
                    if (state.loadingMore) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp).testTag("activities-loading-more"))
                            }
                        }
                    }
                }
            }
        }
    }
    if (picking) {
        RangePickerDialog(
            initial = state.period,
            onDismiss = { picking = false },
            onConfirm = { from, to ->
                picking = false
                onCustom(from, to)
            },
        )
    }
}

@Composable
private fun Filters(
    state: ActivitiesUiState,
    fmt: Fmt,
    onPreset: (RangePreset) -> Unit,
    onTextChange: (String) -> Unit,
    onPickCustom: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            placeholder = { Text(stringResource(R.string.activities_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon = if (state.text.isNotEmpty()) {
                {
                    IconButton(onClick = { onTextChange("") }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.activities_clear_search))
                    }
                }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("activities-search"),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf(
                RangePreset.WEEK to R.string.range_7d,
                RangePreset.FOUR_WEEKS to R.string.range_28d,
                RangePreset.QUARTER to R.string.range_90d,
                RangePreset.YEAR to R.string.range_year,
            ).forEach { (preset, label) ->
                FilterChip(
                    selected = state.period.preset == preset,
                    onClick = { onPreset(preset) },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.testTag("preset-${preset.name.lowercase()}"),
                )
            }
            FilterChip(
                selected = state.period.preset == RangePreset.CUSTOM,
                onClick = onPickCustom,
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = null, Modifier.size(18.dp))
                },
                label = {
                    Text(
                        if (state.period.preset == RangePreset.CUSTOM) {
                            "${fmt.date(state.period.from.toString(), DateStyle.DAY_MONTH)} – " +
                                fmt.date(state.period.to.toString(), DateStyle.DAY_MONTH)
                        } else {
                            stringResource(R.string.range_custom)
                        },
                    )
                },
                modifier = Modifier.testTag("preset-custom"),
            )
        }
    }
}

@Composable
private fun Totals(totals: app.climbra.android.data.api.RideTotals, fmt: Fmt) {
    val label = stringResource(R.string.activities_totals)
    ElevatedCard(modifier = Modifier.fillMaxWidth().testTag("activities-totals").semantics { contentDescription = label }) {
        // Three per row: six figures in one row do not fit a phone
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Total(stringResource(R.string.activities_rides), fmt.num(totals.rides), Modifier.weight(1f))
                Total(stringResource(R.string.activities_time), fmt.hm(totals.seconds), Modifier.weight(1f))
                Total(stringResource(R.string.activities_distance), fmt.km(totals.meters, 0) + " km", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Total(stringResource(R.string.activities_elevation), fmt.num(totals.elevation) + " m", Modifier.weight(1f))
                Total(stringResource(R.string.activities_work), fmt.num(totals.kj) + " kJ", Modifier.weight(1f))
                Total("TSS", fmt.num(totals.tss), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Total(label: String, value: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, fontFamily = MonoFamily, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RideListItem(row: RideRow, fmt: Fmt, onOpenRide: (Long) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable { onOpenRide(row.id) }.testTag("ride-row-${row.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.name ?: stringResource(R.string.activity_unnamed),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        fmt.date(row.start, DateStyle.WEEKDAY),
                        fmt.duration(row.duration),
                        row.distance?.let { fmt.km(it) + " km" },
                        row.np?.let { stringResource(R.string.ride_np, fmt.num(it)) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (row.intensityFactor != null) {
                Surface(
                    color = intensityColor(row.intensityFactor).copy(alpha = 0.18f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.testTag("ride-if-${row.id}"),
                ) {
                    Text(
                        stringResource(R.string.ride_if, fmt.num(row.intensityFactor, 2)),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFamily,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(52.dp)) {
                Text(fmt.num(row.tss), style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily)
                if (row.tssFromHeartRate) {
                    val tooltip = stringResource(R.string.hr_tss_tooltip)
                    Text(
                        stringResource(R.string.hr_tss_marker),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.extendedColors.heartRate,
                        modifier = Modifier.testTag("ride-hr-${row.id}").semantics { contentDescription = tooltip },
                    )
                } else {
                    Text("TSS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint)
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(initial: Period, onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit) {
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.from.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        initialSelectedEndDateMillis = initial.to.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )
    fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = picker.selectedStartDateMillis
                    val end = picker.selectedEndDateMillis ?: start
                    if (start != null && end != null) onConfirm(day(start), day(end))
                },
                enabled = picker.selectedStartDateMillis != null,
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DateRangePicker(state = picker, title = { Text(stringResource(R.string.range_pick), Modifier.padding(16.dp)) })
    }
}

private const val LOAD_MORE_AHEAD = 5
