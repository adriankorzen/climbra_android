package app.climbra.android.data.net

import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** The backend could not be reached and saved answers are shown; [dataFrom] is when the oldest of them was received. */
data class OfflineState(val dataFrom: Instant)

/**
 * Whether the app is offline (spec 018): set by [OfflineInterceptor] when it serves a saved answer, ended by the
 * first request that reaches the backend. A return of the network or the banner's Retry ask the screens to load again
 * ([reloads]), which ends offline mode when the backend answers.
 */
class Connectivity {
    private val mutableOffline = MutableStateFlow<OfflineState?>(null)
    val offline: StateFlow<OfflineState?> = mutableOffline.asStateFlow()

    private val mutableReloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The shown screens load again: the app came back online, or a retry while offline. */
    val reloads: SharedFlow<Unit> = mutableReloads.asSharedFlow()

    val isOffline: Boolean get() = offline.value != null

    /** A saved answer received at [savedAt] was served in place of the backend's. */
    fun servedSaved(savedAt: Instant) {
        mutableOffline.update { current -> OfflineState(current?.dataFrom?.let { minOf(it, savedAt) } ?: savedAt) }
    }

    /** A request reached the backend: back online, and what was shown from saved answers loads again. */
    fun reached() {
        if (mutableOffline.getAndUpdate { null } != null) mutableReloads.tryEmit(Unit)
    }

    /** The banner's Retry, or a network that came back: when offline, the screens try the backend again. */
    fun retry() {
        if (isOffline) mutableReloads.tryEmit(Unit)
    }

    /** The session ended: nothing saved is shown any more. */
    fun reset() {
        mutableOffline.value = null
    }
}
