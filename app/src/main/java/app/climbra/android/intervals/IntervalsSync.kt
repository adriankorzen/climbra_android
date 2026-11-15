package app.climbra.android.intervals

import app.climbra.android.R
import app.climbra.android.data.api.ImportJob
import app.climbra.android.data.api.ImportJobStatus
import app.climbra.android.data.api.ProfileApi
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Who started a sync: a pull to refresh is quiet when nothing came, the profile's "Sync now" is not throttled. */
enum class SyncOrigin { PULL, BUTTON }

/** The app's intervals.icu sync (spec 007). */
data class SyncState(
    val running: Boolean = false,
    /** Rides of the job done so far, of [total] once the backend knows them. */
    val processed: Int = 0,
    val total: Int? = null,
    /** The rider has a connection; `null` until read. */
    val connected: Boolean? = null,
)

/**
 * One intervals.icu sync for the whole app (spec 007): started by a pull to refresh on the dashboard and the training
 * log, or by the profile's "Sync now", followed every [pollMillis] in the app scope until its job ends, and forgotten
 * on logout. Its results go to [messages] for the shell's snackbar; [finished] and [imported] are bumped when a job
 * ends and when it brought rides.
 */
class IntervalsSync(
    private val api: ProfileApi,
    private val changes: DataChanges,
    private val clock: Clock,
    session: SessionRepository,
    private val scope: CoroutineScope,
    private val pollMillis: Long = POLL_MILLIS,
) {
    private val mutableState = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = mutableState.asStateFlow()

    private val channel = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = channel.receiveAsFlow()

    private val mutableFinished = MutableStateFlow(0)

    /** Bumped when a job ended, however it ended. */
    val finished: StateFlow<Int> = mutableFinished.asStateFlow()

    private val mutableImported = MutableStateFlow(0)

    /** Bumped after [DataChanges.changed] when a job imported rides: screens that show rides load again at once. */
    val imported: StateFlow<Int> = mutableImported.asStateFlow()

    /** The rider [SyncState.connected] is about. */
    private var connectionRider: Long? = null
    private var lastEnded: Instant? = null
    private var job: Job? = null

    init {
        scope.launch {
            session.state.collect { if (it is SessionState.LoggedOut) clear() }
        }
    }

    /**
     * Starts a sync of [riderId] unless one runs, the rider has no connection, or ([SyncOrigin.PULL] only) the last
     * one ended less than [THROTTLE] ago.
     */
    fun sync(riderId: Long, origin: SyncOrigin) {
        if (job?.isActive == true) return
        if (origin == SyncOrigin.PULL) {
            val ended = lastEnded
            if (ended != null && Duration.between(ended, clock.instant()) < THROTTLE) return
        }
        job = scope.launch {
            if (origin == SyncOrigin.PULL && !connected(riderId)) return@launch
            run(riderId, origin)
        }
    }

    /** Waits until the started sync, or the connection read before it, is done (for tests). */
    internal suspend fun join() {
        job?.join()
    }

    /** The profile connected or disconnected intervals.icu: the status is read again on the next pull. */
    fun connectionChanged(connected: Boolean? = null, riderId: Long? = null) {
        connectionRider = riderId.takeIf { connected != null }
        mutableState.update { it.copy(connected = connected) }
    }

    /** Reads the connection unless it is known to be there. */
    private suspend fun connected(riderId: Long): Boolean {
        if (connectionRider == riderId && state.value.connected == true) return true
        val result = call { api.intervals(riderId) }
        val connected = (result as? ApiResult.Success)?.value?.connected
        connectionRider = riderId.takeIf { connected != null }
        mutableState.update { it.copy(connected = connected) }
        return connected == true
    }

    private suspend fun run(riderId: Long, origin: SyncOrigin) {
        mutableState.update { it.copy(running = true, processed = 0, total = null) }
        var current = when (val started = call { api.syncIntervals(riderId) }) {
            is ApiResult.Failure -> {
                val error = started.error
                // After a pull the screen's own reload tells a network error
                if (origin == SyncOrigin.BUTTON || error is ApiError.Http) channel.trySend(startError(error))
                // A refused start may mean the connection changed (connect again): read it on the next pull
                if (error is ApiError.Http) connectionChanged()
                return end(imported = false)
            }
            is ApiResult.Success -> started.value
        }
        if (origin == SyncOrigin.BUTTON) channel.trySend(UiText(R.string.intervals_sync_started))
        progress(current)
        while (current.running) {
            delay(pollMillis)
            current = when (val polled = call { api.importJob(current.id) }) {
                is ApiResult.Failure -> {
                    channel.trySend(polled.error.toUiText())
                    return end(imported = false)
                }
                is ApiResult.Success -> polled.value
            }
            progress(current)
        }
        val quiet = origin == SyncOrigin.PULL && current.status == ImportJobStatus.COMPLETED &&
            current.imported == 0 && current.unavailable == 0
        if (!quiet) channel.trySend(result(current))
        end(imported = current.imported > 0)
    }

    private fun progress(job: ImportJob) =
        mutableState.update { it.copy(processed = job.processedFiles, total = job.selectedRides?.takeIf { total -> total > 0 }) }

    private fun end(imported: Boolean) {
        lastEnded = clock.instant()
        mutableState.update { it.copy(running = false, processed = 0, total = null) }
        if (imported) {
            changes.changed()
            mutableImported.update { it + 1 }
        }
        mutableFinished.update { it + 1 }
    }

    private fun startError(error: ApiError) = when ((error as? ApiError.Http)?.status) {
        409 -> UiText(R.string.intervals_sync_conflict)
        503 -> UiText(R.string.intervals_not_configured)
        else -> error.toUiText()
    }

    private fun result(job: ImportJob) = when {
        job.status == ImportJobStatus.FAILED -> UiText(R.string.intervals_sync_failed, listOf(job.message.orEmpty()))
        job.unavailable > 0 -> UiText(R.string.intervals_sync_done_unavailable, listOf(job.imported, job.unavailable))
        else -> UiText(R.string.intervals_sync_done, listOf(job.imported))
    }

    /** Forgets everything, e.g. on logout. */
    fun clear() {
        job?.cancel()
        job = null
        lastEnded = null
        connectionRider = null
        mutableState.value = SyncState()
    }

    private suspend fun <T> call(block: suspend () -> T) = apiCall(Network.json, block)

    companion object {
        const val POLL_MILLIS = 2_000L

        /** A pull this soon after a sync ended only reloads. */
        val THROTTLE: Duration = Duration.ofSeconds(60)
    }
}
