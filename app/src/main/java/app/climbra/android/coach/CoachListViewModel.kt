package app.climbra.android.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.api.CoachConversationSummary
import app.climbra.android.data.api.CoachQuota
import app.climbra.android.data.api.ConversationType
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.RiderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The credits and the running request of the account, together. */
fun coachLimits(quotas: CoachQuotaRepository, activity: CoachActivity): Flow<CoachLimits> =
    combine(quotas.quota, activity.atCapacity) { quota, atCapacity -> CoachLimits(quota, atCapacity) }

/** The filter chips of the list; "All" also shows plan adjustments (as on the web). */
enum class ConversationFilter(val type: String?) {
    ALL(null),
    ACTIVITY_REVIEW(ConversationType.ACTIVITY_REVIEW),
    FILE_REVIEW(ConversationType.FILE_REVIEW),
    TRAINING_BLOCK(ConversationType.TRAINING_BLOCK),
}

sealed interface CoachListMessage {
    data object Deleted : CoachListMessage
    data class Failed(val error: ApiError) : CoachListMessage
}

data class CoachListUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: ApiError? = null,
    val noRider: Boolean = false,
    /** Newest first, as the backend sorts them. */
    val conversations: List<CoachConversationSummary> = emptyList(),
    val filter: ConversationFilter = ConversationFilter.ALL,
    val quota: CoachQuota? = null,
    /** The conversation the deletion is asked for. */
    val deleting: CoachConversationSummary? = null,
    val busy: Boolean = false,
    val message: CoachListMessage? = null,
) {
    val shown: List<CoachConversationSummary>
        get() = filter.type?.let { type -> conversations.filter { it.type == type } } ?: conversations

    /** The welcome with the three ways to start: the rider has no conversation at all. */
    val empty: Boolean get() = !loading && error == null && !noRider && conversations.isEmpty()
}

/** The Coach tab's list (spec 006); it loads again after every change of the conversations ([CoachActivity.changes]). */
@HiltViewModel
class CoachListViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: CoachApi,
    private val activity: CoachActivity,
    quotas: CoachQuotaRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(CoachListUiState())
    val state: StateFlow<CoachListUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var riderId: Long? = null

    init {
        viewModelScope.launch { activity.changes.collect { load() } }
        viewModelScope.launch { quotas.quota.collect { quota -> mutableState.update { it.copy(quota = quota) } } }
    }

    fun refresh() = load()

    fun selectFilter(filter: ConversationFilter) = mutableState.update { it.copy(filter = filter) }

    fun askDelete(conversation: CoachConversationSummary) = mutableState.update { it.copy(deleting = conversation) }

    fun cancelDelete() = mutableState.update { it.copy(deleting = null) }

    fun confirmDelete() {
        val conversation = state.value.deleting ?: return
        mutableState.update { it.copy(deleting = null, busy = true) }
        viewModelScope.launch {
            when (val result = apiCall(Network.json) { api.deleteConversation(conversation.id) }) {
                is ApiResult.Success -> {
                    mutableState.update { it.copy(busy = false, message = CoachListMessage.Deleted) }
                    // Reloads this list and the others
                    activity.changed()
                }
                is ApiResult.Failure -> mutableState.update { it.copy(busy = false, message = CoachListMessage.Failed(result.error)) }
            }
        }
    }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    private fun load() {
        loadJob?.cancel()
        mutableState.update { it.copy(refreshing = !it.loading, error = null) }
        loadJob = viewModelScope.launch {
            val id = riderId ?: when (val rider = riders.load()) {
                is ApiResult.Failure -> return@launch failed(rider.error)
                is ApiResult.Success -> rider.value?.id
                    ?: return@launch mutableState.update { it.copy(loading = false, refreshing = false, noRider = true) }
            }
            riderId = id
            when (val result = apiCall(Network.json) { api.conversations(id) }) {
                is ApiResult.Failure -> failed(result.error)
                is ApiResult.Success -> {
                    activity.trackBusy(result.value)
                    mutableState.update {
                        it.copy(loading = false, refreshing = false, noRider = false, conversations = result.value)
                    }
                }
            }
        }
    }

    private fun failed(error: ApiError) = mutableState.update { it.copy(loading = false, refreshing = false, error = error) }
}
