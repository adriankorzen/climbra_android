package app.climbra.android.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.data.api.ADJUSTMENT_REASONS
import app.climbra.android.data.api.Availability
import app.climbra.android.data.api.CalendarApi
import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.api.PlanAdjustmentRequest
import app.climbra.android.data.api.SuggestionReason
import app.climbra.android.data.api.TrainingPlan
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.session.SessionRepository
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.MonoFamily
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the adjustment form opens with (spec 008): the plan, a day it covers (to find it in the calendar), whether the
 * plan's suggestion is the reason, and a review's first day and note.
 */
data class AdjustmentArgs(
    val planId: Long,
    val date: String,
    val suggestion: Boolean = false,
    val fromDate: String? = null,
    val note: String? = null,
)

/** The reasons a suggestion preselects (the web's `reasonsOfSuggestion`). */
fun reasonsOfSuggestion(reasons: List<SuggestionReason>): Set<String> = reasons.map {
    if (it.code == "KEY_WORKOUT_MISSED" || it.code == "WORKOUTS_MISSED") "MISSED_WORKOUTS" else "DEVIATION"
}.toSet()

/** No day chosen yet, 60 minutes each when chosen (the web's dialog). */
fun noAvailability(): List<DayAvailability> = DayOfWeek.entries.map { DayAvailability(it, available = false, minutes = 60) }

data class AdjustmentFormState(
    val today: LocalDate,
    val loading: Boolean = true,
    /** The plan could not be loaded. */
    val loadError: ApiError? = null,
    /** The plan is not in the calendar any more. */
    val planMissing: Boolean = false,
    val plan: TrainingPlan? = null,
    /** Opened from the plan's suggestion: its reasons are preselected and sent along. */
    val fromSuggestion: Boolean = false,
    val reasons: Set<String> = emptySet(),
    val fatigue: Int? = null,
    val note: String = "",
    val fromDate: LocalDate = today.plusDays(1),
    val changeDays: Boolean = false,
    val days: List<DayAvailability> = noAvailability(),
    val language: String = "en",
    val limits: CoachLimits = CoachLimits(),
    val sending: Boolean = false,
    val error: ApiError? = null,
    /** The accepted conversation: it replaces the form. */
    val accepted: Long? = null,
) {
    val endDate: LocalDate? get() = plan?.endDate?.let(::parseDate)

    val canSend: Boolean
        get() = plan != null && reasons.isNotEmpty() && !sending && limits.allows(CostKind.PLAN_ADJUSTMENT)

    /** The web's request body: reasons in their fixed order, the availability only when days were chosen. */
    fun request(): PlanAdjustmentRequest {
        val available = days.filter { it.available && it.minutes > 0 }
        return PlanAdjustmentRequest(
            reasons = ADJUSTMENT_REASONS.filter { it in reasons },
            fatigue = fatigue,
            note = note.trim().ifEmpty { null },
            fromDate = fromDate.toString(),
            availability = if (changeDays && available.isNotEmpty()) {
                Availability(days = available.associate { it.day.name to it.minutes }, longRideDay = null)
            } else {
                null
            },
            includeSuggestion = fromSuggestion,
            language = language,
        )
    }
}

private fun parseDate(text: String): LocalDate? = try {
    LocalDate.parse(text.take(10))
} catch (e: DateTimeParseException) {
    null
}

/**
 * Asks the coach to adjust the rest of a plan (spec 008, the web's `adjustment-dialog`). The route gives the plan, a
 * day it covers (to find it in the calendar), whether the plan's suggestion is the reason, and a review's first day
 * and note.
 */
