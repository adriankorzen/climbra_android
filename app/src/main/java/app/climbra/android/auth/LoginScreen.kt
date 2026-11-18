package app.climbra.android.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.auth.google.CredentialManagerGoogleSource
import app.climbra.android.ui.common.ErrorBanner

@Composable
fun LoginRoute(
    expired: Boolean,
    config: AppConfig,
    accountDeleted: Boolean = false,
    /** Spec 013: the app's registration and password reset; [onForgotPassword] gets the typed email. */
    onRegister: () -> Unit = {},
    onForgotPassword: (email: String) -> Unit = {},
    /** An email to show, e.g. after a registration. */
    prefillEmail: String? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val google = remember(context) { CredentialManagerGoogleSource(context, config.googleServerClientId) }
    LaunchedEffect(prefillEmail) { prefillEmail?.let(viewModel::onEmailChange) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onScreenPaused() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }
    LoginScreen(
        state = state,
        expired = expired,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePassword = viewModel::togglePasswordVisible,
        onLogin = viewModel::login,
        onGoogle = { viewModel.loginWithGoogle(google) },
        onRegister = onRegister,
        onForgotPassword = { onForgotPassword(state.email.trim()) },
        accountDeleted = accountDeleted,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(
    state: LoginUiState,
    expired: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onLogin: () -> Unit,
    onGoogle: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    /** The account was just deleted (spec 004): a note above the form. */
    accountDeleted: Boolean = false,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Image(
                    painter = painterResource(R.drawable.ic_climbra_mark),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.login_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(24.dp))
                val message = state.error?.resolve() ?: if (expired) stringResource(R.string.login_expired) else null
                if (message != null) {
                    ErrorBanner(message, modifier = Modifier.testTag("login-error"))
                    Spacer(Modifier.height(16.dp))
                } else if (accountDeleted) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("login-account-deleted"),
                    ) {
                        Text(
                            stringResource(R.string.account_deleted),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = { Text(stringResource(R.string.login_email)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    isError = state.emailMissing,
                    supportingText = if (state.emailMissing) {
                        { Text(stringResource(R.string.login_required)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login-email")
                        .semantics { contentType = ContentType.EmailAddress + ContentType.Username },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    enabled = !state.submitting,
                    isError = state.passwordMissing,
                    supportingText = if (state.passwordMissing) {
                        { Text(stringResource(R.string.login_required)) }
                    } else null,
                    visualTransformation =
                        if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onTogglePassword) {
                            Icon(
                                painter = painterResource(
                                    if (state.passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                                ),
                                contentDescription = stringResource(
                                    if (state.passwordVisible) R.string.login_hide_password else R.string.login_show_password,
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onLogin() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login-password")
                        .semantics { contentType = ContentType.Password },
                )
                TextButton(onClick = onForgotPassword, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.login_forgot))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onLogin,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("login-submit"),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.login_submit))
                    }
                }
                if (state.googleAvailable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.login_or),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.weight(1f))
                    }
                    OutlinedButton(
                        onClick = onGoogle,
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("login-google"),
                    ) {
                        Text(stringResource(R.string.login_google))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        stringResource(R.string.login_no_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRegister) { Text(stringResource(R.string.login_register)) }
                }
            }
        }
    }
}
