package app.climbra.android.garmin

import android.content.Intent
import app.climbra.android.R
import app.climbra.android.data.api.GarminAuthorizationRequest
import app.climbra.android.data.api.GarminCompleteRequest
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.api.ProfileApi
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The consent dialog (the web's `garmin.dialog`) for [riderId]; [status] shows why connecting again is needed. */
data class GarminConsent(
    val riderId: Long,
    val status: GarminStatus = GarminStatus.NOT_CONNECTED,
    val starting: Boolean = false,
    val error: UiText? = null,
)

/**
 * Connecting Garmin Connect from the app (spec 016, cyclingapp spec 055), app-scoped: the consent dialog any screen
 * asks for with [ask], Garmin's page in a Custom Tab ([openings], opened by the shell), and the completion when
 * `climbra://garmin-connect?state=…` comes back ([returned]) — after the next login if the session ended meanwhile.
 * The outcome goes to [messages] for the shell's snackbar and bumps [completed], on which screens load the Garmin
 * status again.
 */
class GarminConnectFlow(
    private val api: ProfileApi,
    private val session: SessionRepository,
    private val scope: CoroutineScope,
) {
    private val mutableConsent = MutableStateFlow<GarminConsent?>(null)
    val consent: StateFlow<GarminConsent?> = mutableConsent.asStateFlow()

    private val openingsChannel = Channel<String>(Channel.BUFFERED)

    /** Garmin's authorization pages to open in a Custom Tab. */
    val openings: Flow<String> = openingsChannel.receiveAsFlow()

    private val messagesChannel = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = messagesChannel.receiveAsFlow()

    private val mutableCompleted = MutableStateFlow(0)

    /** Bumped when the Garmin connection may have changed. */
    val completed: StateFlow<Int> = mutableCompleted.asStateFlow()

    /** States Garmin's callback returned with, until they are completed in the app's session. In memory only. */
    private val returns = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            session.state.collect { if (it is SessionState.LoggedOut) mutableConsent.value = null }
        }
        scope.launch {
            for (state in returns) {
                // A return while logged out completes after the next login
                session.state.first { it is SessionState.LoggedIn }
                complete(state)
            }
        }
    }

    fun ask(riderId: Long, status: GarminStatus = GarminStatus.NOT_CONNECTED) {
        if (mutableConsent.value?.starting == true) return
        mutableConsent.value = GarminConsent(riderId, status)
    }

    /** Closes the dialog, unless the request runs. */
    fun dismiss() {
        if (mutableConsent.value?.starting != true) mutableConsent.value = null
    }

    /** The rider agreed: Garmin's page opens. */
    fun continueToGarmin() {
        val consent = mutableConsent.value ?: return
        if (consent.starting) return
        mutableConsent.value = consent.copy(starting = true, error = null)
        scope.launch {
            val result = apiCall(Network.json) {
                api.authorizeGarmin(consent.riderId, GarminAuthorizationRequest(consent = true, returnTo = RETURN_TO))
            }
            when (result) {
                is ApiResult.Success -> {
                    mutableConsent.value = null
                    openingsChannel.trySend(result.value.authorizationUrl)
                }
                is ApiResult.Failure -> {
                    val error = result.error
                    if (error is ApiError.Http && error.status == 409) {
                        // Connected meanwhile (on the web): the screens show it
                        mutableConsent.value = null
                        mutableCompleted.update { it + 1 }
                    } else {
                        mutableConsent.update { it?.copy(starting = false, error = error.toUiText()) }
                    }
                }
            }
        }
    }

    /** Garmin's callback sent the rider back with [state]. */
    fun returned(state: String) {
        if (state.isNotBlank()) returns.trySend(state)
    }

    private suspend fun complete(state: String) {
        val result = apiCall(Network.json) { api.completeGarmin(GarminCompleteRequest(state)) }
        when (result) {
            is ApiResult.Success -> messagesChannel.trySend(UiText(outcomeText(result.value.outcome)))
            is ApiResult.Failure -> messagesChannel.trySend(result.error.toUiText())
        }
        mutableCompleted.update { it + 1 }
    }

    companion object {
        const val RETURN_TO = "APP"
        const val SCHEME = "climbra"
        const val HOST = "garmin-connect"

        /** The web's `garmin.outcome.*`; one the app does not know reads as `failed`. */
        fun outcomeText(outcome: String): Int = when (outcome) {
            "connected" -> R.string.garmin_outcome_connected
            "no-permission" -> R.string.garmin_outcome_no_permission
            "denied" -> R.string.garmin_outcome_denied
            "in-use" -> R.string.garmin_outcome_in_use
            else -> R.string.garmin_outcome_failed
        }
    }
}

/** The state of `climbra://garmin-connect?state=…`, the end of Garmin's consent (spec 016); `null` for other intents. */
fun Intent?.garminConnectState(): String? {
    val uri = this?.data ?: return null
    if (action != Intent.ACTION_VIEW || uri.scheme != GarminConnectFlow.SCHEME || uri.host != GarminConnectFlow.HOST) {
        return null
    }
    return uri.getQueryParameter("state")?.takeIf { it.isNotBlank() }
}
