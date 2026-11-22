package app.climbra.android.push

import android.util.Log
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.session.SessionState
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** The device's FCM token; none without Firebase (spec 020). */
interface PushTokenSource {
    suspend fun token(): String?

    /** A next account gets a new token. */
    suspend fun delete()
}

/** No Firebase project in the build: no token, nothing is sent. */
object NoPushTokens : PushTokenSource {
    override suspend fun token(): String? = null
    override suspend fun delete() = Unit
}

private const val TAG = "PushTokens"

class FirebasePushTokens : PushTokenSource {
    override suspend fun token(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) Log.w(TAG, "No FCM token", task.exception)
            continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }

    override suspend fun delete() {
        suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { continuation.resume(Unit) }
        }
    }
}

/** The signed-in account's id, `null` when nobody is (also while the terms wait: the app registers then too). */
fun SessionState.accountId(): Long? = when (this) {
    is SessionState.LoggedIn -> account.id
    is SessionState.NeedsTerms -> account.id
    else -> null
}

/**
 * The device's push token on the backend (spec 020), app-scoped: sent after a login and when FCM gives a new one,
 * removed before a logout. Failures are ignored: it is sent again at the next start or login.
 */
class PushTokens(
    private val api: PushApi,
    private val source: PushTokenSource,
    private val session: StateFlow<SessionState>,
    private val scope: CoroutineScope,
    private val offline: () -> Boolean = { false },
    private val logoutTimeoutMillis: Long = LOGOUT_TIMEOUT_MILLIS,
) {
    init {
        scope.launch {
            session.map { it.accountId() }.distinctUntilChanged().collect { id -> if (id != null) register(source.token()) }
        }
    }

    /** FCM's `onNewToken`. */
    fun newToken(token: String) {
        if (session.value.accountId() != null) scope.launch { register(token) }
    }

    private suspend fun register(token: String?) {
        if (token == null || offline()) return
        apiCall(Network.json) { api.register(PushDeviceRequest.android(token)) }
    }

    /** Before the logout ends the session, at most [LOGOUT_TIMEOUT_MILLIS]; the logout goes on whatever happens. */
    suspend fun beforeLogout() {
        withTimeoutOrNull(logoutTimeoutMillis) {
            val token = source.token() ?: return@withTimeoutOrNull
            apiCall(Network.json) { api.unregister(PushDeviceRemoval(token)) }
        }
        scope.launch { source.delete() }
    }

    companion object {
        const val LOGOUT_TIMEOUT_MILLIS = 3_000L
    }
}
