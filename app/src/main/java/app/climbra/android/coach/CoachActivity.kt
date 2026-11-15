package app.climbra.android.coach

import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.api.CoachConversation
import app.climbra.android.data.api.CoachConversationSummary
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.data.net.apiResponseCall
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.ui.common.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A followed request ended; the shell tells it in a snackbar with "Open". */
data class CoachFinished(val conversationId: Long, val title: String, val failed: Boolean)

/**
 * The coach requests of the account running in the background (spec 006, the web's `CoachActivity`). Lives as long
 * as the app, so a request is followed after its screen was left: while the app is in the [foreground] it loads every
 * running conversation that no screen shows every [pollMillis]; when one ends, the credits reload, [finished] is
 * bumped (lists reload) and, unless the conversation is shown, [events] tells it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachActivity(
    private val api: CoachApi,
    private val quotas: CoachQuotaRepository,
    session: SessionRepository,
    foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val pollMillis: Long = POLL_MILLIS,
) {
    /** Titles of the busy conversations by id. */
    private val mutableRunning = MutableStateFlow<Map<Long, String>>(emptyMap())
    val running: StateFlow<Map<Long, String>> = mutableRunning.asStateFlow()

    private val mutableAtCapacity = MutableStateFlow(false)

    /** The next request would be refused (`COACH_BUSY`, cyclingapp spec 015): one runs already. */
    val atCapacity: StateFlow<Boolean> = mutableAtCapacity.asStateFlow()

    private val mutableFinished = MutableStateFlow(0)

    /** Bumped when a request ended, so lists of conversations reload. */
    val finished: StateFlow<Int> = mutableFinished.asStateFlow()

    private val mutableChanges = MutableStateFlow(0)

    /** Bumped when the account's conversations changed (a request accepted or ended, a deletion): lists reload. */
    val changes: StateFlow<Int> = mutableChanges.asStateFlow()

    private val channel = Channel<CoachFinished>(Channel.BUFFERED)
    val events: Flow<CoachFinished> = channel.receiveAsFlow()

    private val noticeChannel = Channel<UiText>(Channel.BUFFERED)

    /** Short notices of the coach for the shell's snackbar, e.g. that a ride has a review already. */
    val notices: Flow<UiText> = noticeChannel.receiveAsFlow()

    fun notice(text: UiText) {
        noticeChannel.trySend(text)
    }

    /** Conversations a screen shows; it loads them itself while they are busy. */
    private val watched = MutableStateFlow<Map<Long, Int>>(emptyMap())

    init {
        scope.launch {
            combine(mutableRunning, watched, foreground) { running, shown, visible ->
                visible && running.keys.any { it !in shown }
            }.distinctUntilChanged().collectLatest { active ->
                while (active) {
                    delay(pollMillis)
                    poll()
                }
            }
        }
        scope.launch {
            var account: Long? = null
            session.state.collect { state ->
                when (state) {
                    is SessionState.LoggedIn -> if (state.account.id != account) {
                        account = state.account.id
                        state.account.riderId?.let { discover(it) }
                    }
                    is SessionState.LoggedOut -> {
                        account = null
                        clear()
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Follows a conversation whose request was just accepted. */
    fun track(conversation: CoachConversation) {
        if (conversation.busy) setRunning { it + (conversation.id to conversation.title) }
        changed()
    }

    /** A conversation was deleted or started. */
    fun changed() = mutableChanges.update { it + 1 }

    /** Follows the busy conversations of a list, e.g. after login. */
    fun trackBusy(conversations: List<CoachConversationSummary>) {
        val busy = conversations.filter { it.status == CoachConversationSummary.STATUS_BUSY }
        if (busy.isNotEmpty()) setRunning { it + busy.associate { c -> c.id to c.title } }
    }

    /**
     * A screen shows the conversation and loads it while it is busy: it is not polled here meanwhile.
     *
     * @return ends the watch; a request still running is polled here again
     */
    fun watch(id: Long): () -> Unit {
        watched.update { it + (id to (it[id] ?: 0) + 1) }
        return {
            watched.update {
                val count = (it[id] ?: 0) - 1
                if (count > 0) it + (id to count) else it - id
            }
        }
    }

    /** A screen loaded the conversation: a followed request that ended is finished as after a poll. */
    fun seen(conversation: CoachConversation) {
        if (!conversation.busy && conversation.id in mutableRunning.value) finish(conversation)
    }

    /** Forgets everything, e.g. on logout. */
    fun clear() {
        setRunning { emptyMap() }
        watched.value = emptyMap()
    }

    private suspend fun discover(riderId: Long) {
        (apiCall(Network.json) { api.conversations(riderId) } as? ApiResult.Success)?.let { trackBusy(it.value) }
    }

    private suspend fun poll() {
        val ids = mutableRunning.value.keys.filter { it !in watched.value }
        val results = coroutineScope {
            ids.map { id -> async { id to apiResponseCall(Network.json) { api.conversation(id) } } }.awaitAll()
        }
        results.forEach { (id, result) ->
            when (result) {
                is ApiResult.Success -> result.value.body()?.let { if (!it.busy) finish(it) }
                // Deleted or not the account's any more: stop following it; other failures try again
                is ApiResult.Failure -> if ((result.error as? ApiError.Http)?.status in GONE) forget(id)
            }
        }
    }

    private fun finish(conversation: CoachConversation) {
        if (conversation.id !in mutableRunning.value) return
        forget(conversation.id)
        scope.launch { quotas.reload() }
        mutableFinished.update { it + 1 }
        changed()
        if (conversation.id !in watched.value) {
            channel.trySend(CoachFinished(conversation.id, conversation.title, conversation.pendingRequest?.failed == true))
        }
    }

    private fun forget(id: Long) = setRunning { it - id }

    private fun setRunning(change: (Map<Long, String>) -> Map<Long, String>) {
        mutableRunning.update(change)
        mutableAtCapacity.value = mutableRunning.value.size >= MAX_RUNNING_REQUESTS
    }

    companion object {
        const val POLL_MILLIS = 5_000L

        /** Requests of one account that may run at once (cyclingapp spec 015). */
        const val MAX_RUNNING_REQUESTS = 1
        private val GONE = setOf(403, 404)
    }
}
