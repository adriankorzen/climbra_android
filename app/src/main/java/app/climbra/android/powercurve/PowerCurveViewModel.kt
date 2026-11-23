package app.climbra.android.powercurve

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.R
import app.climbra.android.data.api.PowerCurveResponse
import app.climbra.android.data.api.RiderApi
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The periods of the power curve (spec 026), as the backend's `days` (cyclingapp spec 061). */
enum class CurvePeriod(val days: String, @StringRes val label: Int) {
    DAYS_28("28", R.string.power_curve_period_28),
    DAYS_90("90", R.string.power_curve_period_90),
    YEAR("365", R.string.power_curve_period_365),
    ALL("all", R.string.power_curve_period_all),
}

data class PowerCurveUiState(
    /** A load runs with nothing of the period shown; later loads are [refreshing]. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val period: CurvePeriod = CurvePeriod.DAYS_90,
    /** The backend's curve of [period]; the app computes none of its figures. */
    val curve: PowerCurveResponse? = null,
    val error: UiText? = null,
    /** The account has no rider profile yet. */
    val noRider: Boolean = false,
)

/** The power curve (spec 026): loads the chosen period; the period survives a configuration change. */
@HiltViewModel
class PowerCurveViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val riders: RiderRepository,
    private val api: RiderApi,
    private val changes: DataChanges,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        PowerCurveUiState(period = savedState.get<String>(PERIOD)?.let { name -> CurvePeriod.entries.firstOrNull { it.name == name } } ?: CurvePeriod.DAYS_90),
    )
    val state: StateFlow<PowerCurveUiState> = mutableState.asStateFlow()

    /** Bumped by changes to rides, and by the app coming back online (spec 018). */
    val dataVersion: StateFlow<Int> get() = changes.version

    private var loadJob: Job? = null
    private var loadedVersion = changes.version.value

    init {
        load()
    }

    fun select(period: CurvePeriod) {
        if (period == state.value.period && state.value.curve != null) return
        savedState[PERIOD] = period.name
        loadJob?.cancel()
        // Another period's curve is not shown meanwhile
        mutableState.update { it.copy(period = period, curve = null, loading = true, error = null) }
        load()
    }

    /** Pull to refresh and Retry: the period again (no sync, the training log's pull syncs). */
    fun refresh() {
        if (loadJob?.isActive == true) return
        load()
    }

    /** Shown again after rides changed elsewhere. */
    fun onShown() {
        if (changes.version.value != loadedVersion && loadJob?.isActive != true) load()
    }

    private fun load() {
        mutableState.update { it.copy(refreshing = it.curve != null, loading = it.curve == null, error = null) }
        loadedVersion = changes.version.value
        val period = state.value.period
        loadJob = viewModelScope.launch {
            val rider = when (val result = riders.load()) {
                is ApiResult.Failure -> {
                    mutableState.update { it.copy(loading = false, refreshing = false, error = result.error.toUiText()) }
                    return@launch
                }
                is ApiResult.Success -> result.value
            }
            if (rider == null) {
                mutableState.update { it.copy(loading = false, refreshing = false, noRider = true) }
                return@launch
            }
            val result = apiCall(Network.json) { api.powerCurve(rider.id, period.days) }
            mutableState.update {
                when (result) {
                    is ApiResult.Success -> it.copy(loading = false, refreshing = false, curve = result.value, noRider = false)
                    is ApiResult.Failure -> it.copy(loading = false, refreshing = false, error = result.error.toUiText())
                }
            }
        }
    }

    private companion object {
        const val PERIOD = "period"
    }
}
