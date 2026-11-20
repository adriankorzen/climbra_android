package app.climbra.android.activities

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.R
import app.climbra.android.coach.AskCoachSheet
import app.climbra.android.coach.AskCoachState
import app.climbra.android.coach.AskCoachViewModel
import app.climbra.android.coach.RideCoachActions
import app.climbra.android.coach.RideCoachButton
import app.climbra.android.coach.RideCoachCard
import app.climbra.android.dashboard.KpiTile
import app.climbra.android.data.api.AnalysisResponse
import app.climbra.android.ui.common.DetailBackButton
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.extendedColors

@Composable
fun RideDetailRoute(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onOpenCalendar: (String) -> Unit = {},
    /** A coach conversation, on the Coach tab (spec 006). */
    onOpenConversation: (Long) -> Unit = {},
    onOpenTerms: () -> Unit = {},
    viewModel: RideDetailViewModel = hiltViewModel(),
    coachViewModel: AskCoachViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coach by coachViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(coach.open) {
        coach.open?.let {
            coachViewModel.opened()
            onOpenConversation(it)
        }
    }
    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }
    RideDetailScreen(
        state = state,
        fmt = rememberFmt(),
        onBack = onBack,
        onRetry = viewModel::retry,
        onReanalyse = viewModel::reanalyze,
        onDownload = viewModel::download,
        onDelete = viewModel::delete,
        onIntervalSource = viewModel::setIntervalSource,
        onFormChange = viewModel::onFormChange,
        onApplyDetection = viewModel::applyDetection,
        onResetDetection = viewModel::resetDetection,
        onLink = viewModel::link,
        onMessageShown = viewModel::messageShown,
        onOpenCalendar = onOpenCalendar,
        coach = coach,
        coachActions = RideCoachActions(
            onAsk = coachViewModel::openSheet,
            onCloseSheet = coachViewModel::closeSheet,
            onChange = coachViewModel::update,
            onStart = { coachViewModel.start(free = state.activity?.freeReviewAvailable == true) },
            onOpenConversation = onOpenConversation,
            onOpenTerms = onOpenTerms,
        ),
    )
}

