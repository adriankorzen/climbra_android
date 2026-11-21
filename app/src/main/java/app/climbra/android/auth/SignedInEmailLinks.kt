package app.climbra.android.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.data.net.Connectivity

/** What an email link shows above the signed-in screens (spec 021). */
enum class SignedInLinkScreen { VERIFY_EMAIL, RESET_PASSWORD, FORGOT_PASSWORD }

/**
 * The email links while logged in (spec 021): a verify or reset link shows its screen full screen above [content]
 * (the shell or the terms), which stays as it was; back closes it. Login and forgot password links only bring the
 * app to the front, as the web's guest guard does.
 */
@Composable
fun SignedInEmailLinks(
    link: EmailLink?,
    onLinkTaken: (EmailLink) -> Unit,
    screen: @Composable (shown: SignedInLinkScreen, token: String, opened: Int, open: (SignedInLinkScreen?) -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var shown by rememberSaveable { mutableStateOf<SignedInLinkScreen?>(null) }
    var token by rememberSaveable { mutableStateOf("") }
    var opened by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(link) {
        val opening = link ?: return@LaunchedEffect
        when (opening) {
            is EmailLink.Verify -> {
                token = opening.token
                opened++
                shown = SignedInLinkScreen.VERIFY_EMAIL
            }
            is EmailLink.Reset -> {
                token = opening.token
                opened++
                shown = SignedInLinkScreen.RESET_PASSWORD
            }
            EmailLink.Login, EmailLink.ForgotPassword -> Unit
        }
        onLinkTaken(opening)
    }
    Box(Modifier.fillMaxSize()) {
        content()
        shown?.let { current ->
            BackHandler { shown = null }
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("email-link-screen")) {
                screen(current, token, opened) { next ->
                    if (next != null) opened++
                    shown = next
                }
            }
        }
    }
}

/** [SignedInEmailLinks] with the app's screens. */
@Composable
fun SignedInEmailLinksRoute(
    emailLinks: EmailLinks,
    config: AppConfig,
    connectivity: Connectivity,
    accountEmail: () -> String?,
    content: @Composable () -> Unit,
) {
    val link by emailLinks.pending.collectAsStateWithLifecycle()
    SignedInEmailLinks(
        link = link,
        onLinkTaken = emailLinks::taken,
        screen = { shown, token, opened, open ->
            when (shown) {
                SignedInLinkScreen.VERIFY_EMAIL -> VerifyEmailRoute(
                    config = config,
                    token = token,
                    connectivity = connectivity,
                    onBack = { open(null) },
                    onForgotPassword = { open(SignedInLinkScreen.FORGOT_PASSWORD) },
                    viewModel = hiltViewModel(key = "link-verify-$opened"),
                )
                SignedInLinkScreen.RESET_PASSWORD -> ResetPasswordRoute(
                    token = token,
                    connectivity = connectivity,
                    onBack = { open(null) },
                    onLogin = { open(null) },
                    onForgotPassword = { open(SignedInLinkScreen.FORGOT_PASSWORD) },
                    viewModel = hiltViewModel(key = "link-reset-$opened"),
                )
                SignedInLinkScreen.FORGOT_PASSWORD -> {
                    val viewModel: ForgotPasswordViewModel = hiltViewModel(key = "link-forgot-$opened")
                    viewModel.prefill(accountEmail())
                    ForgotPasswordRoute(config = config, onBack = { open(null) }, viewModel = viewModel)
                }
            }
        },
        content = content,
    )
}
