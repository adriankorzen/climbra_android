package app.climbra.android.auth

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.data.net.Connectivity
import app.climbra.android.session.SessionState

/**
 * The screens before login (spec 013): the login, the registration and the password reset above it; the email
 * links' screens (spec 021) above it too.
 */
enum class LoggedOutScreen { LOGIN, REGISTER, FORGOT_PASSWORD, VERIFY_EMAIL, RESET_PASSWORD }

/**
 * Where the logged-out screens lead. [opened] counts openings: each one starts with an empty form. [token] is the
 * email link's, for [LoggedOutScreen.VERIFY_EMAIL] and [LoggedOutScreen.RESET_PASSWORD].
 */
class LoggedOutNavigator internal constructor(
    val email: String?,
    val opened: Int,
    val token: String,
    val openRegister: () -> Unit,
    val openForgotPassword: (email: String) -> Unit,
    val backToLogin: (email: String?) -> Unit,
)

@Composable
fun LoggedOutContent(
    loggedOut: SessionState.LoggedOut,
    config: AppConfig,
    emailLinks: EmailLinks,
    connectivity: Connectivity,
) {
    val link by emailLinks.pending.collectAsStateWithLifecycle()
    LoggedOutNavigation(
        link = link,
        onLinkTaken = emailLinks::taken,
        login = { nav ->
            LoginRoute(
                expired = loggedOut.expired,
                config = config,
                accountDeleted = loggedOut.accountDeleted,
                onRegister = nav.openRegister,
                onForgotPassword = nav.openForgotPassword,
                prefillEmail = nav.email,
            )
        },
        register = { nav ->
            RegisterRoute(
                config = config,
                onBack = { nav.backToLogin(null) },
                onBackToLogin = nav.backToLogin,
                viewModel = hiltViewModel(key = "register-${nav.opened}"),
            )
        },
        forgotPassword = { nav ->
            val viewModel: ForgotPasswordViewModel = hiltViewModel(key = "forgot-${nav.opened}")
            viewModel.prefill(nav.email)
            ForgotPasswordRoute(config = config, onBack = { nav.backToLogin(null) }, viewModel = viewModel)
        },
        verifyEmail = { nav ->
            VerifyEmailRoute(
                config = config,
                token = nav.token,
                connectivity = connectivity,
                onBack = { nav.backToLogin(null) },
                onForgotPassword = { nav.openForgotPassword("") },
                viewModel = hiltViewModel(key = "verify-${nav.opened}"),
            )
        },
        resetPassword = { nav ->
            ResetPasswordRoute(
                token = nav.token,
                connectivity = connectivity,
                onBack = { nav.backToLogin(null) },
                onLogin = { nav.backToLogin(null) },
                onForgotPassword = { nav.openForgotPassword("") },
                viewModel = hiltViewModel(key = "reset-${nav.opened}"),
            )
        },
    )
}

/**
 * The logged-out state's small back stack: registration, password reset and the email links' screens sit above the
 * login, back returns to it. A pending [link] opens its screen and is taken.
 */
@Composable
fun LoggedOutNavigation(
    login: @Composable (LoggedOutNavigator) -> Unit,
    register: @Composable (LoggedOutNavigator) -> Unit,
    forgotPassword: @Composable (LoggedOutNavigator) -> Unit,
    verifyEmail: @Composable (LoggedOutNavigator) -> Unit = {},
    resetPassword: @Composable (LoggedOutNavigator) -> Unit = {},
    link: EmailLink? = null,
    onLinkTaken: (EmailLink) -> Unit = {},
) {
    var screen by rememberSaveable { mutableStateOf(LoggedOutScreen.LOGIN) }
    var email by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by rememberSaveable { mutableIntStateOf(0) }
    var token by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(link) {
        val opening = link ?: return@LaunchedEffect
        when (opening) {
            is EmailLink.Verify -> {
                token = opening.token
                opened++
                screen = LoggedOutScreen.VERIFY_EMAIL
            }
            is EmailLink.Reset -> {
                token = opening.token
                opened++
                screen = LoggedOutScreen.RESET_PASSWORD
            }
            EmailLink.Login -> screen = LoggedOutScreen.LOGIN
            EmailLink.ForgotPassword -> {
                opened++
                screen = LoggedOutScreen.FORGOT_PASSWORD
            }
        }
        onLinkTaken(opening)
    }
    BackHandler(enabled = screen != LoggedOutScreen.LOGIN) { screen = LoggedOutScreen.LOGIN }
    val navigator = LoggedOutNavigator(
        email = email,
        opened = opened,
        token = token,
        openRegister = {
            opened++
            screen = LoggedOutScreen.REGISTER
        },
        openForgotPassword = { typed ->
            if (typed.isNotEmpty()) email = typed
            opened++
            screen = LoggedOutScreen.FORGOT_PASSWORD
        },
        backToLogin = { typed ->
            if (typed != null) email = typed
            screen = LoggedOutScreen.LOGIN
        },
    )
    when (screen) {
        LoggedOutScreen.LOGIN -> login(navigator)
        LoggedOutScreen.REGISTER -> register(navigator)
        LoggedOutScreen.FORGOT_PASSWORD -> forgotPassword(navigator)
        LoggedOutScreen.VERIFY_EMAIL -> verifyEmail(navigator)
        LoggedOutScreen.RESET_PASSWORD -> resetPassword(navigator)
    }
}
