package app.climbra.android.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.activities.complianceColor
import app.climbra.android.activities.complianceName
import app.climbra.android.activities.sportName
import app.climbra.android.activities.workoutTypeName
import app.climbra.android.data.api.CalendarRide
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.WorkoutComparison
import app.climbra.android.data.api.WorkoutStep
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors

@Composable
fun WorkoutDetailRoute(
    config: AppConfig,
    onBack: () -> Unit,
    onOpenRide: (Long) -> Unit,
    viewModel: WorkoutDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    WorkoutDetailScreen(
        state = state,
        fmt = rememberFmt(),
        actions = WorkoutActions(
            onBack = onBack,
            onRetry = viewModel::retry,
            onOpenRide = onOpenRide,
            onLink = viewModel::link,
            onUnlink = viewModel::unlink,
            onSkip = viewModel::skip,
            onSendToGarmin = viewModel::sendToGarmin,
            onCloseGarminDialog = viewModel::closeGarminDialog,
            onConnectGarmin = { openWebPage(context, config.webPage("profile")) },
            onDownloadZwift = viewModel::downloadZwift,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

data class WorkoutActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenRide: (Long) -> Unit = {},
    val onLink: (Long) -> Unit = {},
    val onUnlink: () -> Unit = {},
    val onSkip: (Boolean) -> Unit = {},
    val onSendToGarmin: () -> Unit = {},
    val onCloseGarminDialog: () -> Unit = {},
    val onConnectGarmin: () -> Unit = {},
    val onDownloadZwift: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/** A planned workout in detail (spec 005, the web's `workout-panel` with `workout-detail`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(state: WorkoutDetailUiState, fmt: Fmt, actions: WorkoutActions) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it, fmt) }
    LaunchedEffect(state.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onMessageShown()
        }
    }
    val workout = state.workout
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workout?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, modifier = Modifier.testTag("workout-back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
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
            if (state.loading || state.busy || state.sending || state.downloading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("workout-progress"))
            }
            when {
                state.error != null -> {
                    ErrorBanner(stringResource(R.string.workout_load_failed), Modifier.testTag("workout-error"))
                    OutlinedButton(onClick = actions.onRetry) { Text(stringResource(R.string.retry)) }
                }
                state.notFound -> ErrorBanner(stringResource(R.string.workout_not_found), Modifier.testTag("workout-not-found"))
                workout != null -> WorkoutContent(state, workout, fmt, actions)
            }
        }
    }
    state.connectingGarmin?.let { status ->
        GarminConnectDialog(
            status,
            onConnect = {
                actions.onCloseGarminDialog()
                actions.onConnectGarmin()
            },
            onDismiss = actions.onCloseGarminDialog,
        )
    }
}

@Composable
private fun WorkoutContent(state: WorkoutDetailUiState, workout: CalendarWorkout, fmt: Fmt, actions: WorkoutActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Badge(workoutTypeName(workout.type))
        Text(fmt.date(workout.date, DateStyle.LONG), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (workout.indoor) Badge(stringResource(R.string.workout_indoor))
    }
    if (!workout.rest) {
        Adherence(state, workout, fmt, actions)
        GarminAndZwift(state, workout, actions)
    }
    Row(Modifier.fillMaxWidth().testTag("workout-figures")) {
        Figure(stringResource(R.string.activity_duration), fmt.hm(workout.plannedDurationMinutes * 60), Modifier.weight(1f))
        Figure(stringResource(R.string.adherence_tss), fmt.num(workout.plannedTss), Modifier.weight(1f))
        Figure(stringResource(R.string.adherence_if), fmt.num(workout.plannedIf, 2), Modifier.weight(1f))
        Figure(stringResource(R.string.workout_target_rpe), fmt.num(workout.targetRpe), Modifier.weight(1f))
    }
    workout.description?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("workout-description"))
    }
    if (workout.blocks.any { it.steps.isNotEmpty() }) {
        Text(stringResource(R.string.workout_profile), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        StepChart(workout, Modifier.fillMaxWidth().height(140.dp))
        Steps(workout, fmt)
        if (state.noFtp) {
            Text(stringResource(R.string.workout_no_ftp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint, modifier = Modifier.testTag("workout-no-ftp"))
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Adherence(state: WorkoutDetailUiState, workout: CalendarWorkout, fmt: Fmt, actions: WorkoutActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                workoutStatusName(workout.status),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(workout),
                modifier = Modifier.testTag("workout-status"),
            )
            if (workout.activityId != null) {
                val ride = state.linkedRide
                Row(
                    Modifier.clickable { actions.onOpenRide(workout.activityId) }.testTag("linked-ride"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_directions_bike), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        ride?.let { it.name ?: sportName(it.sportType) } ?: stringResource(R.string.adherence_ride),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                workout.matchSource?.let {
                    Text(
                        stringResource(if (it == "MANUAL") R.string.adherence_match_manual else R.string.adherence_match_auto),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.extendedColors.textFaint,
                    )
                }
            }
        }
        workout.comparison?.let { Comparison(it, fmt) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                workout.activityId != null -> OutlinedButton(onClick = actions.onUnlink, enabled = !state.busy, modifier = Modifier.testTag("workout-unlink")) {
                    Icon(painterResource(R.drawable.ic_link_off), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.adherence_unlink))
                }
                workout.status == CalendarWorkout.STATUS_SKIPPED -> OutlinedButton(onClick = { actions.onSkip(false) }, enabled = !state.busy, modifier = Modifier.testTag("workout-unskip")) {
                    Text(stringResource(R.string.adherence_undo_skip))
                }
                else -> OutlinedButton(onClick = { actions.onSkip(true) }, enabled = !state.busy, modifier = Modifier.testTag("workout-skip")) {
                    Icon(painterResource(R.drawable.ic_event_busy), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.adherence_skip))
                }
            }
        }
        if (state.candidates.isNotEmpty()) LinkAnother(state.candidates, state.busy, fmt, actions.onLink)
        state.actionError?.let {
            Text(it.toUiText().resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("workout-action-error"))
        }
    }
}

@Composable
private fun Comparison(comparison: WorkoutComparison, fmt: Fmt) {
    Column(Modifier.testTag("workout-comparison"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(complianceName(comparison.level), color = complianceColor(comparison.level), style = MaterialTheme.typography.labelLarge)
        comparison.duration?.let { ComparisonRow(stringResource(R.string.activity_duration), fmt.hm(it.planned), fmt.hm(it.actual), fmt.pct(it.ratio), complianceColor(it.level)) }
        comparison.tss?.let { ComparisonRow(stringResource(R.string.adherence_tss), fmt.num(it.planned), fmt.num(it.actual), fmt.pct(it.ratio), complianceColor(it.level)) }
        comparison.intensityFactor?.let { ComparisonRow(stringResource(R.string.adherence_if), fmt.num(it.planned, 2), fmt.num(it.actual, 2), fmt.signed(it.delta, 2), complianceColor(it.level)) }
        comparison.intervals?.let { ComparisonRow(stringResource(R.string.adherence_intervals), fmt.num(it.plannedRepetitions), fmt.num(it.detectedBlocks), fmt.pct(it.ratio), complianceColor(it.level)) }
    }
}

@Composable
private fun ComparisonRow(label: String, planned: String, actual: String, deviation: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${stringResource(R.string.adherence_planned)} $planned", style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, modifier = Modifier.width(88.dp))
        Text("${stringResource(R.string.adherence_ridden)} $actual", style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, modifier = Modifier.width(88.dp))
        Text(deviation, style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, color = color, modifier = Modifier.width(52.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkAnother(candidates: List<CalendarRide>, busy: Boolean, fmt: Fmt, onLink: (Long) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val chosen = candidates.firstOrNull { it.activityId == selected }
    val label: @Composable (CalendarRide) -> String = { "${fmt.date(it.date, DateStyle.WEEKDAY)} · ${it.name ?: sportName(it.sportType)}" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && !busy }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = chosen?.let { label(it) } ?: Fmt.DASH,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.adherence_link_another)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("link-ride"),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                candidates.forEach { ride ->
                    DropdownMenuItem(
                        text = { Text(label(ride)) },
                        onClick = {
                            selected = ride.activityId
                            expanded = false
                        },
                        modifier = Modifier.testTag("link-option-${ride.activityId}"),
                    )
                }
            }
        }
        Button(
            onClick = { selected?.let(onLink); selected = null },
            enabled = selected != null && !busy,
            modifier = Modifier.testTag("link-submit"),
        ) {
            Icon(painterResource(R.drawable.ic_link), null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.adherence_link))
        }
    }
}

@Composable
private fun GarminAndZwift(state: WorkoutDetailUiState, workout: CalendarWorkout, actions: WorkoutActions) {
    val status = state.garminStatus
    if (status == null && !workout.hasSteps) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status != null && status != "NOT_SENT") {
            Text(
                stringResource(if (status == "SENT") R.string.garmin_workout_status_sent else R.string.garmin_workout_status_outdated),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extendedColors.textFaint,
                modifier = Modifier.testTag("garmin-status"),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (status != null) {
                OutlinedButton(onClick = actions.onSendToGarmin, enabled = !state.sending, modifier = Modifier.testTag("garmin-send")) {
                    Icon(painterResource(R.drawable.ic_send), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (status == "SENT") R.string.garmin_send_again else R.string.garmin_send_workout))
                }
            }
            if (workout.hasSteps) {
                OutlinedButton(onClick = actions.onDownloadZwift, enabled = !state.downloading, modifier = Modifier.testTag("zwift-download")) {
                    Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.zwift_download))
                }
            }
        }
        if (workout.hasSteps) {
            Text(stringResource(R.string.zwift_import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint)
        }
    }
}

/** The power zone color of a step, from the backend's zone (cyclingapp spec 054). */
@Composable
private fun zoneColor(step: WorkoutStep): Color {
    val zones = MaterialTheme.extendedColors.powerZones
    return step.zone?.let { zones.getOrNull(it - 1) } ?: MaterialTheme.colorScheme.outlineVariant
}

/** The step profile: each step as wide as its duration and as high as its upper target, in its zone color. */
@Composable
private fun StepChart(workout: CalendarWorkout, modifier: Modifier = Modifier) {
    val steps = workout.blocks.flatMap { block -> List(block.repetitions.coerceAtLeast(1)) { block.steps }.flatten() }
    val colors = steps.map { zoneColor(it) }
    val axis = MaterialTheme.extendedColors.chartAxis
    val description = stringResource(R.string.workout_profile)
    val total = steps.sumOf { it.durationSeconds }.coerceAtLeast(1)
    val top = (steps.maxOfOrNull { it.powerHighPctFtp } ?: 100).coerceAtLeast(100) * 1.1f
    Canvas(modifier.testTag("workout-chart").semantics { contentDescription = description }) {
        var x = 0f
        steps.forEachIndexed { index, step ->
            val width = size.width * step.durationSeconds / total
            val height = size.height * step.powerHighPctFtp / top
            drawRect(colors[index].copy(alpha = 0.8f), topLeft = Offset(x, size.height - height), size = Size(width, height))
            x += width
        }
        // The FTP line
        val ftp = size.height - size.height * 100f / top
        drawLine(axis, Offset(0f, ftp), Offset(size.width, ftp), strokeWidth = 1.dp.toPx())
    }
}

/** The steps with their repetitions, % FTP, the watts of the current FTP and the cadence. */
@Composable
private fun Steps(workout: CalendarWorkout, fmt: Fmt) {
    Column(Modifier.fillMaxWidth().testTag("workout-steps")) {
        Row(Modifier.padding(vertical = 4.dp)) {
            HeaderCell(stringResource(R.string.workout_step), Modifier.weight(1.6f))
            HeaderCell(stringResource(R.string.activity_duration), Modifier.weight(1f))
            HeaderCell("% FTP", Modifier.weight(1f))
            HeaderCell(stringResource(R.string.workout_watts), Modifier.weight(1.1f))
            HeaderCell(stringResource(R.string.workout_cadence), Modifier.weight(0.9f))
        }
        HorizontalDivider()
        workout.blocks.forEachIndexed { blockIndex, block ->
            val repeated = block.repetitions > 1
            if (repeated) {
                Text(
                    stringResource(R.string.workout_repeat, block.repetitions),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp).testTag("repeat-$blockIndex"),
                )
            }
            block.steps.forEachIndexed { stepIndex, step ->
                Row(
                    Modifier.fillMaxWidth().padding(start = if (repeated) 12.dp else 0.dp, top = 4.dp, bottom = 4.dp).testTag("step-$blockIndex-$stepIndex"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1.6f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(zoneColor(step), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(stepKindName(step.kind), style = MaterialTheme.typography.bodySmall)
                            step.note?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint)
                            }
                        }
                    }
                    Cell(fmt.duration(step.durationSeconds), Modifier.weight(1f))
                    Cell(range(step.powerLowPctFtp, step.powerHighPctFtp), Modifier.weight(1f))
                    Cell(
                        if (step.powerLowWatts != null && step.powerHighWatts != null) "${range(step.powerLowWatts, step.powerHighWatts)} W" else Fmt.DASH,
                        Modifier.weight(1.1f).testTag("step-watts-$blockIndex-$stepIndex"),
                    )
                    Cell(step.cadenceLow?.let { range(it, step.cadenceHigh ?: it) } ?: Fmt.DASH, Modifier.weight(0.9f))
                }
            }
        }
    }
}

private fun range(low: Int, high: Int): String = if (low == high) "$low" else "$low–$high"

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Cell(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, modifier = modifier)
}
