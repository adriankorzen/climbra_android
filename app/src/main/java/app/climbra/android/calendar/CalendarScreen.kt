package app.climbra.android.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.coach.AdjustmentArgs
import app.climbra.android.activities.complianceColor
import app.climbra.android.activities.sportName
import app.climbra.android.activities.workoutTypeName
import app.climbra.android.data.api.CalendarRide
import app.climbra.android.data.api.CalendarWeek
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.ComplianceLevel
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.api.TrainingPlan
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
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.TextStyle

@Composable
fun CalendarRoute(
    config: AppConfig,
    onOpenRide: (Long) -> Unit,
    onOpenWorkout: (CalendarWorkout) -> Unit,
    /** A coach conversation and the training block form, on the Coach tab (spec 006). */
    onOpenConversation: (Long) -> Unit = {},
    onNewTrainingBlock: () -> Unit = {},
    /** The adjustment form (spec 008) and a proposal on its conversation's proposal tab. */
    onAdjust: (AdjustmentArgs) -> Unit = {},
    onOpenProposal: (Long) -> Unit = {},
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }
    CalendarScreen(
        state = state,
        fmt = rememberFmt(),
        actions = CalendarActions(
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onToday = viewModel::showToday,
            onShowWeek = viewModel::showWeek,
            onRefresh = viewModel::refresh,
            onRetry = viewModel::retry,
            onOpenRide = onOpenRide,
            onOpenWorkout = onOpenWorkout,
            onAskRemove = viewModel::askRemove,
            onCancelRemove = viewModel::cancelRemove,
            onConfirmRemove = viewModel::confirmRemove,
            onDismissSuggestion = viewModel::dismissSuggestion,
            onOpenWeb = { path -> openWebPage(context, config.webPage(path)) },
            onImportGarmin = viewModel::sendPlanToGarmin,
            onCloseGarminDialog = viewModel::closeGarminDialog,
            onMessageShown = viewModel::messageShown,
            onOpenConversation = onOpenConversation,
            onNewTrainingBlock = onNewTrainingBlock,
            onAdjust = { plan, suggestion -> onAdjust(AdjustmentArgs(plan.id, plan.endDate.take(10), suggestion = suggestion)) },
            onOpenProposal = onOpenProposal,
            onMode = viewModel::setMode,
            onDefaultMode = viewModel::defaultMode,
            onShowMonth = viewModel::showMonth,
            onOpenWeekOf = viewModel::openWeekOf,
        ),
    )
}

/** What the calendar screen can ask for; one holder keeps the screen's signature readable. */
data class CalendarActions(
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onToday: () -> Unit = {},
    val onShowWeek: (LocalDate) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenRide: (Long) -> Unit = {},
    val onOpenWorkout: (CalendarWorkout) -> Unit = {},
    val onAskRemove: (TrainingPlan) -> Unit = {},
    val onCancelRemove: () -> Unit = {},
    val onConfirmRemove: () -> Unit = {},
    val onDismissSuggestion: (TrainingPlan) -> Unit = {},
    /** A page of the web app, e.g. `calendar?week=2026-11-09`. */
    val onOpenWeb: (String) -> Unit = {},
    val onImportGarmin: () -> Unit = {},
    val onCloseGarminDialog: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
    val onOpenConversation: (Long) -> Unit = {},
    val onNewTrainingBlock: () -> Unit = {},
    /** Spec 008: the adjustment form, from the plan card or ([suggestion]) the suggestion banner. */
    val onAdjust: (plan: TrainingPlan, suggestion: Boolean) -> Unit = { _, _ -> },
    /** The proposal's conversation, on its proposal tab. */
    val onOpenProposal: (conversationId: Long) -> Unit = {},
    /** Spec 015: the rider switches the view; the window's width picks it until then. */
    val onMode: (CalendarMode) -> Unit = {},
    val onDefaultMode: (CalendarMode) -> Unit = {},
    val onShowMonth: (YearMonth) -> Unit = {},
    /** A day of the month view opens its week. */
    val onOpenWeekOf: (LocalDate) -> Unit = {},
)

