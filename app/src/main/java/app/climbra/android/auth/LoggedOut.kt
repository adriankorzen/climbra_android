package app.climbra.android.auth

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.climbra.android.AppConfig
import app.climbra.android.session.SessionState

/** The screens before login (spec 013): the login, the registration and the password reset above it. */
enum class LoggedOutScreen { LOGIN, REGISTER, FORGOT_PASSWORD }

/** Where the logged-out screens lead. [opened] counts openings: each one starts with an empty form. */
class LoggedOutNavigator internal constructor(
    val email: String?,
    val opened: Int,
    val openRegister: () -> Unit,
    val openForgotPassword: (email: String) -> Unit,
    val backToLogin: (email: String?) -> Unit,
)

@Composable
fun LoggedOutContent(loggedOut: SessionState.LoggedOut, config: AppConfig) {
    LoggedOutNavigation(
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
    )
}

/** The logged-out state's small back stack: registration and password reset sit above the login, back returns to it. */
@Composable
fun LoggedOutNavigation(
    login: @Composable (LoggedOutNavigator) -> Unit,
    register: @Composable (LoggedOutNavigator) -> Unit,
    forgotPassword: @Composable (LoggedOutNavigator) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(LoggedOutScreen.LOGIN) }
    var email by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = screen != LoggedOutScreen.LOGIN) { screen = LoggedOutScreen.LOGIN }
    val navigator = LoggedOutNavigator(
        email = email,
        opened = opened,
        openRegister = {
            opened++
            screen = LoggedOutScreen.REGISTER
        },
        openForgotPassword = { typed ->
            email = typed
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
    }
}
