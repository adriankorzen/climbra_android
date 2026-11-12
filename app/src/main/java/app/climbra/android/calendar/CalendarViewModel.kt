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
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarUiState(
    /** Monday of the week shown. */
    val week: LocalDate,
    val today: LocalDate,
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
    }

    fun previous() = showWeek(state.value.week.minusWeeks(1))

    fun next() = showWeek(state.value.week.plusWeeks(1))

    fun showToday() = showWeek(mondayOf(today()))

    /** The week of [date], e.g. from the date picker or a swipe. */
    fun showWeek(date: LocalDate) {
        val monday = mondayOf(date)
        if (monday == state.value.week) return
        savedState[WEEK_KEY] = monday.toString()
        mutableState.update { it.copy(week = monday, data = null) }
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
        mutableState.update { it.copy(loading = !it.refreshing, error = null) }
        request = viewModelScope.launch {
            val first = rider == null
            val rider = rider() ?: return@launch
            loadedVersion = changes.version.value
            val (result, garmin) = coroutineScope {
                val garmin = if (first || withGarmin) async { garmin(rider) } else null
                val calendar = call { api.calendar(rider.id, week.toString(), week.plusDays(6).toString()) }
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

    fun messageShown() = mutableState.update { it.copy(message = null) }

    private suspend fun <T> call(request: suspend () -> T) = apiCall(Network.json, request)

    // LocalDate.ofInstant needs API 34; the app supports API 29
    private fun today(): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

    companion object {
        private const val WEEK_KEY = "week"
    }
}
