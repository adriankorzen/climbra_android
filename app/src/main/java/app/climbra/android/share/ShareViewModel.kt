package app.climbra.android.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.data.api.ShareApi
import app.climbra.android.data.api.ShareCreated
import app.climbra.android.data.api.ShareKind
import app.climbra.android.data.api.ShareRequest
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.RiderRepository
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the share sheet shares (spec 028): a ride or a calendar week. */
data class ShareTarget(
    /** [ShareKind]. */
    val kind: String,
    val activityId: Long? = null,
    /** The week's Monday, ISO date. */
    val weekStart: String? = null,
    /** Sent as the subject of the shared link. */
    val title: String,
    /** The ride has power / heart rate: otherwise its switch is not offered. */
    val hasPower: Boolean = true,
    val hasHeartRate: Boolean = true,
) {
    companion object {
        fun ride(activityId: Long, title: String, hasPower: Boolean, hasHeartRate: Boolean) =
            ShareTarget(ShareKind.RIDE, activityId = activityId, title = title, hasPower = hasPower, hasHeartRate = hasHeartRate)

        fun week(monday: String, title: String) = ShareTarget(ShareKind.WEEK, weekStart = monday, title = title)
    }
}

data class ShareUiState(
    /** The sheet is open for it. */
    val target: ShareTarget? = null,
    val hidePower: Boolean = false,
    val hideHeartRate: Boolean = false,
    val creating: Boolean = false,
    /** The last link created in this sheet, for its expiry. */
    val created: ShareCreated? = null,
    /** A new link the screen still has to hand to the share sheet of the system. */
    val pendingLink: ShareCreated? = null,
    val error: UiText? = null,
)

/**
 * The share sheet of the ride detail and the calendar (spec 028): what it hides, and a public link created by the
 * backend (cyclingapp spec 062). The image is drawn by the screen from what it shows.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: ShareApi,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = mutableState.asStateFlow()

    /** Opens the sheet with the switches off. */
    fun open(target: ShareTarget) {
        mutableState.value = ShareUiState(target = target)
    }

    fun close() {
        mutableState.value = ShareUiState()
    }

    fun setHidePower(hide: Boolean) = mutableState.update { it.copy(hidePower = hide) }

    fun setHideHeartRate(hide: Boolean) = mutableState.update { it.copy(hideHeartRate = hide) }

    fun createLink() {
        val current = state.value
        val target = current.target ?: return
        if (current.creating) return
        mutableState.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            val rider = when (val result = riders.load()) {
                is ApiResult.Failure -> {
                    mutableState.update { it.copy(creating = false, error = result.error.toUiText()) }
                    return@launch
                }
                is ApiResult.Success -> result.value
            } ?: run {
                mutableState.update { it.copy(creating = false) }
                return@launch
            }
            val request = ShareRequest(
                kind = target.kind,
                activityId = target.activityId,
                weekStart = target.weekStart,
                hidePower = current.hidePower && target.hasPower,
                hideHeartRate = current.hideHeartRate && target.hasHeartRate,
            )
            val result = apiCall(Network.json) { api.create(rider.id, request) }
            mutableState.update {
                when (result) {
                    is ApiResult.Success -> it.copy(creating = false, created = result.value, pendingLink = result.value)
                    is ApiResult.Failure -> it.copy(creating = false, error = result.error.toUiText())
                }
            }
        }
    }

    /** The screen opened the system's share sheet with [ShareUiState.pendingLink]. */
    fun linkHandedOver() = mutableState.update { it.copy(pendingLink = null) }
}
