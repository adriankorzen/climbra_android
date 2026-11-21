package app.climbra.android.coach

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.api.CoachConversation
import app.climbra.android.data.api.ConversationType
import app.climbra.android.data.api.PendingCoachRequest
import app.climbra.android.data.api.PlanProposal
import app.climbra.android.data.api.SavePlanRequest
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.data.net.apiResponseCall
import app.climbra.android.rider.DataChanges
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

enum class ConversationTab { CHAT, PANEL }

/** A request the backend refused at once (busy, no credits, …): nothing was stored, so it stays only here. */
data class RefusedRequest(val text: String, val file: PickedFile?, val error: ApiError)

sealed interface ConversationMessage {
    data object PlanAdded : ConversationMessage
    data object PlanUpdated : ConversationMessage

    /** Spec 008: the proposal was accepted, rejected; the review's workout went into the plan. */
    data object ProposalAccepted : ConversationMessage
    data object ProposalRejected : ConversationMessage
    data object WorkoutReplaced : ConversationMessage
    data class Failed(val error: ApiError) : ConversationMessage
}

data class ConversationUiState(
    val loading: Boolean = true,
    /** `404` / `403`: "Conversation not found". */
    val notFound: Boolean = false,
    val error: ApiError? = null,
    val conversation: CoachConversation? = null,
    /** The server's clock minus the device's, from the `Date` header: the elapsed time is the server's. */
    val serverOffsetMillis: Long = 0,
    val limits: CoachLimits = CoachLimits(),
    val draft: String = "",
    val file: PickedFile? = null,
    /** The chosen file is not a FIT / ZIP file. */
    val fileError: Boolean = false,
    /** A message, retry or deletion is being sent (the short request, not the model's answer). */
    val submitting: Boolean = false,
    val refused: RefusedRequest? = null,
    val tab: ConversationTab = ConversationTab.CHAT,
    /** An answer to a request sent from here changed the review or the block. */
    val updated: Boolean = false,
    val savingPlan: Boolean = false,
    /** The plan the training block overlaps, while the rider decides whether to replace it. */
    val overlap: String? = null,
    /** Asking whether to delete the conversation. */
    val confirmingDelete: Boolean = false,
    /** The conversation was deleted: the screen goes back to the list. */
    val deleted: Boolean = false,
    val message: ConversationMessage? = null,
    /** The latest proposal of a `PLAN_ADJUSTMENT` conversation (spec 008), `null` until loaded. */
    val proposal: PlanProposal? = null,
    val proposalError: ApiError? = null,
    /** An accept or reject is being sent. */
    val deciding: Boolean = false,
    /** Asking whether to reject the proposal. */
    val confirmingReject: Boolean = false,
    /** The review's workout is being put into the plan. */
    val replacing: Boolean = false,
) {
    val busy: Boolean get() = conversation?.busy == true
    val pending: PendingCoachRequest? get() = conversation?.pendingRequest
    val hasPanel: Boolean
        get() = conversation?.let { it.latestReview != null || it.latestTrainingBlock != null || hasProposal } == true

    /** A plan adjustment with a proposal to show (spec 008). */
    val hasProposal: Boolean
        get() = conversation?.let { it.type == ConversationType.PLAN_ADJUSTMENT && it.latestProposal != null && it.planId != null } == true

    /** Nothing can be sent: read-only, the coach answers, a request is being sent, or another request runs. */
    val locked: Boolean get() = conversation?.readOnly != false || busy || submitting || limits.atCapacity

    val messageKind: CostKind get() = messageCost(conversation?.type ?: ConversationType.ACTIVITY_REVIEW)

    /** The retry costs what the failed request did: a start or a follow-up. */
    val retryKind: CostKind
        get() {
            val type = conversation?.type ?: ConversationType.ACTIVITY_REVIEW
            return if (pending?.kind == PendingCoachRequest.KIND_START) startCost(type) else messageCost(type)
        }

    /** The input takes text and a file. */
    val inputEnabled: Boolean get() = !locked && limits.quota.covers(messageKind)
    val canSend: Boolean get() = inputEnabled && draft.isNotBlank()
    val canRetry: Boolean get() = !locked && limits.quota.covers(retryKind)

    /** The message under which the block's call-to-action stays (cyclingapp spec 021). */
    val blockMessageId: Long?
        get() {
            val c = conversation ?: return null
            if (c.type != ConversationType.TRAINING_BLOCK || c.latestTrainingBlock == null) return null
            return c.messages.lastOrNull { it.trainingBlock }?.id
        }
}

