package app.climbra.android.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.activities.FileSaver
import app.climbra.android.activities.fileNameOf
import app.climbra.android.data.api.CalendarApi
import app.climbra.android.data.api.CalendarRide
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.GarminConnection
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.api.LinkRideRequest
import app.climbra.android.data.api.Rider
import app.climbra.android.data.api.SkipRequest
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Connectivity
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.data.net.httpError
import app.climbra.android.garmin.GarminConnectFlow
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkoutDetailUiState(
    val loading: Boolean = true,
    val error: ApiError? = null,
    /** The workout is no longer in the calendar (its plan was removed). */
    val notFound: Boolean = false,
    val workout: CalendarWorkout? = null,
    /** The rides within [WorkoutDetailViewModel.LINK_DAYS] of the workout. */
    val rides: List<CalendarRide> = emptyList(),
    /** Linking, unlinking or skipping. */
    val busy: Boolean = false,
    val actionError: ApiError? = null,
    val garmin: GarminConnection? = null,
    /** `SENT`, `OUTDATED` or `NOT_SENT`; `null` when the workout cannot be sent. */
    val garminStatus: String? = null,
    val sending: Boolean = false,
    val connectingGarmin: GarminStatus? = null,
    val downloading: Boolean = false,
    val message: CalendarMessage? = null,
) {
    val linkedRide: CalendarRide? get() = rides.firstOrNull { it.activityId == workout?.activityId }

    /** Rides that can be linked instead: all nearby rides but the linked one. */
    val candidates: List<CalendarRide> get() = rides.filter { it.activityId != workout?.activityId }

    /** The backend resolves watts only with an FTP: power steps without them mean there is none. */
    val noFtp: Boolean get() = workout?.blocks.orEmpty().any { block -> block.steps.any { it.powerLowWatts == null } }
}

/**
 * A planned workout of the calendar (spec 005, the web's `workout-panel`): the workout and the rides around it from
 * one calendar request, linking, skipping, sending to Garmin and the Zwift file.
 */