/** The web calendar's phone layout (spec 005): one week as a list of days, swiped or stepped through. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(state: CalendarUiState, fmt: Fmt, actions: CalendarActions) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it, fmt) }
    LaunchedEffect(state.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onMessageShown()
        }
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = pageOf(state.week)) { WEEK_PAGES }
    val week by rememberUpdatedState(state.week)
    val showWeek by rememberUpdatedState(actions.onShowWeek)
    // A swipe that settles on another week shows it; a week chosen otherwise moves the pager
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val monday = mondayOfPage(page)
            if (monday != week) showWeek(monday)
        }
    }
    LaunchedEffect(state.week) {
        val page = pageOf(state.week)
        if (pager.currentPage != page) pager.scrollToPage(page)
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Spec 015: the month on wide windows, the week on phones, until the rider chooses
            val wide = maxWidth >= WIDE_CALENDAR
            val defaultMode by rememberUpdatedState(actions.onDefaultMode)
            LaunchedEffect(wide) { defaultMode(if (wide) CalendarMode.MONTH else CalendarMode.WEEK) }
            Column(Modifier.fillMaxSize()) {
                Header(state, fmt, actions, onPick = { picking = true })
                ModeSwitch(state.mode, actions.onMode)
                when (state.mode) {
                    CalendarMode.WEEK -> HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("calendar-pager"), key = { it }) { page ->
                        if (mondayOfPage(page) == state.week) {
                            WeekPage(state, fmt, actions)
                        } else {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                    CalendarMode.MONTH -> MonthPager(state, fmt, actions, wide)
                }
            }
        }
    }
    if (picking) {
        WeekPicker(if (state.mode == CalendarMode.MONTH) state.month.atDay(1) else state.week, onPick = { picking = false; actions.onShowWeek(it) }, onDismiss = { picking = false })
    }
    state.removing?.let { plan ->
        AlertDialog(
            onDismissRequest = actions.onCancelRemove,
            title = { Text(stringResource(R.string.calendar_remove_plan)) },
            text = { Text(stringResource(R.string.calendar_remove_confirm, plan.name)) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmRemove, enabled = !state.deleting, modifier = Modifier.testTag("confirm-remove")) {
                    Text(stringResource(R.string.calendar_remove_plan), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onCancelRemove, enabled = !state.deleting) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    state.connectingGarmin?.let { status ->
        GarminConnectDialog(status, onConnect = { actions.onCloseGarminDialog(); actions.onOpenWeb("profile") }, onDismiss = actions.onCloseGarminDialog)
    }
}

@Composable
private fun Header(state: CalendarUiState, fmt: Fmt, actions: CalendarActions, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val month = state.mode == CalendarMode.MONTH
        IconButton(onClick = actions.onPrevious, modifier = Modifier.testTag("calendar-previous")) {
            Icon(
                painterResource(R.drawable.ic_chevron_left),
                stringResource(if (month) R.string.calendar_previous_month else R.string.calendar_previous_week),
            )
        }
        val period = if (month) {
            fmt.month(state.month)
        } else {
            "${fmt.date(state.week.toString(), DateStyle.DAY_MONTH)} – ${fmt.date(state.week.plusDays(6).toString(), DateStyle.DAY_MONTH)}"
        }
        val pickLabel = stringResource(R.string.calendar_pick_week)
        TextButton(
            onClick = onPick,
            modifier = Modifier.testTag("calendar-period").semantics { contentDescription = "$period, $pickLabel" },
        ) {
            Text(period, style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily, modifier = Modifier.semantics { heading() })
        }
        IconButton(onClick = actions.onNext, modifier = Modifier.testTag("calendar-next")) {
            Icon(painterResource(R.drawable.ic_chevron_right), stringResource(if (month) R.string.calendar_next_month else R.string.calendar_next_week))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = actions.onToday, modifier = Modifier.testTag("calendar-today")) {
            Text(stringResource(R.string.calendar_today))
        }
        if (state.showGarminImport) {
            IconButton(onClick = actions.onImportGarmin, enabled = !state.sendingPlan, modifier = Modifier.testTag("garmin-import")) {
                Icon(painterResource(R.drawable.ic_send), stringResource(R.string.garmin_import))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekPage(state: CalendarUiState, fmt: Fmt, actions: CalendarActions) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("calendar-week"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            if (!top(state, fmt, actions)) return@LazyColumn
            item(key = "summary") { WeekSummary(state.figures, fmt) }
            items(state.days, key = { "day-${it.date}" }) { day -> DayRow(day, fmt, actions) }
        }
    }
}

/**
 * What both views show above their days (specs 005, 015): progress, errors, the Garmin notice, the plan cards with
 * their banners. `false` when there is nothing more to show (no rider, nothing loaded yet).
 */
