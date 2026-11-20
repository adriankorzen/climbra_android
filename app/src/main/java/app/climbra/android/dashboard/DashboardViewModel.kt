package app.climbra.android.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.coach.CoachActivity
import app.climbra.android.data.api.AnalysisSummary
import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.api.CoachConversationSummary
import app.climbra.android.data.api.Rider
import app.climbra.android.data.api.RiderApi
import app.climbra.android.data.api.TrainingLoadResponse
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.garmin.GarminSync
import app.climbra.android.intervals.IntervalsSync
import app.climbra.android.intervals.SyncOrigin
import app.climbra.android.intervals.SyncState
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Visible days of the performance management chart, as on the web. */
val PMC_RANGES = listOf(42, 90, 180, 365)

data class DashboardUiState(
    /** The first load; later loads are [refreshing]. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** A request failed: what was loaded before stays, with the error above it. */
    val failed: Boolean = false,
    /** The account has no rider profile yet. */
    val noRider: Boolean = false,
    val rider: Rider? = null,
    /** The backend's training load (cyclingapp spec 051); the app computes none of it. */
    val load: TrainingLoadResponse? = null,
    /** The latest analysed rides, newest first. */
    val rides: List<AnalysisSummary> = emptyList(),
    /** The rider has no ride at all, analysed or not. */
    val noRidesAtAll: Boolean = false,
    /** One of [PMC_RANGES]. */
    val range: Int = DEFAULT_RANGE,
    /** The 3 newest coach conversations (spec 006), `null` until loaded. */
    val conversations: List<CoachConversationSummary>? = null,
) {
    /** The tail of the returned days that the chart shows. */
    val visibleDays get() = load?.days.orEmpty().takeLast(range)

    companion object {
        const val DEFAULT_RANGE = 90
    }
}

/** Dashboard (spec 002): loads the backend's figures and keeps them; formatting and drawing are the screen's. */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: RiderApi,
    private val clock: Clock,
    private val changes: DataChanges,
    private val coachApi: CoachApi,
    coachActivity: CoachActivity,
    private val intervalsSync: IntervalsSync,
    private val garminSync: GarminSync,
) : ViewModel() {

    private val mutableState = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = mutableState.asStateFlow()

    /** The app's intervals.icu sync (spec 007), shown above the content while it runs. */
    val sync: StateFlow<SyncState> = combine(intervalsSync.state, garminSync.running) { sync, garmin -> sync.copy(garminRunning = garmin) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, intervalsSync.state.value)

    private var loadJob: Job? = null
    private var loadedAt: Instant? = null
    /** Bumped by changes to rides, and by the app coming back online (spec 018): the shown dashboard loads again. */
    val dataVersion: StateFlow<Int> get() = changes.version

    private var loadedVersion = changes.version.value

    init {
        load()
        // A coach request accepted or ended, a conversation deleted: the coach card loads again
        viewModelScope.launch { coachActivity.changes.drop(1).collect { state.value.rider?.let { loadConversations(it.id) } } }
        // A sync brought rides: they show at once, also while the dashboard is shown
        // (a load still running may predate the import, so it starts again)
        viewModelScope.launch {
            merge(intervalsSync.imported.drop(1), garminSync.imported.drop(1)).collect {
                loadJob?.cancel()
                load()
            }
        }
    }

    private fun loadConversations(riderId: Long) = viewModelScope.launch {
        (call { coachApi.conversations(riderId) } as? ApiResult.Success)?.let { result ->
            mutableState.update { it.copy(conversations = result.value.take(COACH_CONVERSATIONS)) }
        }
    }

    /** Pull to refresh and Retry: the data again, and new rides from intervals.icu when connected (spec 007). */
    fun refresh() = load(pull = true)

    /**
     * The tab is shown again: figures older than [STALE_AFTER], or from before a change of the rider, the thresholds
     * or the rides elsewhere ([DataChanges], e.g. the profile, spec 004), are loaded again.
     */
    fun onShown() {
        val loaded = loadedAt ?: return
        if (Duration.between(loaded, clock.instant()) > STALE_AFTER || changes.version.value != loadedVersion) {
            load()
        }
    }

    /** Another range is a longer or shorter tail of the loaded days: no request. */
    fun selectRange(days: Int) {
        if (days in PMC_RANGES) {
            mutableState.update { it.copy(range = days) }
        }
    }

    private fun load(pull: Boolean = false) {
        if (loadJob?.isActive == true) {
            if (pull) state.value.rider?.let { pullSync(it.id) }
            return
        }
        mutableState.update { it.copy(refreshing = !it.loading, failed = false) }
        loadJob = viewModelScope.launch {
            loadedVersion = changes.version.value
            val result = fetch(pull)
            loadedAt = clock.instant()
            mutableState.update { result(it).copy(loading = false, refreshing = false) }
        }
    }

    /** @return how the loaded data changes the state */
    private suspend fun fetch(pull: Boolean): (DashboardUiState) -> DashboardUiState {
        val rider = when (val result = riders.load()) {
            is ApiResult.Failure -> return { it.copy(failed = true) }
            is ApiResult.Success -> result.value ?: return { DashboardUiState(noRider = true, range = it.range) }
        }
        if (pull) pullSync(rider.id)
        val (load, rides, conversations) = coroutineScope {
            val load = async { call { api.trainingLoad(rider.id, PMC_RANGES.last(), WEEKS) } }
            val rides = async { call { api.latestAnalyses(rider.id, RECENT_RIDES) } }
            val conversations = async { call { coachApi.conversations(rider.id) } }
            Triple(load.await(), rides.await(), conversations.await())
        }
        val recent = (rides as? ApiResult.Success)?.value?.sortedByDescending { it.startTime }
        // Only a history without analysed rides needs the list of all rides
        val noRidesAtAll = recent?.isEmpty() == true &&
            (call { api.activities(rider.id) } as? ApiResult.Success)?.value?.isEmpty() == true
        return { previous ->
            previous.copy(
                noRider = false,
                rider = rider,
                load = (load as? ApiResult.Success)?.value ?: previous.load,
                rides = recent ?: previous.rides,
                noRidesAtAll = noRidesAtAll,
                conversations = (conversations as? ApiResult.Success)?.value?.take(COACH_CONVERSATIONS) ?: previous.conversations,
                failed = load is ApiResult.Failure || rides is ApiResult.Failure,
            )
        }
    }

    private suspend fun <T> call(request: suspend () -> T) = apiCall(Network.json, request)

    /** A pull syncs intervals.icu (spec 007) and, for an admin, Garmin (spec 011). */
    private fun pullSync(riderId: Long) {
        intervalsSync.sync(riderId, SyncOrigin.PULL)
        garminSync.sync(riderId, SyncOrigin.PULL)
    }

    companion object {
        const val WEEKS = 12
        const val RECENT_RIDES = 7
        const val COACH_CONVERSATIONS = 3
        val STALE_AFTER: Duration = Duration.ofMinutes(5)
    }
}