/**
 * A conversation with the coach (spec 006): its messages and panel, follow-ups, the request running in the background
 * (loaded every [POLL_MILLIS] while shown) or failed, refused requests and saving a training block to the calendar.
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: CoachApi,
    private val activity: CoachActivity,
    private val quotas: CoachQuotaRepository,
    private val files: RideFileSource,
    private val changes: DataChanges,
    private val clock: Clock,
) : ViewModel() {

    /** The conversation shown; its notification is cleared when it opens (spec 020). */
    val id: Long = checkNotNull(savedState.get<Long>("id")) { "A conversation route has an id" }

    /** The calendar's proposal banner opens the proposal tab (spec 008). */
    private val mutableState = MutableStateFlow(
        ConversationUiState(tab = if (savedState.get<String>("tab") == ConversationTab.PANEL.name) ConversationTab.PANEL else ConversationTab.CHAT),
    )
    val state: StateFlow<ConversationUiState> = mutableState.asStateFlow()

    /** Polls while the screen is shown and the coach answers. */
    private var pollJob: Job? = null
    private var unwatch: (() -> Unit)? = null
    private var shown = false

    /** Review and block before a request sent from here, to flag the panel when the answer changed them. */
    private var panelBefore: Any? = null
    private var updatedJob: Job? = null

    internal var pollMillis = POLL_MILLIS

    init {
        viewModelScope.launch { coachLimits(quotas, activity).collect { limits -> mutableState.update { it.copy(limits = limits) } } }
        viewModelScope.launch { load() }
    }

    /** The screen is shown (started) or not; only a shown conversation is loaded here while busy. */
    fun setShown(value: Boolean) {
        if (value == shown) return
        shown = value
        if (value) {
            unwatch = activity.watch(id)
            schedulePoll()
        } else {
            unwatch?.invoke()
            unwatch = null
            pollJob?.cancel()
        }
    }

    fun retryLoad() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch { load() }
    }

    fun onDraftChange(text: String) = mutableState.update { it.copy(draft = text.take(MAX_MESSAGE)) }

    fun attach(file: PickedFile) = mutableState.update {
        it.copy(file = file.takeIf { f -> f.rideFile }, fileError = !file.rideFile)
    }

    fun removeFile() = mutableState.update { it.copy(file = null, fileError = false) }

    fun selectTab(tab: ConversationTab) = mutableState.update { it.copy(tab = tab) }

    fun send() {
        val current = state.value
        val text = current.draft.trim()
        if (text.isEmpty() || !current.canSend) return
        mutableState.update { it.copy(draft = "", file = null, fileError = false) }
        submit(text, current.file)
    }

    /** Sends a refused message again. */
    fun sendAgain() {
        val refused = state.value.refused ?: return
        if (!state.value.locked) submit(refused.text, refused.file)
    }

    /** A refused message, or the text of a failed follow-up, back into the input. */
    fun edit() {
        val refused = state.value.refused
        mutableState.update {
            if (refused != null) it.copy(draft = refused.text, file = refused.file, refused = null)
            else it.copy(draft = it.pending?.text.orEmpty())
        }
    }

    /** Runs the failed request again with the same prompt and files. */
    fun retry() {
        val current = state.value
        if (current.conversation == null || !current.canRetry) return
        accept(RefusedRequest("", null, ApiError.Unexpected)) { api.retry(id) }
    }

    fun askDelete() = mutableState.update { it.copy(confirmingDelete = true) }

    fun cancelDelete() = mutableState.update { it.copy(confirmingDelete = false) }

    /** Deletes the conversation (from the menu, or a failed start that has nothing worth keeping). */
    fun delete() {
        if (state.value.submitting) return
        mutableState.update { it.copy(submitting = true, confirmingDelete = false) }
        viewModelScope.launch {
            when (val result = apiCall(Network.json) { api.deleteConversation(id) }) {
                is ApiResult.Success -> {
                    activity.changed()
                    mutableState.update { it.copy(submitting = false, deleted = true) }
                }
                is ApiResult.Failure -> mutableState.update {
                    it.copy(submitting = false, refused = RefusedRequest("", null, result.error))
                }
            }
        }
    }

    /** Saves the training block to the calendar, or updates its plan; [replaceOverlapping] after the rider agreed. */
    fun savePlan(replaceOverlapping: Boolean = false) {
        val conversation = state.value.conversation ?: return
        if (state.value.savingPlan || conversation.busy) return
        val updating = conversation.plan != null
        mutableState.update { it.copy(savingPlan = true) }
        viewModelScope.launch {
            when (val result = apiCall(Network.json) { api.savePlan(id, SavePlanRequest(replaceOverlapping)) }) {
                is ApiResult.Success -> {
                    changes.changed()
                    mutableState.update {
                        it.copy(
                            savingPlan = false,
                            overlap = null,
                            message = if (updating) ConversationMessage.PlanUpdated else ConversationMessage.PlanAdded,
                        )
                    }
                    load()
                }
                is ApiResult.Failure -> {
                    val error = result.error as? ApiError.Http
                    mutableState.update {
                        if (!replaceOverlapping && error?.code == "PLAN_OVERLAP" && error.overlappingPlanName != null) {
                            it.copy(savingPlan = false, overlap = error.overlappingPlanName)
                        } else {
                            it.copy(savingPlan = false, overlap = null, message = ConversationMessage.Failed(result.error))
                        }
                    }
                }
            }
        }
    }

    fun cancelOverlap() = mutableState.update { it.copy(overlap = null) }

    // Spec 008: the proposal of a plan adjustment, and the review's change of the plan

    fun retryProposal() = viewModelScope.launch { loadProposal() }

    fun acceptProposal() = decide(ConversationMessage.ProposalAccepted) { rider, plan, proposal -> api.acceptProposal(rider, plan, proposal) }

    fun askReject() = mutableState.update { it.copy(confirmingReject = true) }

    fun cancelReject() = mutableState.update { it.copy(confirmingReject = false) }

    fun rejectProposal() {
        mutableState.update { it.copy(confirmingReject = false) }
        decide(ConversationMessage.ProposalRejected) { rider, plan, proposal -> api.rejectProposal(rider, plan, proposal) }
    }

    /** Accepts or rejects; an outdated proposal tells why. Either way the proposal loads again. */
    private fun decide(done: ConversationMessage, request: suspend (Long, Long, Long) -> Unit) {
        val current = state.value
        val conversation = current.conversation ?: return
        val proposal = current.proposal ?: return
        val riderId = conversation.riderId ?: return
        if (current.deciding || proposal.status != PlanProposal.STATUS_PENDING) return
        mutableState.update { it.copy(deciding = true) }
        viewModelScope.launch {
            val result = apiCall(Network.json) { request(riderId, proposal.planId, proposal.id) }
            when (result) {
                is ApiResult.Success -> {
                    changes.changed()
                    mutableState.update { it.copy(message = done) }
                }
                is ApiResult.Failure -> mutableState.update { it.copy(message = ConversationMessage.Failed(result.error)) }
            }
            loadProposal()
            mutableState.update { it.copy(deciding = false) }
        }
    }

    private suspend fun loadProposal() {
        val conversation = state.value.conversation ?: return
        val latest = conversation.latestProposal ?: return
        val planId = conversation.planId ?: return
        val riderId = conversation.riderId ?: return
        mutableState.update { it.copy(proposalError = null) }
        when (val result = apiCall(Network.json) { api.proposal(riderId, planId, latest.id) }) {
            is ApiResult.Success -> mutableState.update { it.copy(proposal = result.value) }
            is ApiResult.Failure -> mutableState.update { it.copy(proposalError = result.error) }
        }
    }

    /** Puts the review's next session into the plan in place of the planned workout (cyclingapp spec 020). */
    fun replaceInPlan() {
        if (state.value.replacing || state.value.busy) return
        mutableState.update { it.copy(replacing = true) }
        viewModelScope.launch {
            when (val result = apiCall(Network.json) { api.applyReviewPlanChange(id) }) {
                is ApiResult.Success -> {
                    changes.changed()
                    mutableState.update { it.copy(message = ConversationMessage.WorkoutReplaced) }
                    load()
                }
                is ApiResult.Failure -> mutableState.update { it.copy(message = ConversationMessage.Failed(result.error)) }
            }
            mutableState.update { it.copy(replacing = false) }
        }
    }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    private fun submit(text: String, file: PickedFile?) {
        mutableState.update { it.copy(refused = null) }
        accept(RefusedRequest(text, file, ApiError.Unexpected)) {
            api.sendMessage(id, text.toRequestBody(TEXT), file?.let { files.part("file", it) })
        }
    }

    /** The backend accepted the request: the conversation is busy and followed until the answer is there. */
    private fun accept(message: RefusedRequest, request: suspend () -> CoachConversation) {
        val before = state.value.conversation
        mutableState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            when (val result = apiCall(Network.json, request)) {
                is ApiResult.Success -> {
                    panelBefore = before?.let(::panel)
                    mutableState.update { it.copy(submitting = false, conversation = result.value) }
                    activity.track(result.value)
                    quotas.reload()
                    schedulePoll()
                }
                is ApiResult.Failure -> mutableState.update {
                    it.copy(submitting = false, refused = message.copy(error = result.error))
                }
            }
        }
    }

    private suspend fun load() {
        when (val result = apiResponseCall(Network.json) { api.conversation(id) }) {
            is ApiResult.Failure -> {
                val status = (result.error as? ApiError.Http)?.status
                mutableState.update {
                    if (status == 403 || status == 404) it.copy(loading = false, notFound = true)
                    else it.copy(loading = false, error = result.error.takeIf { _ -> it.conversation == null })
                }
            }
            is ApiResult.Success -> {
                val response = result.value
                val conversation = response.body()!!
                val serverTime = response.headers().getDate("Date")?.time
                mutableState.update {
                    it.copy(
                        loading = false,
                        error = null,
                        conversation = conversation,
                        serverOffsetMillis = serverTime?.let { time -> time - clock.millis() } ?: it.serverOffsetMillis,
                    )
                }
                // A new proposal (a follow-up made one) or one decided elsewhere loads again
                val latest = conversation.latestProposal
                val loaded = state.value.proposal
                if (latest != null && (loaded == null || loaded.id != latest.id || (latest.status != null && loaded.status != latest.status))) {
                    loadProposal()
                }
                if (conversation.busy) {
                    activity.track(conversation)
                    schedulePoll()
                } else {
                    activity.seen(conversation)
                    flagPanelChange(conversation)
                }
            }
        }
    }

    private fun flagPanelChange(conversation: CoachConversation) {
        val before = panelBefore ?: return
        panelBefore = null
        if (panel(conversation) == before) return
        mutableState.update { it.copy(updated = true) }
        updatedJob?.cancel()
        updatedJob = viewModelScope.launch {
            delay(UPDATED_MILLIS)
            mutableState.update { it.copy(updated = false) }
        }
    }

    private fun schedulePoll() {
        if (!shown || pollJob?.isActive == true || !state.value.busy) return
        pollJob = viewModelScope.launch {
            while (state.value.busy) {
                delay(pollMillis)
                load()
            }
        }
    }

    override fun onCleared() {
        unwatch?.invoke()
    }

    private fun panel(conversation: CoachConversation): Any =
        Triple(conversation.latestReview, conversation.latestTrainingBlock, conversation.latestProposal?.id)

    companion object {
        const val POLL_MILLIS = 5_000L
        const val UPDATED_MILLIS = 4_000L
        const val MAX_MESSAGE = 10_000
        private val TEXT = "text/plain; charset=utf-8".toMediaType()
    }
}