internal fun LazyListScope.top(state: CalendarUiState, fmt: Fmt, actions: CalendarActions): Boolean {
    if (state.loading || state.sendingPlan || state.deleting) {
        item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("calendar-loading")) }
    }
    if (state.sendingPlan && state.garmin?.from != null) {
        item(key = "garmin-range") {
            Text(
                stringResource(R.string.garmin_import_tip, fmt.date(state.garmin.from, DateStyle.DAY_MONTH), fmt.date(state.garmin.to, DateStyle.DAY_MONTH)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendedColors.textFaint,
            )
        }
    }
    state.error?.let { error ->
        item(key = "error") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ErrorBanner("${stringResource(R.string.calendar_load_failed)} ${error.toUiText().resolve()}", Modifier.testTag("calendar-error"))
                OutlinedButton(onClick = actions.onRetry, modifier = Modifier.testTag("calendar-retry")) { Text(stringResource(R.string.retry)) }
            }
        }
    }
    if (state.noRider) {
        item(key = "no-rider") { NoRider { actions.onOpenWeb("") } }
        return false
    }
    val data = state.data ?: return false
    if (state.garminStale) {
        item(key = "garmin-outdated") {
            Notice(stringResource(R.string.garmin_outdated_notice), Modifier.testTag("garmin-outdated"))
        }
    }
    items(state.plans, key = { "plan-${it.id}" }) { plan ->
        PlanCard(plan, data.plannedWorkouts, fmt, actions)
    }
    if (state.plans.isEmpty()) {
        item(key = "no-plan") { NoPlan(onCreateBlock = actions.onNewTrainingBlock) }
    }
    return true
}

