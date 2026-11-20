package app.climbra.android.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.data.api.ActivityApi
import app.climbra.android.data.api.RideRow
import app.climbra.android.data.api.RideTotals
import app.climbra.android.data.api.Rider
import app.climbra.android.data.net.ApiError
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
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** Period presets of the training log, as the web's `RANGE_PRESETS`; [days] counts today. */
enum class RangePreset(val days: Long?) {
    WEEK(7), FOUR_WEEKS(28), QUARTER(90), YEAR(null), CUSTOM(null),
}

/** A period of whole days, both ends included. */
data class Period(val preset: RangePreset, val from: LocalDate, val to: LocalDate) {
    companion object {
        fun of(preset: RangePreset, today: LocalDate): Period = when (preset) {
            RangePreset.YEAR -> Period(preset, today.withDayOfYear(1), today)
            RangePreset.CUSTOM -> error("A custom period has its own dates")
            else -> Period(preset, today.minusDays(preset.days!! - 1), today)
        }
    }
}

/**
 * The backend's sort columns (`RideList.Column`, spec 012); names and types start ascending, the rest descending, as
 * `RideListController`.
 */
enum class RideColumn(val key: String, val ascendingFirst: Boolean = false) {
    DATE("date"), NAME("name", ascendingFirst = true), TYPE("type", ascendingFirst = true), DURATION("duration"),
    DISTANCE("distance"), ELEVATION("elevation"), AVG_POWER("avgPower"), NP("np"), IF("if"), TSS("tss"),
    AVG_HR("avgHr"), INTERVALS("intervals"),
}

/** The order of the training log; the default is the newest first. */
data class RideSort(val column: RideColumn = RideColumn.DATE, val ascending: Boolean = false) {
    val dir: String get() = if (ascending) "asc" else "desc"

    /** The column again flips the direction; another one starts with its own. */
    fun then(column: RideColumn): RideSort =
        if (column == this.column) copy(ascending = !ascending) else RideSort(column, column.ascendingFirst)
}

