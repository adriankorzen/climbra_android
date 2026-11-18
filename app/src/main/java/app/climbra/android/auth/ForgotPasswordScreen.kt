package app.climbra.android.auth

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.coach.FormScaffold
import app.climbra.android.data.api.AuthApi
import app.climbra.android.data.api.EmailRequest
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.session.SessionRepository
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ForgotPasswordUiState(
    val email: String = "",
    val showErrors: Boolean = false,
    val siteKey: String? = null,
    val sending: Boolean = false,
    val error: UiText? = null,
    /** The address the link went to, if an account has it. */
    val sent: String? = null,
) {
    val emailInvalid: Boolean get() = !isEmail(email)
}

/** The web's `forgot-password-page` (spec 013); the email's link sets the new password on the web. */
@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(
    private val api: AuthApi,
    private val session: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ForgotPasswordUiState())
    val state: StateFlow<ForgotPasswordUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val key = session.captchaSiteKey()
            mutableState.update { it.copy(siteKey = key) }
        }
    }

    /** The login's or the account's address, once. */
    fun prefill(email: String?) {
        if (!email.isNullOrBlank() && state.value.email.isEmpty()) mutableState.update { it.copy(email = email) }
    }

    fun setEmail(text: String) = mutableState.update { it.copy(email = text, error = null) }

    fun send(captcha: CaptchaSource) {
        val current = state.value
        if (current.sending) return
        if (current.emailInvalid) {
            mutableState.update { it.copy(showErrors = true) }
            return
        }
        mutableState.update { it.copy(showErrors = true, sending = true, error = null) }
        viewModelScope.launch {
            val token = captcha.token()
            if (token is CaptchaResult.Unavailable) return@launch failed(UiText(R.string.captcha_unavailable))
            val email = current.email.trim()
            when (val result = apiCall(Network.json) { api.requestPasswordReset(EmailRequest(email, token.value)) }) {
                is ApiResult.Success -> mutableState.update { it.copy(sending = false, sent = email) }
                is ApiResult.Failure -> failed(result.error.toUiText())
            }
        }
    }

    private fun failed(error: UiText) = mutableState.update { it.copy(sending = false, error = error) }
}

@Composable
fun ForgotPasswordRoute(config: AppConfig, onBack: () -> Unit, viewModel: ForgotPasswordViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val turnstile = rememberTurnstile(state.siteKey, config.webBaseUrl, appLanguageOf(context), isSystemInDarkTheme())
    ForgotPasswordScreen(
        state = state,
        captcha = { TurnstileView(turnstile, state.siteKey) },
        onBack = onBack,
        onEmail = viewModel::setEmail,
        onSend = { viewModel.send(turnstile) },
    )
}

@Composable
fun ForgotPasswordScreen(
    state: ForgotPasswordUiState,
    onBack: () -> Unit,
    onEmail: (String) -> Unit,
    onSend: () -> Unit,
    captcha: @Composable () -> Unit = {},
) {
    FormScaffold(stringResource(R.string.forgot_title), onBack, state.sending) {
        val sent = state.sent
        if (sent != null) {
            Text(stringResource(R.string.forgot_sent, sent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("forgot-sent"))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("forgot-back")) { Text(stringResource(R.string.forgot_back)) }
            return@FormScaffold
        }
        Text(stringResource(R.string.forgot_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.email,
            onValueChange = onEmail,
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            isError = state.showErrors && state.emailInvalid,
            supportingText = if (state.showErrors && state.emailInvalid) {
                { Text(stringResource(R.string.register_email_invalid), modifier = Modifier.testTag("forgot-email-error")) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().testTag("forgot-email"),
        )
        state.error?.let { ErrorBanner(it.resolve(), Modifier.testTag("forgot-error")) }
        captcha()
        Button(onClick = onSend, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("forgot-submit")) {
            Text(stringResource(R.string.forgot_submit))
        }
    }
}