/** The web's `activity-detail-page` (spec 003), with its AI coach parts (spec 006). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(
    state: RideDetailUiState,
    fmt: Fmt,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onReanalyse: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onIntervalSource: (Boolean) -> Unit,
    onFormChange: (DetectionForm) -> Unit,
    onApplyDetection: () -> Unit,
    onResetDetection: () -> Unit,
    onLink: (Long) -> Unit,
    onMessageShown: () -> Unit,
    /** The calendar at the week of the planned workout's ISO date (spec 005). */
    onOpenCalendar: (String) -> Unit = {},
    coach: AskCoachState = AskCoachState(),
    coachActions: RideCoachActions = RideCoachActions(),
) {
    var menu by remember { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val messageText = when (val message = state.message) {
        is RideMessage.Saved -> stringResource(R.string.activity_saved_to_downloads, message.fileName)
        is RideMessage.Failed -> message.error.toUiText().resolve()
        RideMessage.SaveFailed -> stringResource(R.string.activity_save_failed)
        null -> null
    }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            onMessageShown()
        }
    }

    val activity = state.activity
    val title = activity?.name ?: stringResource(R.string.activity_unnamed)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (activity != null) title else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { DetailBackButton(onBack, "ride-back") },
                actions = {
                    if (activity != null) {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("ride-menu")) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_actions))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (state.analysing) R.string.activity_analysing else R.string.activity_reanalyse)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), null) },
                                enabled = !state.analysing,
                                onClick = {
                                    menu = false
                                    onReanalyse()
                                },
                                modifier = Modifier.testTag("action-reanalyse"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.activity_download_fit)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_download), null) },
                                enabled = !state.downloading,
                                onClick = {
                                    menu = false
                                    onDownload()
                                },
                                modifier = Modifier.testTag("action-download"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.activity_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error) },
                                enabled = !state.deleting,
                                onClick = {
                                    menu = false
                                    confirmingDelete = true
                                },
                                modifier = Modifier.testTag("action-delete"),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading || state.analysing || state.deleting || state.downloading || state.linking) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("ride-progress"))
            }
            when {
                state.notFound -> ErrorBanner(stringResource(R.string.activity_not_found), Modifier.testTag("ride-not-found"))
                state.error != null -> {
                    ErrorBanner(stringResource(R.string.activity_load_failed), Modifier.testTag("ride-error"))
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                activity != null -> {
                    RideContent(state, fmt, coach, coachActions, onIntervalSource, onFormChange, onApplyDetection, onResetDetection, onLink, onOpenCalendar)
                    RideCoachCard(coach, fmt, coachActions)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (coach.sheetOpen && activity != null) {
        AskCoachSheet(coach, activity.freeReviewAvailable, fmt, coachActions)
    }
    if (confirmingDelete && activity != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.activity_delete_title)) },
            text = {
                Text(stringResource(R.string.activity_delete_confirm, title, fmt.date(activity.startTimeLocal ?: activity.startTime, DateStyle.LONG)))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("confirm-delete"),
                ) { Text(stringResource(R.string.activity_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RideContent(
    state: RideDetailUiState,
    fmt: Fmt,
    coach: AskCoachState,
    coachActions: RideCoachActions,
    onIntervalSource: (Boolean) -> Unit,
    onFormChange: (DetectionForm) -> Unit,
    onApplyDetection: () -> Unit,
    onResetDetection: () -> Unit,
    onLink: (Long) -> Unit,
    onOpenCalendar: (String) -> Unit,
) {
    val activity = state.activity!!
    val start = activity.startTimeLocal ?: activity.startTime
    Text(
        listOfNotNull(
            fmt.date(start, DateStyle.LONG) + ", " + start.substring(11, 16),
            sportName(activity.sportType),
            sourceName(activity.source),
            activity.deviceName,
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("ride-subtitle"),
    )
    RideCoachButton(coach, coachActions)
    PlannedWorkoutCard(activity.plannedWorkout, state.candidates, state.linking, state.linkError, fmt, onLink, onOpenCalendar)
    val result = state.analysis
    if (state.analysisFailed && result == null) {
        ErrorBanner(stringResource(R.string.activity_no_analysis), Modifier.testTag("ride-no-analysis"))
    }
    if (result == null) return
    val analysis = result.analysis
    Figures(result, state, fmt)
    if (analysis.power == null) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.activity_no_power), Modifier.padding(12.dp).testTag("ride-no-power"), style = MaterialTheme.typography.bodyMedium)
        }
    }
    val intervals = analysis.intervals
    val blocks = intervals?.blocks.orEmpty()
    val gaps = intervals?.unders.orEmpty() + intervals?.recoveries.orEmpty()
    val recording = state.recording
    val duration = recording?.let { (it.samples.t.lastOrNull() ?: 0) + it.resolutionSeconds.toDouble() } ?: analysis.durationSeconds.toDouble()
    var window by remember(recording) { mutableStateOf(ChartWindow(0.0, duration)) }
    val zoomTo: (app.climbra.android.data.api.IntervalBlock) -> Unit = { window = windowAround(it, duration) }

    // The interval source is switched in the intervals card only: twice on a phone screen is noise
    SectionCard(stringResource(R.string.activity_ride_chart), tag = "ride-chart-card") {
        when {
            recording != null -> RideChart(recording, blocks, gaps, window, { window = it }, fmt)
            state.recordingLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Muted(stringResource(R.string.activity_no_recording), Modifier.testTag("ride-no-recording"))
        }
    }
    BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        val zones: @Composable (Modifier) -> Unit = { modifier ->
            SectionCard(stringResource(R.string.activity_power_zones), modifier, tag = "power-zones") {
                if (analysis.powerZones.isNotEmpty()) {
                    ZoneBars(analysis.powerZones, MaterialTheme.extendedColors.powerZones, fmt, "power-zone-bars", ZoneKind.POWER)
                } else {
                    Muted(stringResource(if (analysis.power != null) R.string.activity_set_ftp else R.string.activity_no_power_data))
                }
            }
        }
        val hrZones: @Composable (Modifier) -> Unit = { modifier ->
            SectionCard(stringResource(R.string.activity_hr_zones), modifier, tag = "hr-zones") {
                if (analysis.heartRateZones.isNotEmpty()) {
                    ZoneBars(
                        analysis.heartRateZones,
                        MaterialTheme.extendedColors.heartRateZones,
                        fmt,
                        "hr-zone-bars",
                        if (analysis.heartRateZoneBasis == "MAX_HR") ZoneKind.HEART_RATE_MAX else ZoneKind.HEART_RATE,
                    )
                    if (analysis.heartRateZoneBasis == "MAX_HR") {
                        Muted(stringResource(R.string.activity_hr_zones_from_max_hr), Modifier.testTag("hr-zones-from-max"))
                    }
                } else {
                    Muted(stringResource(if (analysis.heartRate != null) R.string.activity_set_lthr else R.string.activity_no_hr_data))
                }
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                zones(Modifier.weight(1f))
                hrZones(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                zones(Modifier)
                hrZones(Modifier)
            }
        }
    }
    if (analysis.power != null) {
        SectionCard(stringResource(R.string.activity_intervals), tag = "intervals") {
            if (!result.stored) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                    Text(
                        stringResource(R.string.activity_not_stored),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).testTag("not-stored"),
                    )
                }
            }
            if (intervals?.lapsAvailable == true) {
                IntervalSourceSwitch(intervals.useLaps, enabled = !state.analysing, onChange = onIntervalSource)
            }
            DetectionFormSection(
                form = state.form,
                invalid = state.formInvalid,
                adHoc = !result.stored,
                busy = state.analysing,
                onChange = onFormChange,
                onApply = onApplyDetection,
                onReset = onResetDetection,
            )
            if (intervals != null && intervals.detected) {
                IntervalSummary(intervals, fmt)
                if (intervals.sets.isNotEmpty()) SetList(intervals.sets, fmt, zoomTo)
                BlockList(intervalRows(intervals), fmt, zoomTo)
            } else {
                Muted(stringResource(R.string.activity_no_intervals), Modifier.testTag("no-intervals"))
            }
        }
    }
}