data class ActivitiesUiState(
    val period: Period,
    /** What the search field shows; the request follows it after a pause. */
    val text: String = "",
    val sort: RideSort = RideSort(),
    /** The sport type filter, `null` for all. */
    val sport: String? = null,
    /** The sport types of the period, from the backend. */
    val types: List<String> = emptyList(),
    val rows: List<RideRow> = emptyList(),
    val totals: RideTotals? = null,
    val totalRows: Int = 0,
    /** The rider has any ride at all (cyclingapp spec 052). */
    val hasRides: Boolean = true,
    val noRider: Boolean = false,
    /** First page of the current period and search. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: ApiError? = null,
) {
    val hasMore: Boolean get() = rows.size < totalRows
}

/** Training log (spec 003): everything shown is the backend's, page by page. */
@HiltViewModel
class ActivitiesViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: ActivityApi,
    private val changes: DataChanges,
    private val clock: Clock,
    private val intervalsSync: IntervalsSync,
    private val garminSync: GarminSync,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ActivitiesUiState(period = Period.of(RangePreset.FOUR_WEEKS, today())))
    val state: StateFlow<ActivitiesUiState> = mutableState.asStateFlow()

    /** The app's intervals.icu sync (spec 007), shown above the list while it runs. */
    val sync: StateFlow<SyncState> = combine(intervalsSync.state, garminSync.running) { sync, garmin -> sync.copy(garminRunning = garmin) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, intervalsSync.state.value)

    /** A pull waits for the rider of its reload before it starts a sync. */
    private var syncAfterLoad = false

    private var rider: Rider? = null
    private var request: Job? = null
    private var search: Job? = null
    /** Bumped by changes to rides: shown next to the ride or workout changed (spec 019), the screen loads again. */
    val dataVersion: StateFlow<Int> get() = changes.version

    private var loadedVersion = changes.version.value

    /** The text the current rows were asked for. */
    private var query = ""

    init {
        reload()
        // A sync brought rides: they show at once, also while the list is shown
        viewModelScope.launch { merge(intervalsSync.imported.drop(1), garminSync.imported.drop(1)).collect { reload() } }
    }

    fun selectPreset(preset: RangePreset) {
        if (preset == RangePreset.CUSTOM) return
        mutableState.update { it.copy(period = Period.of(preset, today())) }
        reload()
    }

    fun selectCustom(from: LocalDate, to: LocalDate) {
        if (from.isAfter(to)) return
        mutableState.update { it.copy(period = Period(RangePreset.CUSTOM, from, to)) }
        reload()
    }

    /** Spec 012: the order of the rides; the current column again flips it. */
    fun selectSort(column: RideColumn) {
        mutableState.update { it.copy(sort = it.sort.then(column)) }
        reload()
    }

    /** Spec 012: one sport type, or `null` for all. */
    fun selectSport(type: String?) {
        if (type == state.value.sport) return
        mutableState.update { it.copy(sport = type) }
        reload()
    }

    /** The request follows [SEARCH_DELAY] after the last change. */
    fun onTextChange(text: String) {
        mutableState.update { it.copy(text = text) }
        search?.cancel()
        search = viewModelScope.launch {
            delay(SEARCH_DELAY)
            if (text.trim() != query) reload()
        }
    }

    /** The list nears its end. */
    fun loadMore() {
        val current = state.value
        if (!current.hasMore || current.loadingMore || current.loading || request?.isActive == true) return
        load(page = current.rows.size / PAGE_SIZE, append = true)
    }

    /** Pull to refresh and Retry: the list again, and new rides from intervals.icu when connected (spec 007). */
    fun refresh() {
        mutableState.update { it.copy(refreshing = true) }
        syncAfterLoad = true
        reload()
    }

    /** The tab or the list is shown again: rides changed elsewhere (deleted, reanalysed) load again. */
    fun onShown() {
        if (changes.version.value != loadedVersion) reload()
    }

    private fun reload() {
        search?.cancel()
        request?.cancel()
        mutableState.update { it.copy(loading = !it.refreshing, error = null) }
        load(page = 0, append = false)
    }

    private fun load(page: Int, append: Boolean) {
        if (append) mutableState.update { it.copy(loadingMore = true, error = null) }
        val period = state.value.period
        val text = state.value.text.trim()
        val sort = state.value.sort
        val sport = state.value.sport
        request = viewModelScope.launch {
            val rider = rider ?: when (val result = riders.load()) {
                is ApiResult.Failure -> return@launch failed(result.error)
                is ApiResult.Success -> result.value?.also { rider = it }
                    ?: return@launch mutableState.update { it.copy(noRider = true, loading = false, refreshing = false) }
            }
            if (syncAfterLoad) {
                syncAfterLoad = false
                pullSync(rider.id)
            }
            loadedVersion = changes.version.value
            val result = apiCall(Network.json) {
                api.rides(
                    riderId = rider.id,
                    from = period.from.toString(),
                    to = period.to.toString(),
                    text = text.ifEmpty { null },
                    type = sport,
                    sort = sort.column.key,
                    dir = sort.dir,
                    page = page,
                    size = PAGE_SIZE,
                )
            }
            when (result) {
                is ApiResult.Failure -> failed(result.error)
                is ApiResult.Success -> {
                    query = text
                    val list = result.value
                    mutableState.update {
                        it.copy(
                            rows = if (append) it.rows + list.rows else list.rows,
                            totals = list.totals,
                            totalRows = list.totalRows,
                            types = list.types,
                            hasRides = list.hasRides,
                            noRider = false,
                            loading = false,
                            refreshing = false,
                            loadingMore = false,
                        )
                    }
                }
            }
        }
    }

    private fun failed(error: ApiError) {
        syncAfterLoad = false
        mutableState.update { it.copy(error = error, loading = false, refreshing = false, loadingMore = false) }
    }

    // LocalDate.ofInstant needs API 34; the app supports API 29
    private fun today(): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

    /** A pull syncs intervals.icu (spec 007) and, for an admin, Garmin (spec 011). */
    private fun pullSync(riderId: Long) {
        intervalsSync.sync(riderId, SyncOrigin.PULL)
        garminSync.sync(riderId, SyncOrigin.PULL)
    }

    companion object {
        const val PAGE_SIZE = 50
        val SEARCH_DELAY = 300.milliseconds
    }
}
