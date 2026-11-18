package app.climbra.android.auth

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import app.climbra.android.R
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.theme.ClimbraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 013 AC1 - AC7: the registration and password reset screens and their place before the login. */
@RunWith(AndroidJUnit4::class)
class RegisterScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun content(body: @Composable () -> Unit) = compose.setContent { ClimbraTheme(darkTheme = false) { body() } }

    private fun inside(tag: String, text: String) = compose.onNode(
        hasText(text, substring = true) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
        useUnmergedTree = true,
    )

    // AC2: what is wrong shows after a send; the captcha sits above the button
    @Test
    fun theFormShowsWhatIsWrongAfterASend() {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(RegisterUiState())
        content {
            RegisterScreen(
                state,
                RegisterActions(onRegister = { calls += "register" }, onOpenTerms = { calls += "terms" }),
                captcha = { Text("captcha", Modifier.testTag("captcha-slot")) },
            )
        }

        compose.onNodeWithText("Create an account").assertIsDisplayed()
        compose.onNodeWithTag("register-email-error", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("captcha-slot").assertExists()
        compose.onNodeWithTag("register-open-terms").performScrollTo().performClick()
        compose.onNodeWithTag("register-submit").performScrollTo().performClick()
        assertEquals(listOf("terms", "register"), calls)

        state = RegisterUiState(email = "anna@", password = "short", repeat = "x", showErrors = true)
        compose.onNodeWithTag("register-email-error", useUnmergedTree = true).assertExists()
        inside("register-password-supporting", "At least 10 characters").assertExists()
        inside("register-repeat-supporting", "The passwords are different").assertExists()
        compose.onNodeWithTag("register-terms-error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("register-health-error", useUnmergedTree = true).assertExists()

        // AC4
        state = state.copy(error = UiText(R.string.api_error_email_domain_not_allowed))
        inside("register-error", "disposable email address").assertExists()
    }

    // AC3, AC6: the sent screen with the address, the link again and back to the login
    @Test
    fun afterASendTheInboxIsToldAndTheLinkCanBeSentAgain() {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(RegisterUiState(sent = "anna@example.com"))
        content { RegisterScreen(state, RegisterActions(onResend = { calls += "resend" }, onBackToLogin = { calls += "login" })) }

        compose.onNodeWithText("Check your inbox").assertIsDisplayed()
        inside("register-sent", "anna@example.com").assertExists()
        compose.onNodeWithTag("register-resend").performClick()
        state = state.copy(resent = true)
        compose.onNodeWithTag("register-resent").assertExists()
        compose.onNodeWithTag("register-back-to-login").performClick()
        assertEquals(listOf("resend", "login"), calls)
    }

    // AC5: the password reset's answer is the same whether the account exists
    @Test
    fun thePasswordResetTellsTheSameAnswer() {
        var state by mutableStateOf(ForgotPasswordUiState(email = "anna@example.com"))
        var sent = 0
        content { ForgotPasswordScreen(state, onBack = {}, onEmail = {}, onSend = { sent++ }) }

        compose.onNodeWithText("Reset password").assertIsDisplayed()
        inside("forgot-email", "anna@example.com").assertExists()
        compose.onNodeWithTag("forgot-submit").performClick()
        assertEquals(1, sent)

        state = state.copy(sent = "anna@example.com")
        inside("forgot-sent", "If an account exists for anna@example.com").assertExists()
        compose.onNodeWithTag("forgot-back").assertExists()
    }

    // AC1, AC6: the login opens the app's screens; back returns with the email
    @Test
    fun theLoginOpensTheAppsScreensAndBackKeepsTheEmail() {
        content {
            LoggedOutNavigation(
                login = { nav -> androidx.compose.foundation.layout.Column {
                    Text("login ${nav.email}", Modifier.testTag("login"))
                    Button(onClick = nav.openRegister, modifier = Modifier.testTag("open-register")) { Text("register") }
                    Button(onClick = { nav.openForgotPassword("typed@example.com") }, modifier = Modifier.testTag("open-forgot")) { Text("forgot") }
                } },
                register = { nav ->
                    Button(onClick = { nav.backToLogin("anna@example.com") }, modifier = Modifier.testTag("register")) { Text("register ${nav.opened}") }
                },
                forgotPassword = { nav ->
                    Button(onClick = { nav.backToLogin(null) }, modifier = Modifier.testTag("forgot")) { Text("forgot ${nav.email}") }
                },
            )
        }

        compose.onNodeWithTag("open-register").performClick()
        compose.onNodeWithText("register 1").assertExists()
        compose.onNodeWithTag("register").performClick()
        compose.onNodeWithText("login anna@example.com").assertExists()

        compose.onNodeWithTag("open-forgot").performClick()
        compose.onNodeWithText("forgot typed@example.com").assertExists()
        compose.onNodeWithTag("forgot").performClick()
        compose.onNodeWithText("login typed@example.com").assertExists()

        // A new opening is a new form
        compose.onNodeWithTag("open-register").performClick()
        compose.onNodeWithText("register 3").assertExists()
    }

    // AC7: the bundled page asks Cloudflare for a token only when the app needs one, as the web's widget
    @Test
    fun theTurnstilePageIsTheWebsWidget() {
        val page = ApplicationProvider.getApplicationContext<Context>().assets.open("turnstile.html").bufferedReader().use { it.readText() }

        assertTrue(page.contains("https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"))
        listOf("execution: 'execute'", "appearance: 'interaction-only'", "retry: 'never'", "turnstile.reset(widgetId)", "Climbra.token(token)")
            .forEach { assertTrue(it, page.contains(it)) }
    }

    // AC7: no site key, no check and no widget
    @Test
    fun withoutASiteKeyThereIsNoCheck() = kotlinx.coroutines.test.runTest {
        val controller = TurnstileController(siteKey = null, baseUrl = "https://climbra.app", language = "en", dark = false)

        assertEquals(CaptchaResult.Off, controller.token())
        content { TurnstileView(controller, siteKey = null) }
        compose.onNodeWithTag("turnstile").assertDoesNotExist()
    }
}
