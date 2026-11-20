package app.climbra.android.garmin

import app.climbra.android.R
import app.climbra.android.data.api.GarminApi
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.intervals.SyncOrigin
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.ui.common.DateArg
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The account may sync from Garmin: the backend allows it to admins only (spec 011). */
fun SessionState.garminSyncAllowed(): Boolean = (this as? SessionState.LoggedIn)?.account?.role == ROLE_ADMIN

private const val ROLE_ADMIN = "ADMIN"

/**
 * The owner's Garmin sync for the whole app (spec 011): started by the profile's button or by an admin's pull to
 * refresh, one at a time, its result in [messages] for the shell's snackbar; forgotten on logout. The request answers
 * when the sync is done.
 */
class GarminSync(
    private val api: GarminApi,
    private val changes: DataChanges,
    private val clock: Clock,
    private val session: SessionRepository,
    private val scope: CoroutineScope,
    /** Offline (spec 018) a pull starts no sync. */
    private val offline: () -> Boolean = { false },
) {
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    /** The account is an admin: only then is anything of Garmin sync shown or sent. */
    val allowed: StateFlow<Boolean> = session.state.map { it.garminSyncAllowed() }
        .stateIn(scope, SharingStarted.Eagerly, session.state.value.garminSyncAllowed())

    private val channel = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = channel.receiveAsFlow()

    private val mutableImported = MutableStateFlow(0)

    /** Bumped after [DataChanges.changed] when a sync brought activities: screens that show rides load again at once. */
    val imported: StateFlow<Int> = mutableImported.asStateFlow()

    private var lastEnded: Instant? = null
    private var job: Job? = null

    init {
        scope.launch {
            session.state.collect { if (it is SessionState.LoggedOut) clear() }
        }
    }

    /** Syncs [riderId] unless the account is no admin, a sync runs, or ([SyncOrigin.PULL]) one ended < [THROTTLE] ago. */
    fun sync(riderId: Long, origin: SyncOrigin) {
        if (!session.state.value.garminSyncAllowed() || job?.isActive == true) return
        if (origin == SyncOrigin.PULL && offline()) return
        if (origin == SyncOrigin.PULL) {
            val ended = lastEnded
            if (ended != null && Duration.between(ended, clock.instant()) < THROTTLE) return
        }
        mutableRunning.value = true
        job = scope.launch {
            val result = apiCall(Network.json) { api.sync(riderId) }
            lastEnded = clock.instant()
            mutableRunning.value = false
            when (result) {
                is ApiResult.Success -> {
                    val imported = result.value.imported
                    if (imported > 0) {
                        changes.changed()
                        mutableImported.update { it + 1 }
                        channel.trySend(UiText(R.plurals.garmin_sync_imported, listOf(imported), quantity = imported))
                    } else if (origin == SyncOrigin.BUTTON) {
                        channel.trySend(UiText(R.string.garmin_sync_nothing_new, listOf(result.value.since?.let(::DateArg) ?: "–")))
                    }
                }
                is ApiResult.Failure -> {
                    // After a pull the screen's own reload tells a network error
                    if (origin == SyncOrigin.BUTTON || result.error is ApiError.Http) {
                        channel.trySend(UiText(R.string.garmin_sync_failed, listOf(result.error.toUiText())))
                    }
                }
            }
        }
    }

    /** Waits until the started sync is done (for tests). */
    internal suspend fun join() {
        job?.join()
    }

    private fun clear() {
        job?.cancel()
        job = null
        lastEnded = null
        mutableRunning.value = false
    }

    companion object {
        val THROTTLE: Duration = Duration.ofSeconds(60)
    }
}