@HiltViewModel
class AdjustmentFormViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val riders: RiderRepository,
    private val calendarApi: CalendarApi,
    private val api: CoachApi,
    private val activity: CoachActivity,
    private val quotas: CoachQuotaRepository,
    private val changes: DataChanges,
    session: SessionRepository,
    clock: Clock,
) : ViewModel() {

    internal var zone: ZoneId = ZoneId.systemDefault()

    private val planId: Long = checkNotNull(savedState.get<Long>("planId")) { "An adjustment route has a plan" }
    private val date: String = checkNotNull(savedState.get<String>("date")) { "An adjustment route has a day of the plan" }
    private val suggestion: Boolean = savedState.get<Boolean>("suggestion") == true
    private val reviewFromDate: LocalDate? = savedState.get<String>("fromDate")?.let(::parseDate)
    private val reviewNote: String? = savedState.get<String>("note")

    private val mutableState = MutableStateFlow(
        AdjustmentFormState(today = clock.instant().atZone(zone).toLocalDate(), language = appLanguage(session)),
    )
    val state: StateFlow<AdjustmentFormState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { coachLimits(quotas, activity).collect { limits -> mutableState.update { it.copy(limits = limits) } } }
        load()
    }

    /** Loads the plan again after a failure. */
    fun load() {
        mutableState.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            val rider = when (val result = riders.load()) {
                is ApiResult.Failure -> return@launch loadFailed(result.error)
                is ApiResult.Success -> result.value ?: return@launch loadFailed(ApiError.Unexpected)
            }
            when (val result = apiCall(Network.json) { calendarApi.calendar(rider.id, date, date) }) {
                is ApiResult.Failure -> loadFailed(result.error)
                is ApiResult.Success -> {
                    val plan = result.value.plans.firstOrNull { it.id == planId }
                    mutableState.update { if (plan == null) it.copy(loading = false, planMissing = true) else prefilled(it, plan) }
                }
            }
        }
    }

    private fun prefilled(state: AdjustmentFormState, plan: TrainingPlan): AdjustmentFormState {
        val end = parseDate(plan.endDate)
        val tomorrow = state.today.plusDays(1)
        val first = when {
            reviewFromDate != null -> maxOf(reviewFromDate, tomorrow)
            end != null && end < tomorrow -> state.today
            else -> tomorrow
        }.let { if (end != null && it > end) end else it }
        val fromSuggestion = suggestion && plan.suggestion != null
        return state.copy(
            loading = false,
            plan = plan,
            fromSuggestion = fromSuggestion,
            reasons = when {
                fromSuggestion -> reasonsOfSuggestion(plan.suggestion!!.reasons)
                reviewNote != null || reviewFromDate != null -> setOf("DEVIATION")
                else -> emptySet()
            },
            note = reviewNote.orEmpty().take(MAX_NOTE),
            fromDate = first,
        )
    }

    private fun loadFailed(error: ApiError) = mutableState.update { it.copy(loading = false, loadError = error) }

    fun toggleReason(reason: String) = mutableState.update {
        it.copy(reasons = if (reason in it.reasons) it.reasons - reason else it.reasons + reason, error = null)
    }

    /** Tapping the chosen value clears it. */
    fun setFatigue(value: Int) = mutableState.update { it.copy(fatigue = if (it.fatigue == value) null else value) }

    fun setNote(text: String) = mutableState.update { it.copy(note = text.take(MAX_NOTE)) }

    fun setFromDate(date: LocalDate) = mutableState.update { it.copy(fromDate = date) }

    fun setChangeDays(on: Boolean) = mutableState.update { it.copy(changeDays = on) }

    fun updateDay(day: DayOfWeek, change: (DayAvailability) -> DayAvailability) = mutableState.update { state ->
        state.copy(
            days = state.days.map {
                if (it.day == day) change(it).let { d -> d.copy(minutes = d.minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)) } else it
            },
        )
    }

    fun send() {
        val current = state.value
        val plan = current.plan ?: return
        if (!current.canSend) return
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val rider = when (val result = riders.load()) {
                is ApiResult.Failure -> return@launch failed(result.error)
                is ApiResult.Success -> result.value ?: return@launch failed(ApiError.Unexpected)
            }
            when (val result = apiCall(Network.json) { api.requestAdjustment(rider.id, plan.id, current.request()) }) {
                is ApiResult.Failure -> failed(result.error)
                is ApiResult.Success -> {
                    activity.track(result.value)
                    changes.changed()
                    mutableState.update { it.copy(sending = false, accepted = result.value.id) }
                    quotas.reload()
                }
            }
        }
    }

    private fun failed(error: ApiError) = mutableState.update { it.copy(sending = false, error = error) }

    companion object {
        const val MAX_NOTE = 2000
        const val MIN_MINUTES = 15
        const val MAX_MINUTES = 600
    }
}

@Composable
fun AdjustmentFormRoute(
    config: AppConfig,
    onBack: () -> Unit,
    onAccepted: (Long) -> Unit,
    viewModel: AdjustmentFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.accepted) { state.accepted?.let(onAccepted) }
    AdjustmentFormScreen(
        state = state,
        fmt = rememberFmt(),
        actions = object : AdjustmentFormActions {
            override fun back() = onBack()
            override fun retry() = viewModel.load()
            override fun toggleReason(reason: String) = viewModel.toggleReason(reason)
            override fun setFatigue(value: Int) = viewModel.setFatigue(value)
            override fun setNote(text: String) = viewModel.setNote(text)
            override fun setFromDate(date: LocalDate) = viewModel.setFromDate(date)
            override fun setChangeDays(on: Boolean) = viewModel.setChangeDays(on)
            override fun updateDay(day: DayOfWeek, change: (DayAvailability) -> DayAvailability) = viewModel.updateDay(day, change)
            override fun send() = viewModel.send()
            override fun openTerms() = openWebPage(context, config.webPage("terms"))
        },
    )
}

