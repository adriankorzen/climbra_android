package app.climbra.android.auth

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.coach.FormScaffold
import app.climbra.android.data.api.AuthApi
import app.climbra.android.data.api.EmailRequest
import app.climbra.android.data.api.PasswordResetConfirmRequest
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Connectivity
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.profile.PasswordField
import app.climbra.android.profile.PasswordProblem
import app.climbra.android.profile.newPasswordProblem
import app.climbra.android.profile.passwordProblemText
import app.climbra.android.profile.repeatProblem
import app.climbra.android.session.SessionRepository
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.LocalOffline
import app.climbra.android.ui.common.OfflineNote
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TOKEN = "token"

private val ApiError.code: String? get() = (this as? ApiError.Http)?.code

data class VerifyEmailUiState(
    val password: String = "",
    val showErrors: Boolean = false,
    val sending: Boolean = false,
    val wrongPassword: Boolean = false,
    val error: UiText? = null,
    /** The link's token is missing, used or expired: a new link is asked for with [email]. */
    val invalid: Boolean = false,
    val email: String = "",
    val siteKey: String? = null,
    val resent: Boolean = false,
    /** Verified and logged in: the screen closes. */
    val verified: Boolean = false,
) {
    val passwordMissing: Boolean get() = password.isEmpty()
    val emailInvalid: Boolean get() = !isEmail(email)
}

/** The web's `verify-email-page` (spec 021): the password proves the registration and logs in (cyclingapp spec 032). */
@HiltViewModel
class VerifyEmailViewModel @Inject constructor(
    private val api: AuthApi,
    private val session: SessionRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val mutableState = MutableStateFlow(VerifyEmailUiState())
    val state: StateFlow<VerifyEmailUiState> = mutableState.asStateFlow()

    private val token: String get() = savedState.get<String>(TOKEN).orEmpty()

    init {
        viewModelScope.launch {
            val key = session.captchaSiteKey()
            mutableState.update { it.copy(siteKey = key) }
        }
    }

    /** The link's token, kept from the first call (also across a process restart). */
    fun open(linkToken: String) {
        if (!savedState.contains(TOKEN)) savedState[TOKEN] = linkToken
        if (token.isBlank()) mutableState.update { it.copy(invalid = true) }
    }

    fun setPassword(text: String) = mutableState.update { it.copy(password = text, wrongPassword = false, error = null) }

    fun setEmail(text: String) = mutableState.update { it.copy(email = text, error = null) }

    fun confirm() {
        val current = state.value
        if (current.sending) return
        if (current.passwordMissing) return mutableState.update { it.copy(showErrors = true) }
        mutableState.update { it.copy(showErrors = true, sending = true, error = null, wrongPassword = false) }
        viewModelScope.launch {
            val error = session.verifyEmail(token, current.password)
            mutableState.update {
                when {
                    error == null -> it.copy(sending = false, verified = true)
                    error.code == "TOKEN_INVALID" -> it.copy(sending = false, invalid = true)
                    error.code == "BAD_CREDENTIALS" -> it.copy(sending = false, wrongPassword = true)
                    else -> it.copy(sending = false, error = error.toUiText())
                }
            }
        }
    }

    fun resend(captcha: CaptchaSource) {
        val current = state.value
        if (current.sending) return
        if (current.emailInvalid) return mutableState.update { it.copy(showErrors = true) }
        mutableState.update { it.copy(showErrors = true, sending = true, error = null) }
        viewModelScope.launch {
            val captchaToken = captcha.token()
            if (captchaToken is CaptchaResult.Unavailable) {
                return@launch mutableState.update { it.copy(sending = false, error = UiText(R.string.captcha_unavailable)) }
            }
            val email = current.email.trim()
            when (val result = apiCall(Network.json) { api.resendVerification(EmailRequest(email, captchaToken.value)) }) {
                is ApiResult.Success -> mutableState.update { it.copy(sending = false, resent = true) }
                is ApiResult.Failure -> mutableState.update { it.copy(sending = false, error = result.error.toUiText()) }
            }
        }
    }
}

