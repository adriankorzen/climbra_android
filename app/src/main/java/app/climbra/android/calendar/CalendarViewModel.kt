package app.climbra.android.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.data.api.CalendarApi
import app.climbra.android.data.api.CalendarResponse
import app.climbra.android.data.api.CalendarWeek
import app.climbra.android.data.api.GarminConnection
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.api.Rider
import app.climbra.android.data.api.TrainingPlan
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.garmin.GarminConnectFlow
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The calendar's views (spec 015): one week, or the whole weeks covering a month. */
enum class CalendarMode { WEEK, MONTH }

/** The whole weeks (Monday to Sunday) covering [month], as the web's `monthRange`. */
fun monthRange(month: YearMonth): Pair<LocalDate, LocalDate> =
    mondayOf(month.atDay(1)) to mondayOf(month.atEndOfMonth()).plusDays(6)

data class CalendarUiState(
    val mode: CalendarMode = CalendarMode.WEEK,
    /** The rider chose the view: the window's width no longer decides it. */
    val modeChosen: Boolean = false,
    /** Monday of the week shown. */
    val week: LocalDate,
    val today: LocalDate,
    /** The month of the month view (spec 015). */
    val month: YearMonth = YearMonth.from(week.plusDays(3)),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: ApiError? = null,
    val noRider: Boolean = false,
    /** The calendar of [week]; kept while the week loads again. */
    val data: CalendarResponse? = null,
    val garmin: GarminConnection? = null,
    val sendingPlan: Boolean = false,
    /** A send was asked for without a connection: the status the dialog explains. */
    val connectingGarmin: GarminStatus? = null,
    /** The plan the removal is asked for. */
    val removing: TrainingPlan? = null,
    val deleting: Boolean = false,
    val message: CalendarMessage? = null,
) {
    val days: List<CalendarDay> get() = calendarDays(week, data, today)

    /** The week's totals and plan week (cyclingapp spec 054). */
    val figures: CalendarWeek? get() = data?.weeks?.firstOrNull { it.start == week.toString() }

    /** The month view's weeks (spec 015): days outside [month] are marked by the screen. */
    val monthWeeks: List<List<CalendarDay>>
        get() {
            val (first, last) = monthRange(month)
            return generateSequence(first) { it.plusWeeks(1) }.takeWhile { !it.isAfter(last) }
                .map { calendarDays(it, data, today) }.toList()
        }

    fun figuresOf(monday: LocalDate): CalendarWeek? = data?.weeks?.firstOrNull { it.start == monday.toString() }

    val plans: List<TrainingPlan> get() = data?.plans.orEmpty()

    /** The plan import is offered when something can be sent or is out of date (the web's toolbar button). */
    val showGarminImport: Boolean
        get() = garmin?.available == true && (garmin.sendable > 0 || garmin.stale > 0)

    val garminStale: Boolean get() = garmin?.available == true && garmin.stale > 0
}