@Composable
private fun PlanCard(plan: TrainingPlan, workouts: List<CalendarWorkout>, fmt: Fmt, actions: CalendarActions) {
    ElevatedCard(Modifier.fillMaxWidth().testTag("plan-${plan.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(plan.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${fmt.date(plan.startDate, DateStyle.DAY_MONTH_YEAR)} – ${fmt.date(plan.endDate, DateStyle.DAY_MONTH_YEAR)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Badge(methodologyName(plan.methodology))
            }
            plan.goal?.takeIf { it.isNotBlank() }?.let { goal ->
                Text("${stringResource(R.string.calendar_goal)}: $goal", style = MaterialTheme.typography.bodyMedium)
            }
            if (plan.adjustmentRunning) {
                StatusLine(stringResource(R.string.adjustment_running), Modifier.testTag("adjusting-${plan.id}"))
            } else {
                OutlinedButton(onClick = { actions.onAdjust(plan, false) }, modifier = Modifier.testTag("adjust-plan-${plan.id}")) {
                    Icon(painterResource(R.drawable.ic_auto_awesome), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.adjustment_adjust_plan))
                }
            }
            plan.pendingProposal?.let { proposal ->
                Banner(Modifier.testTag("proposal-${plan.id}")) {
                    Text(stringResource(R.string.proposal_banner), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.proposal_from, fmt.date(proposal.fromDate, DateStyle.DAY_MONTH_YEAR)), style = MaterialTheme.typography.bodySmall)
                    proposal.conversationId?.let { conversation ->
                        TextButton(onClick = { actions.onOpenProposal(conversation) }, modifier = Modifier.testTag("show-proposal-${plan.id}")) {
                            Text(stringResource(R.string.proposal_show))
                        }
                    }
                }
            }
            plan.suggestion?.let { suggestion ->
                Banner(Modifier.testTag("suggestion-${plan.id}")) {
                    Text(stringResource(R.string.adherence_suggestion_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    suggestion.reasons.forEach { reason ->
                        Text("• ${reasonText(reason, workouts, fmt)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { actions.onAdjust(plan, true) },
                            enabled = !plan.adjustmentRunning,
                            modifier = Modifier.testTag("ask-coach-${plan.id}"),
                        ) { Text(stringResource(R.string.adjustment_ask_coach)) }
                        TextButton(onClick = { actions.onDismissSuggestion(plan) }, modifier = Modifier.testTag("dismiss-${plan.id}")) {
                            Text(stringResource(R.string.adherence_dismiss))
                        }
                    }
                }
            }
            plan.conversationId?.let { conversation ->
                TextButton(onClick = { actions.onOpenConversation(conversation) }, modifier = Modifier.testTag("plan-conversation-${plan.id}")) {
                    Icon(painterResource(R.drawable.ic_forum), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.calendar_open_conversation))
                }
            }
            TextButton(onClick = { actions.onAskRemove(plan) }, modifier = Modifier.testTag("remove-plan-${plan.id}")) {
                Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.calendar_remove_plan), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun WeekSummary(figures: CalendarWeek?, fmt: Fmt) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("week-summary")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            figures?.planWeek?.let { planWeek ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.calendar_week_number, planWeek.weekNumber), style = MaterialTheme.typography.titleSmall)
                    if (planWeek.recoveryWeek) Badge(stringResource(R.string.calendar_recovery), Modifier.testTag("recovery-week"))
                }
                planWeek.focus?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Total(stringResource(R.string.adherence_tss), fmt.num(figures?.riddenTss ?: 0), fmt.num(figures?.plannedTss ?: 0), "week-tss")
                Total(stringResource(R.string.calendar_time), fmt.hm(figures?.riddenSeconds ?: 0), fmt.hm(figures?.plannedSeconds ?: 0), "week-time")
            }
        }
    }
}

@Composable
private fun Total(label: String, ridden: String, planned: String, tag: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint)
        Row(modifier = Modifier.testTag(tag)) {
            Text(ridden, style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily)
            Text(" / $planned", style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily, color = MaterialTheme.extendedColors.textFaint)
        }
    }
}

@Composable
private fun DayRow(day: CalendarDay, fmt: Fmt, actions: CalendarActions) {
    val highlight = if (day.today) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent
    Row(
        modifier = Modifier.fillMaxWidth().background(highlight, MaterialTheme.shapes.small).padding(vertical = 4.dp)
            .alpha(if (day.past) 0.85f else 1f).testTag("day-${day.date}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(56.dp).padding(start = 4.dp)) {
            Text(
                day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, fmt.locale),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (day.today) FontWeight.Bold else FontWeight.Normal,
            )
            Text(fmt.date(day.date.toString(), DateStyle.DAY_MONTH), style = MaterialTheme.typography.labelSmall, fontFamily = MonoFamily, color = MaterialTheme.extendedColors.textFaint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            day.workouts.forEach { item ->
                WorkoutTile(item.workout, dimmed = day.past && item.workout.status != "COMPLETED", fmt) { actions.onOpenWorkout(item.workout) }
                item.ride?.let { RideRow(it, fmt, linked = true) { actions.onOpenRide(it.activityId) } }
            }
            day.unplannedRides.forEach { ride -> RideRow(ride, fmt, linked = false) { actions.onOpenRide(ride.activityId) } }
            if (day.empty) Text(Fmt.DASH, color = MaterialTheme.extendedColors.textFaint)
        }
    }
}

