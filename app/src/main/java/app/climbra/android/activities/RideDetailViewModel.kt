package app.climbra.android.activities

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.data.api.ActivityApi
import app.climbra.android.data.api.ActivityDetail
import app.climbra.android.data.api.AnalysisResponse
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.IntervalSourceRequest
import app.climbra.android.data.api.LinkRideRequest
import app.climbra.android.data.api.Recording
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Connectivity
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.data.net.httpError
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable


/** The fields of the web's detection form; blank means "auto". Percents as typed, e.g. `90`. */
data class DetectionForm(
    val targetWatts: String = "",
    val thresholdPercent: String = "",
    val minDuration: String = "",
    val maxGap: String = "",
    val underPercent: String = "",
) {
    /**
     * The query of an ad hoc analysis: only the filled fields, percents as fractions — the only conversion the app
     * makes (spec 003). `null` when a field is not a number.
     */
    fun toQuery(): Map<String, String>? {
        val query = mutableMapOf<String, String>()
        fun put(name: String, text: String, scale: Double = 1.0): Boolean {
            if (text.isBlank()) return true
            val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return false
            val scaled = value / scale
            query[name] = if (scale == 1.0 && scaled % 1.0 == 0.0) scaled.toLong().toString() else scaled.toString()
            return true
        }
        val valid = put("targetWatts", targetWatts) &&
            put("relativeThreshold", thresholdPercent, 100.0) &&
            put("minDuration", minDuration) &&
            put("maxGap", maxGap) &&
            put("underThreshold", underPercent, 100.0)
        return if (valid) query else null
    }

    val isEmpty: Boolean
        get() = listOf(targetWatts, thresholdPercent, minDuration, maxGap, underPercent).all { it.isBlank() }
}

/** What a finished action tells the rider in a snackbar. */
sealed interface RideMessage {
    data class Saved(val fileName: String) : RideMessage
    data class Failed(val error: ApiError) : RideMessage
    data object SaveFailed : RideMessage
}

data class RideDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    /** The ride or its analysis could not be loaded for another reason. */
    val error: ApiError? = null,
    val activity: ActivityDetail? = null,
    /** The analysis shown: the stored one, or an ad hoc one ([AnalysisResponse.stored] false). */
    val analysis: AnalysisResponse? = null,
    val analysisFailed: Boolean = false,
    val recording: Recording? = null,
    val recordingLoading: Boolean = true,
    val form: DetectionForm = DetectionForm(),
    val formInvalid: Boolean = false,
    /** Reanalysing, switching the interval source or detecting ad hoc. */
    val analysing: Boolean = false,
    val deleting: Boolean = false,
    val downloading: Boolean = false,
    /** Workouts an unplanned ride can be linked to. */
    val candidates: List<CalendarWorkout> = emptyList(),
    val linking: Boolean = false,
    val linkError: ApiError? = null,
    /** The ride was deleted: the screen goes back. */
    val deleted: Boolean = false,
    val message: RideMessage? = null,
)