data class ResetPasswordUiState(
    val password: String = "",
    val repeat: String = "",
    val showErrors: Boolean = false,
    val sending: Boolean = false,
    val error: UiText? = null,
    val invalid: Boolean = false,
    val done: Boolean = false,
) {
    val passwordProblem: PasswordProblem? get() = newPasswordProblem(password)

    /** The account's address is not known here: the backend checks that one. */
    val repeatProblem: PasswordProblem? get() = repeatProblem(password, repeat, email = "")
}

/** The web's `reset-password-page` (spec 021); the backend ends every session of the account. */
@HiltViewModel
class ResetPasswordViewModel @Inject constructor(
    private val api: AuthApi,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ResetPasswordUiState())
    val state: StateFlow<ResetPasswordUiState> = mutableState.asStateFlow()

    private val token: String get() = savedState.get<String>(TOKEN).orEmpty()

    fun open(linkToken: String) {
        if (!savedState.contains(TOKEN)) savedState[TOKEN] = linkToken
        if (token.isBlank()) mutableState.update { it.copy(invalid = true) }
    }

    fun setPassword(text: String) = mutableState.update { it.copy(password = text, error = null) }

    fun setRepeat(text: String) = mutableState.update { it.copy(repeat = text, error = null) }

    fun submit() {
        val current = state.value
        if (current.sending) return
        if (current.passwordProblem != null || current.repeatProblem != null) {
            return mutableState.update { it.copy(showErrors = true) }
        }
        mutableState.update { it.copy(showErrors = true, sending = true, error = null) }
        viewModelScope.launch {
            val request = PasswordResetConfirmRequest(token, current.password)
            when (val result = apiCall(Network.json) { api.confirmPasswordReset(request) }) {
                is ApiResult.Success -> mutableState.update { it.copy(sending = false, done = true) }
                is ApiResult.Failure -> mutableState.update {
                    if (result.error.code == "TOKEN_INVALID") it.copy(sending = false, invalid = true)
                    else it.copy(sending = false, error = result.error.toUiText())
                }
            }
        }
    }
}

