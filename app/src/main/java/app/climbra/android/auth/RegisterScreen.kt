package app.climbra.android.auth

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.coach.FormScaffold
import app.climbra.android.coach.appLanguage
import app.climbra.android.data.api.AuthApi
import app.climbra.android.data.api.EmailRequest
import app.climbra.android.data.api.RegisterRequest
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.profile.PasswordField
import app.climbra.android.profile.PasswordProblem
import app.climbra.android.profile.newPasswordProblem
import app.climbra.android.profile.passwordProblemText
import app.climbra.android.profile.repeatProblem
import app.climbra.android.session.SessionRepository
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The web's `Validators.email`, roughly: something, `@`, a domain with a dot. */
fun isEmail(text: String): Boolean = EMAIL.matches(text.trim())

private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/** The Turnstile site key of the backend, `null` when the check is off (or the providers could not be read). */
internal suspend fun SessionRepository.captchaSiteKey(): String? =
    (providers() as? ApiResult.Success)?.value?.turnstileSiteKey

data class RegisterUiState(
    val email: String = "",
    val password: String = "",
    val repeat: String = "",
    val acceptTerms: Boolean = false,
    val acceptHealthData: Boolean = false,
    /** Show what is missing or wrong: after a send. */
    val showErrors: Boolean = false,
    val siteKey: String? = null,
    val sending: Boolean = false,
    val error: UiText? = null,
    /** The address the link went to: the screen says to check the inbox. */
    val sent: String? = null,
    val resent: Boolean = false,
) {
    val emailInvalid: Boolean get() = !isEmail(email)
    val passwordProblem: PasswordProblem? get() = newPasswordProblem(password)
    val repeatProblem: PasswordProblem? get() = repeatProblem(password, repeat, email)
    val valid: Boolean
        get() = !emailInvalid && passwordProblem == null && repeatProblem == null && acceptTerms && acceptHealthData
}

/** The web's `register-page` (spec 013): the account is created, then its address confirmed through the email's link. */
@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val api: AuthApi,
    private val session: SessionRepository,
    private val clock: Clock,
) : ViewModel() {

    private val mutableState = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = mutableState.asStateFlow()

    private var lastResend: Instant? = null

    init {
        viewModelScope.launch {
            val key = session.captchaSiteKey()
            mutableState.update { it.copy(siteKey = key) }
        }
    }

    fun update(change: (RegisterUiState) -> RegisterUiState) = mutableState.update { change(it).copy(error = null) }

    /** Checks the form, gets a captcha token and creates the account. */
    fun register(captcha: CaptchaSource) {
        val current = state.value
        if (current.sending) return
        if (!current.valid) {
            mutableState.update { it.copy(showErrors = true) }
            return
        }
        mutableState.update { it.copy(showErrors = true, sending = true, error = null) }
        viewModelScope.launch {
            val token = captcha.token()
            if (token is CaptchaResult.Unavailable) return@launch failed(UiText(R.string.captcha_unavailable))
            val request = RegisterRequest(
                email = current.email.trim(),
                password = current.password,
                language = appLanguage(session),
                acceptTerms = true,
                acceptHealthData = true,
                captchaToken = token.value,
            )
            when (val result = apiCall(Network.json) { api.register(request) }) {
                is ApiResult.Success -> mutableState.update { it.copy(sending = false, sent = request.email) }
                is ApiResult.Failure -> failed(result.error.toUiText())
            }
        }
    }

    /** The link again, at most once per [RESEND_PAUSE]. */
    fun resend(captcha: CaptchaSource) {
        val email = state.value.sent ?: return
        val last = lastResend
        if (state.value.sending || (last != null && Duration.between(last, clock.instant()) < RESEND_PAUSE)) return
        lastResend = clock.instant()
        mutableState.update { it.copy(sending = true, error = null, resent = false) }
        viewModelScope.launch {
            val token = captcha.token()
            if (token is CaptchaResult.Unavailable) return@launch failed(UiText(R.string.captcha_unavailable))
            when (val result = apiCall(Network.json) { api.resendVerification(EmailRequest(email, token.value)) }) {
                is ApiResult.Success -> mutableState.update { it.copy(sending = false, resent = true) }
                is ApiResult.Failure -> failed(result.error.toUiText())
            }
        }
    }

    private fun failed(error: UiText) = mutableState.update { it.copy(sending = false, error = error) }

    companion object {
        val RESEND_PAUSE: Duration = Duration.ofSeconds(60)
    }
}