@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val riders: RiderRepository,
    private val api: CalendarApi,
    private val changes: DataChanges,
    private val files: FileSaver,
    private val garminConnect: GarminConnectFlow,
    /** Back online (spec 018): the workout loads again. */
    connectivity: Connectivity = Connectivity(),
) : ViewModel() {

    private val id: Long = checkNotNull(savedState.get<Long>("id")) { "A workout route has an id" }
    private val date: LocalDate = LocalDate.parse(checkNotNull(savedState.get<String>("date")) { "A workout route has a date" })

    private val mutableState = MutableStateFlow(WorkoutDetailUiState())
    val state: StateFlow<WorkoutDetailUiState> = mutableState.asStateFlow()

    private var rider: Rider? = null

    init {
        viewModelScope.launch { connectivity.reloads.collect { load() } }
        load()
        // Connecting Garmin Connect ended (spec 016): the connection and the workout's status load again
        viewModelScope.launch {
            garminConnect.completed.drop(1).collect { if (rider != null) load() }
        }
    }

    fun retry() = load()

    private fun load() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val rider = rider ?: when (val result = riders.load()) {
                is ApiResult.Failure -> return@launch mutableState.update { it.copy(loading = false, error = result.error) }
                is ApiResult.Success -> result.value?.also { rider = it }
                    ?: return@launch mutableState.update { it.copy(loading = false, notFound = true) }
            }
            val (nearby, garmin) = coroutineScope {
                val nearby = async { loadNearby(rider) }
                val garmin = async { (call { api.garminConnection(rider.id) } as? ApiResult.Success)?.value }
                nearby.await() to garmin.await()
            }
            mutableState.update { it.copy(garmin = garmin) }
            if (nearby is ApiResult.Failure) return@launch mutableState.update { it.copy(loading = false, error = nearby.error) }
            loadGarminStatus()
            mutableState.update { it.copy(loading = false) }
        }
    }

    /** The workout and the rides around it; the workout is shown as the calendar answers it. */
    private suspend fun loadNearby(rider: Rider): ApiResult<Unit> {
        val result = call {
            api.calendar(rider.id, date.minusDays(LINK_DAYS).toString(), date.plusDays(LINK_DAYS).toString())
        }
        if (result is ApiResult.Success) {
            val workout = result.value.plannedWorkouts.firstOrNull { it.id == id }
            mutableState.update { it.copy(workout = workout, notFound = workout == null, rides = result.value.activities) }
        }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }
    }

    private suspend fun loadGarminStatus() {
        val rider = rider ?: return
        if (state.value.garmin?.available != true) return
        val statuses = call { api.garminWorkouts(rider.id, date.toString(), date.toString()) }
        val status = (statuses as? ApiResult.Success)?.value?.workouts?.firstOrNull { it.workoutId == id }?.status
        mutableState.update { it.copy(garminStatus = status) }
    }

    fun link(activityId: Long) = act { rider -> api.linkRide(rider.id, id, LinkRideRequest(activityId)) }

    fun unlink() = act { rider -> api.unlinkRide(rider.id, id) }

    fun skip(skipped: Boolean) = act { rider -> api.skip(rider.id, id, SkipRequest(skipped)) }

    /** The answered workout replaces the shown one; the calendar and the training log load again when shown. */
    private fun act(request: suspend (Rider) -> CalendarWorkout) {
        val rider = rider ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            when (val result = call { request(rider) }) {
                is ApiResult.Failure -> mutableState.update { it.copy(busy = false, actionError = result.error) }
                is ApiResult.Success -> {
                    changes.changed()
                    mutableState.update { it.copy(workout = result.value) }
                    loadNearby(rider)
                    mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }

    // Garmin Connect

    fun sendToGarmin() {
        val rider = rider ?: return
        val garmin = state.value.garmin ?: return
        if (state.value.sending) return
        if (garmin.status != GarminStatus.CONNECTED) {
            return mutableState.update { it.copy(connectingGarmin = garmin.status) }
        }
        mutableState.update { it.copy(sending = true) }
        viewModelScope.launch {
            val message = when (val result = call { api.sendWorkoutToGarmin(rider.id, id) }) {
                is ApiResult.Failure -> CalendarMessage.Failed(result.error)
                is ApiResult.Success -> CalendarMessage.GarminWorkoutSent(result.value)
            }
            val connection = (call { api.garminConnection(rider.id) } as? ApiResult.Success)?.value
            mutableState.update { it.copy(garmin = connection ?: it.garmin) }
            loadGarminStatus()
            mutableState.update { it.copy(sending = false, message = message) }
        }
    }

    fun closeGarminDialog() = mutableState.update { it.copy(connectingGarmin = null) }

    /** Connect in the dialog: the app's consent dialog takes over (spec 016). */
    fun connectGarmin() {
        val status = state.value.connectingGarmin ?: GarminStatus.NOT_CONNECTED
        closeGarminDialog()
        rider?.let { garminConnect.ask(it.id, status) }
    }

    // Zwift

    /** The `.zwo` file to the user's Downloads (cyclingapp spec 048). */
    fun downloadZwift() {
        val rider = rider ?: return
        if (state.value.downloading) return
        mutableState.update { it.copy(downloading = true) }
        viewModelScope.launch {
            val message = when (val result = call { api.zwo(rider.id, id) }) {
                is ApiResult.Failure -> CalendarMessage.Failed(result.error)
                is ApiResult.Success -> {
                    val response = result.value
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        CalendarMessage.Failed(httpError(Network.json, response.code(), response.errorBody()?.string()))
                    } else {
                        val name = fileNameOf(response.headers()["Content-Disposition"], "workout.zwo")
                        try {
                            body.use { files.saveToDownloads(name, ZWO_MIME_TYPE, it.byteStream()) }
                            CalendarMessage.Saved(name)
                        } catch (e: IOException) {
                            CalendarMessage.SaveFailed
                        }
                    }
                }
            }
            mutableState.update { it.copy(downloading = false, message = message) }
        }
    }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    private suspend fun <T> call(request: suspend () -> T) = apiCall(Network.json, request)

    companion object {
        /** Rides may be linked to a workout at most this many days apart (the web's `MAX_LINK_DAYS`). */
        const val LINK_DAYS = 3L
        const val ZWO_MIME_TYPE = "application/xml"
    }
}