/** The calendar tab (spec 005): one week of the backend's calendar at a time, with the plan actions. */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val riders: RiderRepository,
    private val api: CalendarApi,
    private val changes: DataChanges,
    private val requests: CalendarRequests,
    private val clock: Clock,
    private val garminConnect: GarminConnectFlow,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        CalendarUiState(
            week = requests.week.value ?: savedState.get<String>(WEEK_KEY)?.let(LocalDate::parse) ?: mondayOf(today()),
            today = today(),
        ),
    )
    val state: StateFlow<CalendarUiState> = mutableState.asStateFlow()

    private var rider: Rider? = null
    private var request: Job? = null
    /** Bumped by changes to rides: shown next to the ride or workout changed (spec 019), the screen loads again. */
    val dataVersion: StateFlow<Int> get() = changes.version

    private var loadedVersion = changes.version.value

    init {
        requests.consumed()
        savedState[WEEK_KEY] = state.value.week.toString()
        load()
        // A week asked for by another screen while the calendar exists
        viewModelScope.launch {
            requests.week.filterNotNull().collect { week ->
                requests.consumed()
                showWeek(week)
            }
        }
        // Connecting Garmin Connect ended (spec 016): what can be sent changes
        viewModelScope.launch {
            garminConnect.completed.drop(1).collect { if (rider != null) load(withGarmin = true) }
        }
    }

    fun previous() = when (state.value.mode) {
        CalendarMode.WEEK -> showWeek(state.value.week.minusWeeks(1))
        CalendarMode.MONTH -> showMonth(state.value.month.minusMonths(1))
    }

    fun next() = when (state.value.mode) {
        CalendarMode.WEEK -> showWeek(state.value.week.plusWeeks(1))
        CalendarMode.MONTH -> showMonth(state.value.month.plusMonths(1))
    }

    fun showToday() = when (state.value.mode) {
        CalendarMode.WEEK -> showWeek(mondayOf(today()))
        CalendarMode.MONTH -> showMonth(YearMonth.from(today()))
    }

    /** The week of [date], e.g. from the date picker or a swipe; in the month view its month. */
    fun showWeek(date: LocalDate) {
        if (state.value.mode == CalendarMode.MONTH) return showMonth(YearMonth.from(date))
        val monday = mondayOf(date)
        if (monday == state.value.week) return
        savedState[WEEK_KEY] = monday.toString()
        mutableState.update { it.copy(week = monday, data = null) }
        load()
    }

    /** Spec 015: the month view of [month]. */
    fun showMonth(month: YearMonth) {
        if (month == state.value.month && state.value.mode == CalendarMode.MONTH && state.value.data != null) return
        mutableState.update { it.copy(month = month, data = null) }
        load()
    }

    /** Spec 015: the rider switches the view; the shown date stays (the week of today or of the month's 1st). */
    fun setMode(mode: CalendarMode) = switchMode(mode, chosen = true)

    /** Spec 015: the window's width picks the view until the rider chose one. */
    fun defaultMode(mode: CalendarMode) {
        if (!state.value.modeChosen) switchMode(mode, chosen = false)
    }

    /** Spec 015: a day of the month view opens its week. */
    fun openWeekOf(date: LocalDate) {
        val monday = mondayOf(date)
        savedState[WEEK_KEY] = monday.toString()
        mutableState.update { it.copy(mode = CalendarMode.WEEK, modeChosen = true, week = monday, data = null) }
        load()
    }

    private fun switchMode(mode: CalendarMode, chosen: Boolean) {
        val current = state.value
        if (mode == current.mode) {
            if (chosen) mutableState.update { it.copy(modeChosen = true) }
            return
        }
        mutableState.update {
            when (mode) {
                CalendarMode.MONTH -> it.copy(mode = mode, modeChosen = chosen || it.modeChosen, month = YearMonth.from(it.week.plusDays(3)), data = null)
                CalendarMode.WEEK -> {
                    val today = today()
                    val date = if (YearMonth.from(today) == it.month) today else it.month.atDay(1)
                    it.copy(mode = mode, modeChosen = chosen || it.modeChosen, week = mondayOf(date), data = null)
                }
            }
        }
        savedState[WEEK_KEY] = state.value.week.toString()
        load()
    }

    fun refresh() {
        mutableState.update { it.copy(refreshing = true) }
        load(withGarmin = true)
    }

    fun retry() = load()

    /** The tab is shown again: rides or workouts changed elsewhere load again, and today may be another day. */
    fun onShown() {
        val today = today()
        if (today != state.value.today) mutableState.update { it.copy(today = today) }
        if (changes.version.value != loadedVersion) load()
    }

    /** The week shown; the Garmin connection with it the first time and on [withGarmin]. */
    private fun load(withGarmin: Boolean = false) {
        request?.cancel()
        val week = state.value.week
        // The month view asks for the whole weeks covering its month (spec 015)
        val (from, to) = when (state.value.mode) {
            CalendarMode.WEEK -> week to week.plusDays(6)
            CalendarMode.MONTH -> monthRange(state.value.month)
        }
        mutableState.update { it.copy(loading = !it.refreshing, error = null) }
        request = viewModelScope.launch {
            val first = rider == null
            val rider = rider() ?: return@launch
            loadedVersion = changes.version.value
            val (result, garmin) = coroutineScope {
                val garmin = if (first || withGarmin) async { garmin(rider) } else null
                val calendar = call { api.calendar(rider.id, from.toString(), to.toString()) }
                calendar to garmin?.await()
            }
            mutableState.update {
                val shown = it.copy(loading = false, refreshing = false, garmin = garmin ?: it.garmin)
                when (result) {
                    is ApiResult.Failure -> shown.copy(error = result.error)
                    is ApiResult.Success -> shown.copy(data = result.value, noRider = false)
                }
            }
        }
    }

    /** The account's rider; `null` after a failure or without a rider. */
    private suspend fun rider(): Rider? {
        rider?.let { return it }
        return when (val result = riders.load()) {
            is ApiResult.Failure -> {
                mutableState.update { it.copy(loading = false, refreshing = false, error = result.error) }
                null
            }
            is ApiResult.Success -> result.value?.also { rider = it } ?: run {
                mutableState.update { it.copy(loading = false, refreshing = false, noRider = true) }
                null
            }
        }
    }

    /** `null` when it cannot be loaded: nothing of Garmin shows then. */
    private suspend fun garmin(rider: Rider): GarminConnection? =
        (call { api.garminConnection(rider.id) } as? ApiResult.Success)?.value

    // Plans

    fun askRemove(plan: TrainingPlan) = mutableState.update { it.copy(removing = plan) }

    fun cancelRemove() = mutableState.update { if (it.deleting) it else it.copy(removing = null) }

    fun confirmRemove() {
        val plan = state.value.removing ?: return
        val rider = rider ?: return
        if (state.value.deleting) return
        mutableState.update { it.copy(deleting = true) }
        viewModelScope.launch {
            when (val result = call { api.deletePlan(rider.id, plan.id) }) {
                is ApiResult.Failure -> mutableState.update {
                    it.copy(deleting = false, message = CalendarMessage.Failed(result.error))
                }
                is ApiResult.Success -> {
                    // Its rides are no longer linked, and what is on Garmin may be out of date
                    changes.changed()
                    load(withGarmin = true)
                    mutableState.update {
                        it.copy(deleting = false, removing = null, message = CalendarMessage.PlanRemoved(plan.name))
                    }
                }
            }
        }
    }

    fun dismissSuggestion(plan: TrainingPlan) {
        val rider = rider ?: return
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val result = call { api.dismissSuggestion(rider.id, plan.id) }) {
                is ApiResult.Failure -> mutableState.update {
                    it.copy(loading = false, message = CalendarMessage.Failed(result.error))
                }
                is ApiResult.Success -> load()
            }
        }
    }

    // Garmin Connect

    /** Sends the plan's workouts of 2 weeks; without a connection the dialog explains where to connect. */
    fun sendPlanToGarmin() {
        val rider = rider ?: return
        val garmin = state.value.garmin ?: return
        if (state.value.sendingPlan) return
        if (garmin.status != GarminStatus.CONNECTED) {
            return mutableState.update { it.copy(connectingGarmin = garmin.status) }
        }
        mutableState.update { it.copy(sendingPlan = true) }
        viewModelScope.launch {
            val message = when (val result = call { api.sendPlanToGarmin(rider.id) }) {
                is ApiResult.Failure -> CalendarMessage.Failed(result.error)
                is ApiResult.Success -> CalendarMessage.GarminPlanSent(result.value, garmin.from, garmin.to)
            }
            // The connection may have been refused meanwhile: the next send offers connecting again
            val connection = garmin(rider)
            mutableState.update { it.copy(sendingPlan = false, message = message, garmin = connection ?: it.garmin) }
        }
    }

    fun closeGarminDialog() = mutableState.update { it.copy(connectingGarmin = null) }

    /** Connect in the dialog: the app's consent dialog takes over (spec 016). */
    fun connectGarmin() {
        val status = state.value.connectingGarmin ?: GarminStatus.NOT_CONNECTED
        closeGarminDialog()
        rider?.let { garminConnect.ask(it.id, status) }
    }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    private suspend fun <T> call(request: suspend () -> T) = apiCall(Network.json, request)

    // LocalDate.ofInstant needs API 34; the app supports API 29
    private fun today(): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

    companion object {
        private const val WEEK_KEY = "week"
    }
}