/** The status color of a workout: the plan vs. ride level of a completed one, as the web's `workout-tile`. */
@Composable
fun statusColor(workout: CalendarWorkout): Color = when (workout.status) {
    "COMPLETED" -> complianceColor(workout.comparison?.level ?: ComplianceLevel.OK)
    "MISSED" -> MaterialTheme.colorScheme.error
    "SKIPPED", "REST" -> MaterialTheme.colorScheme.outlineVariant
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun WorkoutTile(workout: CalendarWorkout, dimmed: Boolean, fmt: Fmt, onOpen: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().alpha(if (dimmed) 0.6f else 1f).testTag("workout-${workout.id}"),
        onClick = onOpen,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(statusColor(workout)))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(workout.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = buildList {
                    add(workoutTypeName(workout.type))
                    if (!workout.rest) {
                        add(fmt.hm(workout.plannedDurationMinutes * 60))
                        add("${fmt.num(workout.plannedTss)} TSS")
                    }
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!workout.rest) {
                    Text(workoutStatusName(workout.status), style = MaterialTheme.typography.labelSmall, color = statusColor(workout), modifier = Modifier.testTag("workout-status-${workout.id}"))
                }
            }
        }
    }
}

@Composable
private fun RideRow(ride: CalendarRide, fmt: Fmt, linked: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = if (linked) 12.dp else 0.dp, top = 2.dp, bottom = 2.dp)
            .testTag("ride-${ride.activityId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_directions_bike), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            ride.name ?: sportName(ride.sportType),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(fmt.hm(ride.durationSeconds), style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ride.trainingLoad?.let { load ->
            Text("${fmt.num(load)} TSS", style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ride.trainingLoadSource == "HEART_RATE") {
                val tooltip = stringResource(R.string.hr_tss_tooltip)
                Text(
                    stringResource(R.string.hr_tss_marker),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.extendedColors.heartRate,
                    modifier = Modifier.testTag("ride-hr-${ride.activityId}").semantics { contentDescription = tooltip },
                )
            }
        }
    }
}

@Composable
private fun NoPlan(onCreateBlock: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("no-plan")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.calendar_no_plan), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCreateBlock, modifier = Modifier.testTag("create-block")) {
                Icon(painterResource(R.drawable.ic_auto_awesome), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.calendar_create_block))
            }
        }
    }
}

@Composable
private fun NoRider(onOpenWeb: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().testTag("calendar-no-rider")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dashboard_no_rider_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dashboard_no_rider_hint), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenWeb) { Text(stringResource(R.string.dashboard_open_web)) }
        }
    }
}

@Composable
fun OpenOnWeb(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag("open-web")) {
        Icon(painterResource(R.drawable.ic_open_in_new), null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.calendar_open_web))
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun Banner(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun StatusLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.extendedColors.info, modifier = modifier)
}

@Composable
fun Notice(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

/** A send without a connection: why, and where to connect (on the web, spec 004). */
@Composable
fun GarminConnectDialog(status: GarminStatus, onConnect: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.garmin_connect_title)) },
        text = { Text(garminStatusText(status)) },
        confirmButton = {
            TextButton(onClick = onConnect, modifier = Modifier.testTag("garmin-connect-web")) { Text(stringResource(R.string.garmin_connect_web)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        modifier = Modifier.testTag("garmin-connect-dialog"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekPicker(week: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = week.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() },
                modifier = Modifier.testTag("week-pick-ok"),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state = state)
    }
}