@Composable
fun RegisterRoute(
    config: AppConfig,
    onBack: () -> Unit,
    onBackToLogin: (email: String) -> Unit,
    viewModel: RegisterViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val turnstile = rememberTurnstile(state.siteKey, config.webBaseUrl, appLanguageOf(context), isSystemInDarkTheme())
    RegisterScreen(
        state = state,
        captcha = { TurnstileView(turnstile, state.siteKey) },
        actions = RegisterActions(
            onBack = onBack,
            onChange = viewModel::update,
            onRegister = { viewModel.register(turnstile) },
            onResend = { viewModel.resend(turnstile) },
            onBackToLogin = { onBackToLogin(state.sent ?: state.email.trim()) },
            onOpenTerms = { openWebPage(context, config.webPage("terms")) },
            onOpenPrivacy = { openWebPage(context, config.webPage("privacy")) },
        ),
    )
}

/** The app's language for the widget, as the requests send it. */
internal fun appLanguageOf(context: android.content.Context): String =
    context.resources.configuration.locales[0].language.takeIf { it == "pl" } ?: "en"

data class RegisterActions(
    val onBack: () -> Unit = {},
    val onChange: ((RegisterUiState) -> RegisterUiState) -> Unit = {},
    val onRegister: () -> Unit = {},
    val onResend: () -> Unit = {},
    val onBackToLogin: () -> Unit = {},
    val onOpenTerms: () -> Unit = {},
    val onOpenPrivacy: () -> Unit = {},
)

@Composable
fun RegisterScreen(state: RegisterUiState, actions: RegisterActions, captcha: @Composable () -> Unit = {}) {
    FormScaffold(stringResource(R.string.register_title), actions.onBack, state.sending) {
        val sent = state.sent
        if (sent != null) {
            Text(stringResource(R.string.register_sent_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.register_sent, sent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("register-sent"))
            if (state.resent) {
                Text(stringResource(R.string.register_resent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("register-resent"))
            }
            state.error?.let { ErrorBanner(it.resolve(), Modifier.testTag("register-error")) }
            captcha()
            OutlinedButton(onClick = actions.onResend, enabled = !state.sending, modifier = Modifier.testTag("register-resend")) {
                Text(stringResource(R.string.register_resend))
            }
            Button(onClick = actions.onBackToLogin, modifier = Modifier.fillMaxWidth().testTag("register-back-to-login")) {
                Text(stringResource(R.string.forgot_back))
            }
            return@FormScaffold
        }
        val errors = state.showErrors
        OutlinedTextField(
            value = state.email,
            onValueChange = { text -> actions.onChange { it.copy(email = text) } },
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            isError = errors && state.emailInvalid,
            supportingText = if (errors && state.emailInvalid) {
                { Text(stringResource(R.string.register_email_invalid), modifier = Modifier.testTag("register-email-error")) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().testTag("register-email"),
        )
        PasswordField(
            value = state.password,
            onChange = { text -> actions.onChange { it.copy(password = text) } },
            label = stringResource(R.string.login_password),
            tag = "register-password",
            error = state.passwordProblem?.takeIf { errors }?.let { passwordProblemText(it) },
            hint = stringResource(R.string.password_hint),
        )
        PasswordField(
            value = state.repeat,
            onChange = { text -> actions.onChange { it.copy(repeat = text) } },
            label = stringResource(R.string.register_repeat_password),
            tag = "register-repeat",
            error = state.repeatProblem?.takeIf { errors }?.let { passwordProblemText(it) },
        )
        ConsentRow(
            text = stringResource(R.string.terms_accept_terms),
            checked = state.acceptTerms,
            onChecked = { checked -> actions.onChange { it.copy(acceptTerms = checked) } },
            error = stringResource(R.string.register_terms_required).takeIf { errors && !state.acceptTerms },
            tag = "register-terms",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.onOpenTerms, modifier = Modifier.testTag("register-open-terms")) { Text(stringResource(R.string.terms_open_terms)) }
            TextButton(onClick = actions.onOpenPrivacy, modifier = Modifier.testTag("register-open-privacy")) { Text(stringResource(R.string.terms_open_privacy)) }
        }
        ConsentRow(
            text = stringResource(R.string.terms_accept_health_data),
            checked = state.acceptHealthData,
            onChecked = { checked -> actions.onChange { it.copy(acceptHealthData = checked) } },
            error = stringResource(R.string.register_health_required).takeIf { errors && !state.acceptHealthData },
            tag = "register-health",
        )
        state.error?.let { ErrorBanner(it.resolve(), Modifier.testTag("register-error")) }
        captcha()
        Button(onClick = actions.onRegister, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("register-submit")) {
            Text(stringResource(R.string.register_submit))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.register_have_account), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = actions.onBackToLogin, modifier = Modifier.testTag("register-login")) { Text(stringResource(R.string.login_submit)) }
        }
    }
}

@Composable
internal fun ConsentRow(text: String, checked: Boolean, onChecked: (Boolean) -> Unit, error: String?, tag: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChecked, modifier = Modifier.testTag(tag))
        androidx.compose.foundation.layout.Column(Modifier.padding(top = 12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("$tag-error")) }
        }
    }
}