interface AdjustmentFormActions {
    fun back() {}
    fun retry() {}
    fun toggleReason(reason: String) {}
    fun setFatigue(value: Int) {}
    fun setNote(text: String) {}
    fun setFromDate(date: LocalDate) {}
    fun setChangeDays(on: Boolean) {}
    fun updateDay(day: DayOfWeek, change: (DayAvailability) -> DayAvailability) {}
    fun send() {}
    fun openTerms() {}
}

@Composable
fun adjustmentReasonName(reason: String): String = when (reason) {
    "FATIGUE" -> stringResource(R.string.adjustment_reason_fatigue)
    "ILLNESS" -> stringResource(R.string.adjustment_reason_illness)
    "LACK_OF_TIME" -> stringResource(R.string.adjustment_reason_lack_of_time)
    "MISSED_WORKOUTS" -> stringResource(R.string.adjustment_reason_missed_workouts)
    "DEVIATION" -> stringResource(R.string.adjustment_reason_deviation)
    else -> stringResource(R.string.adjustment_reason_other)
}

/** The web's adjustment dialog as one scrolling screen (spec 008). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdjustmentFormScreen(state: AdjustmentFormState, fmt: Fmt, actions: AdjustmentFormActions) {
    val title = state.plan?.let { stringResource(R.string.adjustment_title_plan, it.name) } ?: stringResource(R.string.adjustment_title)
    FormScaffold(title, actions::back, state.sending) {
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("adjustment-loading"))
            state.planMissing -> ErrorBanner(stringResource(R.string.adjustment_plan_missing), Modifier.testTag("plan-missing"))
            state.loadError != null -> {
                CoachRequestError(state.loadError, Modifier.testTag("adjustment-load-error"))
                Button(onClick = actions::retry, modifier = Modifier.testTag("adjustment-retry")) { Text(stringResource(R.string.retry)) }
            }
            else -> Form(state, fmt, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(state: AdjustmentFormState, fmt: Fmt, actions: AdjustmentFormActions) {
    if (state.fromSuggestion) {
        Text(
            stringResource(R.string.adjustment_from_suggestion),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("from-suggestion"),
        )
    }
    Label(stringResource(R.string.adjustment_reasons))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ADJUSTMENT_REASONS.forEach { reason ->
            FilterChip(
                selected = reason in state.reasons,
                onClick = { actions.toggleReason(reason) },
                label = { Text(adjustmentReasonName(reason)) },
                modifier = Modifier.testTag("reason-${reason.lowercase()}"),
            )
        }
    }
    Label(stringResource(R.string.adjustment_fatigue))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..10).forEach { value ->
            FilterChip(
                selected = state.fatigue == value,
                onClick = { actions.setFatigue(value) },
                label = { Text("$value", fontFamily = MonoFamily) },
                modifier = Modifier.testTag("fatigue-$value"),
            )
        }
    }
    OutlinedTextField(
        value = state.note,
        onValueChange = actions::setNote,
        label = { Text(stringResource(R.string.adjustment_note)) },
        placeholder = { Text(stringResource(R.string.adjustment_note_placeholder)) },
        supportingText = if (state.note.length > AdjustmentFormViewModel.MAX_NOTE - NOTE_COUNTER_FROM) {
            { Text("${state.note.length} / ${AdjustmentFormViewModel.MAX_NOTE}") }
        } else {
            null
        },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("adjustment-note"),
    )
    HealthNote(actions::openTerms)
    DateField(
        label = stringResource(R.string.adjustment_from_date),
        value = state.fromDate,
        minDate = state.today,
        maxDate = state.endDate,
        fmt = fmt,
        tag = "from-date",
        onPick = { date -> date?.let(actions::setFromDate) },
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.adjustment_change_days), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = state.changeDays, onCheckedChange = actions::setChangeDays, modifier = Modifier.testTag("change-days"))
    }
    if (state.changeDays) {
        state.days.forEach { day -> DayRow(day, fmt, actions::updateDay, showIndoor = false) }
    }
    state.error?.let { CoachRequestError(it, Modifier.testTag("adjustment-error")) }
    CoachCostLine(state.limits, CostKind.PLAN_ADJUSTMENT, fmt)
    Button(onClick = actions::send, enabled = state.canSend, modifier = Modifier.fillMaxWidth().testTag("send-adjustment")) {
        Text(stringResource(R.string.adjustment_send))
    }
}

/** The note's counter shows this close to the limit. */
private const val NOTE_COUNTER_FROM = 200