/** Ride detail (spec 003): the backend's ride, analysis and recording, and the actions of the web page. */
@HiltViewModel
class RideDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: ActivityApi,
    private val riders: RiderRepository,
    private val changes: DataChanges,
    private val files: FileSaver,
    /** Back online (spec 018): the ride loads again. */
    connectivity: Connectivity = Connectivity(),
) : ViewModel() {

    /** The id of the ride route of either tab (`DashboardRideRoute`, `ActivitiesRideRoute`). */
    private val id: Long = checkNotNull(savedState.get<Long>("id")) { "A ride route has an id" }

    private val mutableState = MutableStateFlow(RideDetailUiState())
    val state: StateFlow<RideDetailUiState> = mutableState.asStateFlow()

    /** The stored analysis, what "reset" of an ad hoc detection returns to without a request. */
    private var stored: AnalysisResponse? = null

    init {
        viewModelScope.launch { connectivity.reloads.collect { load() } }
        load()
    }

    fun retry() = load()

    private fun load() {
        mutableState.update { RideDetailUiState(form = it.form) }
        viewModelScope.launch {
            val (activity, analysis) = coroutineScope {
                val activity = async { call { api.activity(id) } }
                val analysis = async { call { api.analysis(id) } }
                activity.await() to analysis.await()
            }
            if (activity is ApiResult.Failure) {
                val missing = (activity.error as? ApiError.Http)?.status == 404
                mutableState.update { it.copy(loading = false, recordingLoading = false, notFound = missing, error = activity.error.takeUnless { missing }) }
                return@launch
            }
            val ride = (activity as ApiResult.Success).value
            stored = (analysis as? ApiResult.Success)?.value
            mutableState.update {
                it.copy(loading = false, activity = ride, analysis = stored, analysisFailed = analysis is ApiResult.Failure)
            }
            // A file read counts against the backend's limits: once per opening, after the rest is shown
            val recording = call { api.recording(id, RECORDING_RESOLUTION) }
            mutableState.update { it.copy(recording = (recording as? ApiResult.Success)?.value, recordingLoading = false) }
            loadCandidates(ride)
        }
    }

    private suspend fun loadCandidates(ride: ActivityDetail) {
        if (ride.plannedWorkout != null) {
            mutableState.update { it.copy(candidates = emptyList()) }
            return
        }
        val rider = (riders.load() as? ApiResult.Success)?.value ?: return
        val day = LocalDate.parse(ride.day)
        val calendar = call {
            api.calendar(rider.id, day.minusDays(LINK_DAYS).toString(), day.plusDays(LINK_DAYS).toString())
        }
        val workouts = (calendar as? ApiResult.Success)?.value?.plannedWorkouts.orEmpty()
            .filter { it.status != "REST" && it.status != "COMPLETED" }
        mutableState.update { it.copy(candidates = workouts) }
    }

    fun onFormChange(form: DetectionForm) = mutableState.update { it.copy(form = form, formInvalid = false) }

    /** An ad hoc analysis with the filled fields, not stored. */
    fun applyDetection() {
        val query = state.value.form.toQuery() ?: return mutableState.update { it.copy(formInvalid = true) }
        analyse(changesData = false) { api.analysis(id, query) }
    }

    /** Back to the stored analysis, without a request. */
    fun resetDetection() = mutableState.update { it.copy(form = DetectionForm(), analysis = stored ?: it.analysis) }

    fun setIntervalSource(useLaps: Boolean) = analyse(changesData = true) {
        api.setIntervalSource(id, IntervalSourceRequest(useLaps))
    }

    fun reanalyze() = analyse(changesData = true) { api.reanalyze(id) }

    private fun analyse(changesData: Boolean, request: suspend () -> AnalysisResponse) {
        if (state.value.analysing) return
        mutableState.update { it.copy(analysing = true) }
        viewModelScope.launch {
            when (val result = call(request)) {
                is ApiResult.Success -> {
                    if (result.value.stored) {
                        stored = result.value
                    }
                    if (changesData) changes.changed()
                    mutableState.update {
                        it.copy(
                            analysing = false,
                            analysis = result.value,
                            analysisFailed = false,
                            form = if (result.value.stored) DetectionForm() else it.form,
                        )
                    }
                }
                is ApiResult.Failure -> mutableState.update {
                    it.copy(analysing = false, message = RideMessage.Failed(result.error))
                }
            }
        }
    }

    fun delete() {
        if (state.value.deleting) return
        mutableState.update { it.copy(deleting = true) }
        viewModelScope.launch {
            when (val result = call { api.delete(id) }) {
                is ApiResult.Success -> {
                    changes.changed()
                    mutableState.update { it.copy(deleting = false, deleted = true) }
                }
                is ApiResult.Failure -> mutableState.update {
                    it.copy(deleting = false, message = RideMessage.Failed(result.error))
                }
            }
        }
    }

    fun link(workoutId: Long) {
        if (state.value.linking) return
        mutableState.update { it.copy(linking = true, linkError = null) }
        viewModelScope.launch {
            val rider = when (val result = riders.load()) {
                is ApiResult.Failure -> return@launch mutableState.update { it.copy(linking = false, linkError = result.error) }
                is ApiResult.Success -> result.value ?: return@launch mutableState.update { it.copy(linking = false) }
            }
            when (val result = call { api.linkRide(rider.id, workoutId, LinkRideRequest(id)) }) {
                is ApiResult.Failure -> mutableState.update { it.copy(linking = false, linkError = result.error) }
                is ApiResult.Success -> {
                    changes.changed()
                    when (val activity = call { api.activity(id) }) {
                        is ApiResult.Success -> {
                            mutableState.update { it.copy(linking = false, activity = activity.value) }
                            loadCandidates(activity.value)
                        }
                        is ApiResult.Failure -> mutableState.update { it.copy(linking = false) }
                    }
                }
            }
        }
    }

    /** The FIT file to the user's Downloads. */
    fun download() {
        if (state.value.downloading) return
        mutableState.update { it.copy(downloading = true) }
        viewModelScope.launch {
            val message = when (val result = call { api.file(id) }) {
                is ApiResult.Failure -> RideMessage.Failed(result.error)
                is ApiResult.Success -> {
                    val response = result.value
                    val body = response.body()
                    if (!response.isSuccessful || body == null) {
                        RideMessage.Failed(httpError(Network.json, response.code(), response.errorBody()?.string()))
                    } else {
                        val name = fileName(response.headers()["Content-Disposition"])
                        try {
                            body.use { files.saveToDownloads(name, FIT_MIME_TYPE, it.byteStream()) }
                            RideMessage.Saved(name)
                        } catch (e: IOException) {
                            RideMessage.SaveFailed
                        }
                    }
                }
            }
            mutableState.update { it.copy(downloading = false, message = message) }
        }
    }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    private fun fileName(disposition: String?): String = fileNameOf(disposition, "climbra-ride-$id.fit")

    private suspend fun <T> call(request: suspend () -> T) = apiCall(Network.json, request)

    companion object {
        /** Seconds per sample of the ride chart: enough detail on a phone, far less data than 1 s. */
        const val RECORDING_RESOLUTION = 5

        /** Days before and after the ride a workout may be linked from (the web's `MAX_LINK_DAYS`). */
        const val LINK_DAYS = 3L
        const val FIT_MIME_TYPE = "application/vnd.ant.fit"
    }
}