@Composable
fun VerifyEmailRoute(
    config: AppConfig,
    token: String,
    connectivity: Connectivity,
    onBack: () -> Unit,
    onForgotPassword: () -> Unit,
    viewModel: VerifyEmailViewModel,
) {
    LaunchedEffect(viewModel, token) { viewModel.open(token) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offline by connectivity.offline.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val turnstile = rememberTurnstile(state.siteKey, config.webBaseUrl, appLanguageOf(context), isSystemInDarkTheme())
    LaunchedEffect(state.verified) { if (state.verified) onBack() }
    CompositionLocalProvider(LocalOffline provides (offline != null)) {
        VerifyEmailScreen(
            state = state,
            onBack = onBack,
            onPassword = viewModel::setPassword,
            onConfirm = viewModel::confirm,
            onForgotPassword = onForgotPassword,
            onEmail = viewModel::setEmail,
            onResend = { viewModel.resend(turnstile) },
            captcha = { TurnstileView(turnstile, state.siteKey) },
        )
    }
}

@Composable
fun VerifyEmailScreen(
    state: VerifyEmailUiState,
    onBack: () -> Unit,
    onPassword: (String) -> Unit,
    onConfirm: () -> Unit,
    onForgotPassword: () -> Unit,
    onEmail: (String) -> Unit,
    onResend: () -> Unit,
    captcha: @Composable () -> Unit = {},
) {
    val offline = LocalOffline.current
    FormScaffold(stringResource(R.string.verify_title), onBack, state.sending) {
        if (!state.invalid) {
            Text(stringResource(R.string.verify_intro), style = MaterialTheme.typography.bodyMedium)
            PasswordField(
                value = state.password,
                onChange = onPassword,
                label = stringResource(R.string.login_password),
                tag = "verify-password",
                error = when {
                    state.wrongPassword -> stringResource(R.string.verify_wrong_password)
                    state.showErrors && state.passwordMissing -> stringResource(R.string.password_required)
                    else -> null
                },
            )
            state.error?.let { ErrorBanner(it.resolve(), Modifier.testTag("verify-error")) }
            Button(onClick = onConfirm, enabled = !state.sending && !offline, modifier = Modifier.fillMaxWidth().testTag("verify-submit")) {
                Text(stringResource(R.string.verify_submit))
            }
            OfflineNote()
            TextButton(onClick = onForgotPassword, modifier = Modifier.testTag("verify-forgot")) {
                Text(stringResource(R.string.login_forgot))
            }
            return@FormScaffold
        }
        ErrorBanner(stringResource(R.string.verify_invalid), Modifier.testTag("verify-invalid"))
        if (state.resent) {
            Text(stringResource(R.string.verify_resent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("verify-resent"))
            return@FormScaffold
        }
        OutlinedTextField(
            value = state.email,
            onValueChange = onEmail,
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            isError = state.showErrors && state.emailInvalid,
            supportingText = if (state.showErrors && state.emailInvalid) {
                { Text(stringResource(R.string.register_email_invalid)) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().testTag("verify-email"),
        )
        state.error?.let { ErrorBanner(it.resolve(), Modifier.testTag("verify-error")) }
        captcha()
        Button(onClick = onResend, enabled = !state.sending && !offline, modifier = Modifier.fillMaxWidth().testTag("verify-resend")) {
            Text(stringResource(R.string.verify_resend))
        }
        OfflineNote()
    }
}

@Composable
fun ResetPasswordRoute(
    token: String,
    connectivity: Connectivity,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onForgotPassword: () -> Unit,
    viewModel: ResetPasswordViewModel,
) {
    LaunchedEffect(viewModel, token) { viewModel.open(token) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offline by connectivity.offline.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalOffline provides (offline != null)) {
        ResetPasswordScreen(
            state = state,
            onBack = onBack,
            onPassword = viewModel::setPassword,
            onRepeat = viewModel::setRepeat,
            onSubmit = viewModel::submit,
            onLogin = onLogin,
            onForgotPassword = onForgotPassword,
        )
    }
}

@Composable
fun ResetPasswordScreen(
    state: ResetPasswordUiState,
    onBack: () -> Unit,
    onPassword: (String) -> Unit,
    onRepeat: (String) -> Unit,
    onSubmit: () -> Unit,
    onLogin: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    FormScaffold(stringResource(R.string.reset_title), onBack, state.sending) {
        when {
            state.done -> {
                Text(stringResource(R.string.reset_done), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reset-done"))
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().testTag("reset-login")) {
                    Text(stringResource(R.string.login_submit))
                }
            }
            state.invalid -> {
                ErrorBanner(stringResource(R.string.reset_invalid), Modifier.testTag("reset-invalid"))
                Button(onClick = onForgotPassword, modifier = Modifier.fillMaxWidth().testTag("reset-new-link")) {
                    Text(stringResource(R.string.reset_new_link))
                }
            }
            else -> {
                val errors = state.showErrors
                PasswordField(
                    value = state.password,
                    onChange = onPassword,
                    label = stringResource(R.string.password_new),
                    tag = "reset-password",
                    error = state.passwordProblem?.takeIf { errors }?.let { passwordProblemText(it) },
                    hint = stringResource(R.string.password_hint),
                )
                PasswordField(
                    value = state.repeat,
                    onChange = onRepeat,
                    label = stringResource(R.string.password_repeat),
                    tag = "reset-repeat",
                    error = state.repeatProblem?.takeIf { errors }?.let { passwordProblemText(it) },
                )
                state.error?.let { ErrorBanner(it.resolve(), Modifier.testTag("reset-error")) }
                Button(
                    onClick = onSubmit,
                    enabled = !state.sending && !LocalOffline.current,
                    modifier = Modifier.fillMaxWidth().testTag("reset-submit"),
                ) { Text(stringResource(R.string.reset_submit)) }
                OfflineNote()
            }
        }
    }
}
