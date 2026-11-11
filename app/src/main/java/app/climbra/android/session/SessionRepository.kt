package app.climbra.android.session

import app.climbra.android.data.api.AcceptTermsRequest
import app.climbra.android.data.api.AccountResponse
import app.climbra.android.data.api.AuthApi
import app.climbra.android.data.api.AuthProviders
import app.climbra.android.data.api.GoogleIdTokenRequest
import app.climbra.android.data.api.LoginRequest
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.PersistentCookieJar
import app.climbra.android.data.net.SessionEvent
import app.climbra.android.data.net.SessionEvents
import app.climbra.android.data.net.apiCall
import app.climbra.android.data.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    /** Checking the stored session at start. */
    data object Loading : SessionState

    /** The backend could not be reached at start; the stored session is kept. */
    data class Unavailable(val error: ApiError) : SessionState

    /** @param expired the session ended while the app was used */
    data class LoggedOut(val expired: Boolean = false) : SessionState

    data class NeedsTerms(val account: AccountResponse) : SessionState

    data class LoggedIn(val account: AccountResponse) : SessionState
}

/**
 * The account of the app and its session with the backend (spec 001). The session itself is the backend's cookie in
 * [cookies]; only the account is held here, in memory, and read again from `GET /api/auth/me` at every start.
 */
class SessionRepository(
    private val api: AuthApi,
    private val cookies: PersistentCookieJar,
    events: SessionEvents,
    private val locales: AppLocales,
    scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    init {
        scope.launch {
            events.events.collect { event ->
                when (event) {
                    SessionEvent.UNAUTHORIZED -> if (isSignedIn()) endSession(expired = true)
                    SessionEvent.TERMS_REQUIRED -> (state.value as? SessionState.LoggedIn)?.let {
                        mutableState.value = SessionState.NeedsTerms(it.account)
                    }
                }
            }
        }
    }

    /** Resolves the stored session: logged in, logged out, or the backend is unavailable. */
    suspend fun start() {
        mutableState.value = SessionState.Loading
        when (val result = call { api.me() }) {
            is ApiResult.Success -> signedIn(result.value)
            is ApiResult.Failure -> when ((result.error as? ApiError.Http)?.status) {
                401 -> endSession(expired = false)
                else -> mutableState.value = SessionState.Unavailable(result.error)
            }
        }
    }

    suspend fun providers(): ApiResult<AuthProviders> = call { api.providers() }

    /** Always a remember-me session (30 days on the backend). @return the error, `null` when logged in */
    suspend fun login(email: String, password: String): ApiError? =
        signIn { api.login(LoginRequest(email.trim(), password, rememberMe = true)) }

    /** A nonce for a Google ID token, bound to this session by the backend (cyclingapp spec 050). */
    suspend fun googleNonce(): ApiResult<String> = when (val result = call { api.googleNonce() }) {
        is ApiResult.Success -> ApiResult.Success(result.value.nonce)
        is ApiResult.Failure -> result
    }

    suspend fun loginWithGoogle(idToken: String): ApiError? =
        signIn { api.googleLogin(GoogleIdTokenRequest(idToken)) }

    /** Accepts the terms and the health data consent, both required, as on the web. */
    suspend fun acceptTerms(): ApiError? {
        when (val accepted = call { api.acceptTerms(AcceptTermsRequest(acceptTerms = true, acceptHealthData = true)) }) {
            is ApiResult.Failure -> return accepted.error
            is ApiResult.Success -> Unit
        }
        return signIn { api.me() }
    }

    /** Ends the session on the backend; the cookies are cleared even when that call fails. */
    suspend fun logout() {
        call { api.logout() }
        endSession(expired = false)
    }

    private suspend fun signIn(request: suspend () -> AccountResponse): ApiError? =
        when (val result = call(request)) {
            is ApiResult.Success -> {
                signedIn(result.value)
                null
            }
            is ApiResult.Failure -> result.error
        }

    private fun signedIn(account: AccountResponse) {
        locales.useAccountLanguage(account.language)
        mutableState.value =
            if (account.termsAccepted) SessionState.LoggedIn(account) else SessionState.NeedsTerms(account)
    }

    private fun endSession(expired: Boolean) {
        cookies.clear()
        locales.useDeviceLanguage()
        mutableState.value = SessionState.LoggedOut(expired)
    }

    private fun isSignedIn() = state.value is SessionState.LoggedIn || state.value is SessionState.NeedsTerms

    private suspend fun <T> call(request: suspend () -> T) = apiCall(Network.json, request)
}
