package app.climbra.android.coach

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.R
import app.climbra.android.data.api.ActivityReview
import app.climbra.android.ui.common.UiText
import app.climbra.android.data.api.ActivityReviewRequest
import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.api.ConversationType
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.data.net.apiResponseCall
import app.climbra.android.rider.RiderRepository
import app.climbra.android.session.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AskCoachState(
    /** The ride's newest review conversation, `null` without one. */
    val conversationId: Long? = null,
    val readOnly: Boolean = false,
    val review: ActivityReview? = null,
    val limits: CoachLimits = CoachLimits(),
    val sheetOpen: Boolean = false,
    val rpe: Int? = null,
    val note: String = "",
    val language: String = "en",
    val sending: Boolean = false,
    /** An error of the start, shown in the sheet. */
    val error: ApiError? = null,
    /** The conversation to open on the Coach tab: the accepted one, or the ride's existing review. */
    val open: Long? = null,
) {
    /** "Continue review" in place of "Ask the coach". */
    val canContinue: Boolean get() = conversationId != null && !readOnly
}

/**
 * The coach of a ride detail (spec 006): its latest review, and the sheet that starts one. Uses the ride route's
 * `id`, as [app.climbra.android.activities.RideDetailViewModel] does.
 */
@HiltViewModel
class AskCoachViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val riders: RiderRepository,
    private val api: CoachApi,
    private val activity: CoachActivity,
    private val quotas: CoachQuotaRepository,
    session: SessionRepository,
) : ViewModel() {

    private val activityId: Long = checkNotNull(savedState.get<Long>("id")) { "A ride route has an id" }

    private val mutableState = MutableStateFlow(AskCoachState(language = appLanguage(session)))
    val state: StateFlow<AskCoachState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch { coachLimits(quotas, activity).collect { limits -> mutableState.update { it.copy(limits = limits) } } }
        // A review answered or deleted elsewhere shows here
        viewModelScope.launch { activity.changes.collect { load() } }
    }

    fun openSheet() = mutableState.update { it.copy(sheetOpen = true, error = null) }

    /** Closing does not cancel a request being sent: the shell tells when its answer is ready. */
    fun closeSheet() = mutableState.update { it.copy(sheetOpen = false) }

    fun update(change: (AskCoachState) -> AskCoachState) = mutableState.update { change(it).let { next -> next.copy(note = next.note.take(MAX_NOTE)) } }

    /** [free] for the ride's free first review. */
    fun start(free: Boolean) {
        val current = state.value
        if (current.sending || !current.limits.allows(CostKind.REVIEW, free)) return
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val request = ActivityReviewRequest(current.rpe, current.note.trim().ifEmpty { null }, current.language)
            when (val result = apiCall(Network.json) { api.startActivityReview(activityId, request) }) {
                is ApiResult.Success -> {
                    activity.track(result.value)
                    mutableState.update {
                        it.copy(sending = false, open = result.value.id.takeIf { _ -> it.sheetOpen }, sheetOpen = false, rpe = null, note = "")
                    }
                    quotas.reload()
                }
                is ApiResult.Failure -> {
                    val existing = (result.error as? ApiError.Http)?.takeIf { it.code == "COACH_REVIEW_EXISTS" }?.conversationId
                    // The shell says so, over the conversation that opens
                    if (existing != null) activity.notice(UiText(R.string.ask_coach_review_exists))
                    mutableState.update {
                        if (existing != null) it.copy(sending = false, sheetOpen = false, open = existing)
                        else it.copy(sending = false, error = result.error)
                    }
                }
            }
        }
    }

    fun opened() = mutableState.update { it.copy(open = null) }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val rider = (riders.load() as? ApiResult.Success)?.value ?: return@launch
            val conversations = (apiCall(Network.json) { api.conversations(rider.id) } as? ApiResult.Success)?.value ?: return@launch
            // Newest first, as the backend sorts them
            val summary = conversations.firstOrNull { it.type == ConversationType.ACTIVITY_REVIEW && it.activityId == activityId }
            if (summary == null) {
                mutableState.update { it.copy(conversationId = null, readOnly = false, review = null) }
                return@launch
            }
            val conversation = (apiResponseCall(Network.json) { api.conversation(summary.id) } as? ApiResult.Success)?.value?.body()
            mutableState.update {
                it.copy(
                    conversationId = summary.id,
                    readOnly = conversation?.readOnly ?: summary.readOnly,
                    review = conversation?.latestReview,
                )
            }
        }
    }
}
