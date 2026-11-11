package app.climbra.android.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.AppConfig
import app.climbra.android.auth.google.GoogleCredentialResult
import app.climbra.android.auth.google.GoogleCredentialSource
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.session.SessionRepository
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    /** Fields left empty at the last attempt. */
    val emailMissing: Boolean = false,
    val passwordMissing: Boolean = false,
    val submitting: Boolean = false,
    val error: UiText? = null,
    val googleAvailable: Boolean = false,
    /** The backend's login providers are known (or Google login is off in this build). */
    val googleChecked: Boolean = false,
)

/** Login screen (spec 001); a successful login changes [SessionRepository.state], which leaves this screen. */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: SessionRepository,
    private val config: AppConfig,
) : ViewModel() {

    private val mutableState = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

    private var credentialRequest: Deferred<GoogleCredentialResult>? = null
    private var leftForCredentialUi = false

    init {
        if (config.googleLoginEnabled) {
            viewModelScope.launch {
                val providers = session.providers()
                val google = providers is ApiResult.Success && providers.value.google
                mutableState.update { it.copy(googleAvailable = google, googleChecked = true) }
            }
        } else {
            mutableState.update { it.copy(googleChecked = true) }
        }
    }

    fun onEmailChange(email: String) = mutableState.update { it.copy(email = email, emailMissing = false) }

    fun onPasswordChange(password: String) =
        mutableState.update { it.copy(password = password, passwordMissing = false) }

    fun togglePasswordVisible() = mutableState.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun login() {
        val current = state.value
        if (current.submitting) return
        val emailMissing = current.email.isBlank()
        val passwordMissing = current.password.isEmpty()
        if (emailMissing || passwordMissing) {
            mutableState.update { it.copy(emailMissing = emailMissing, passwordMissing = passwordMissing) }
            return
        }
        run { session.login(current.email, current.password) }
    }

    fun loginWithGoogle(source: GoogleCredentialSource) {
        if (state.value.submitting) return
        run {
            when (val nonce = session.googleNonce()) {
                is ApiResult.Failure -> nonce.error
                is ApiResult.Success -> when (val credential = awaitCredential(source, nonce.value)) {
                    is GoogleCredentialResult.Token -> session.loginWithGoogle(credential.idToken)
                    GoogleCredentialResult.Cancelled -> null
                    GoogleCredentialResult.Failed -> GOOGLE_FAILED
                }
            }
        }
    }

    /**
     * Waits for the system's Google UI. It can close without ever answering (seen when adding an account fails), so
     * a request still open [RESULT_GRACE] after the screen is back is given up as cancelled.
     */
    private suspend fun awaitCredential(source: GoogleCredentialSource, nonce: String): GoogleCredentialResult =
        coroutineScope {
            // Known before it starts, so a pause while the system UI opens is seen
            val request = async(start = CoroutineStart.LAZY) { source.idToken(nonce) }
            credentialRequest = request
            try {
                request.await()
            } catch (e: CancellationException) {
                if (request.isCancelled && isActive) GoogleCredentialResult.Cancelled else throw e
            } finally {
                credentialRequest = null
                leftForCredentialUi = false
            }
        }

    /** The screen went to the background, e.g. under the system's account picker. */
    fun onScreenPaused() {
        if (credentialRequest != null) leftForCredentialUi = true
    }

    /** The screen is back: the picker has closed, so its answer is due. */
    fun onScreenResumed() {
        val request = credentialRequest ?: return
        if (!leftForCredentialUi) return
        viewModelScope.launch {
            delay(RESULT_GRACE)
            if (credentialRequest === request && request.isActive) request.cancel()
        }
    }

    private fun run(attempt: suspend () -> ApiError?) {
        mutableState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val error = attempt()
            mutableState.update { it.copy(submitting = false, error = error?.toUiText()) }
        }
    }

    private companion object {
        /** How long an answer of the Google UI may take after the screen is back. */
        val RESULT_GRACE = 3.seconds

        /** The credential could not be obtained on the device; shown like the backend's `401 GOOGLE_FAILED`. */
        val GOOGLE_FAILED = ApiError.Http(status = 401, code = "GOOGLE_FAILED")
    }
}