@Composable
private fun Figures(result: AnalysisResponse, state: RideDetailUiState, fmt: Fmt) {
    val analysis = result.analysis
    val activity = state.activity!!
    val power = analysis.power
    val thresholds = result.thresholds
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        add { KpiTile(stringResource(R.string.activity_duration), fmt.duration(analysis.durationSeconds), it, tag = "kpi-duration") }
        add { KpiTile(stringResource(R.string.activities_distance), fmt.km(analysis.distanceMeters ?: activity.distanceMeters), it, unit = "km", tag = "kpi-distance") }
        add { KpiTile(stringResource(R.string.activities_elevation), fmt.num(activity.elevationGainMeters), it, unit = "m", tag = "kpi-elevation") }
        if (power?.trainingStressScore != null || analysis.heartRate?.stressScore == null) {
            add { KpiTile("TSS", fmt.num(power?.trainingStressScore), it, tag = "kpi-tss") }
        }
        analysis.heartRate?.stressScore?.let { score ->
            add { KpiTile("hrTSS", fmt.num(score), it, hint = stringResource(R.string.hr_tss_tooltip), tag = "kpi-hr-tss") }
        }
        add { KpiTile("IF", fmt.num(power?.intensityFactor, 2), it, tag = "kpi-if") }
        add { KpiTile("NP", fmt.num(power?.normalizedPower), it, unit = "W", tag = "kpi-np") }
        add {
            KpiTile(
                stringResource(R.string.activity_avg_power),
                fmt.num(power?.averagePower),
                it,
                unit = "W",
                hint = power?.let { p -> stringResource(R.string.activity_max_value, fmt.num(p.maxPower) + " W") },
                tag = "kpi-avg-power",
            )
        }
        add { KpiTile("VI", fmt.num(power?.variabilityIndex, 2), it, tag = "kpi-vi") }
        add { KpiTile(stringResource(R.string.activities_work), fmt.num(power?.workKilojoules), it, unit = "kJ", tag = "kpi-work") }
        add {
            KpiTile(
                stringResource(R.string.activity_hr),
                fmt.num(analysis.heartRate?.average),
                it,
                unit = "bpm",
                hint = analysis.heartRate?.let { hr -> stringResource(R.string.activity_max_value, fmt.num(hr.max) + " bpm") },
                tag = "kpi-hr",
            )
        }
        add { KpiTile(stringResource(R.string.activity_cadence), fmt.num(analysis.averageCadence), it, unit = "rpm", tag = "kpi-cadence") }
        add {
            KpiTile(
                stringResource(R.string.activity_thresholds),
                fmt.num(thresholds.ftpWatts),
                it,
                unit = "W FTP",
                hint = thresholds.effectiveFrom?.let { from ->
                    stringResource(R.string.activity_thresholds_since, fmt.num(thresholds.lthrBpm), fmt.num(thresholds.maxHeartRateBpm), fmt.date(from, DateStyle.DAY_MONTH_YEAR))
                } ?: stringResource(R.string.activity_thresholds_hint, fmt.num(thresholds.lthrBpm), fmt.num(thresholds.maxHeartRateBpm)),
                tag = "kpi-thresholds",
            )
        }
    }
    BoxWithConstraints {
        val columns = if (maxWidth >= 600.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
