package app.climbra.android.auth

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.MainActivity
import app.climbra.android.ui.common.LocalOffline
import app.climbra.android.ui.theme.ClimbraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 021 AC1 - AC3, AC6: the App Links, their parsing, where they open and the screens offline. */
@RunWith(AndroidJUnit4::class)
class EmailLinksTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun content(body: @Composable () -> Unit) = compose.setContent { ClimbraTheme(darkTheme = false) { body() } }

    private fun opens(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == MainActivity::class.java.name }
    }

    // AC1: the four paths of climbra.app, nothing else
    @Test
    fun theAppOpensOnlyTheEmailLinks() {
        listOf("verify-email?token=abc", "reset-password?token=abc", "login", "forgot-password").forEach {
            assertTrue(it, opens("https://climbra.app/$it"))
        }
        listOf(
            "https://climbra.app/terms",
            "https://climbra.app/activities/3",
            // Spec 028 AC8: a shared ride or week opens in the browser, the rider's too
            "https://climbra.app/s/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
            "https://climbra.app/",
            "http://climbra.app/login",
            "https://example.com/login",
        ).forEach { assertTrue(it, !opens(it)) }
    }

    // AC2: the links and their tokens
    @Test
    fun linksAreParsed() {
        assertEquals(EmailLink.Verify("abc"), emailLink(Uri.parse("https://climbra.app/verify-email?token=abc")))
        assertEquals(EmailLink.Reset("x-1"), emailLink(Uri.parse("https://climbra.app/reset-password?token=x-1&utm=1")))
        assertEquals(EmailLink.Login, emailLink(Uri.parse("https://climbra.app/login")))
        assertEquals(EmailLink.ForgotPassword, emailLink(Uri.parse("https://climbra.app/forgot-password/")))
        assertEquals(EmailLink.Verify(""), emailLink(Uri.parse("https://climbra.app/verify-email")))
        assertNull(emailLink(Uri.parse("https://climbra.app/terms")))
        assertNull(emailLink(Uri.parse("https://evil.example/verify-email?token=abc")))
        assertNull(emailLink(Uri.parse("climbra://garmin-connect?state=1")))
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://climbra.app/login"))
        assertEquals(EmailLink.Login, intent.emailLink())
        assertNull(Intent(Intent.ACTION_SEND).emailLink())
    }

    @Test
    fun aNewerLinkReplacesOneNotTaken() {
        val links = EmailLinks()
        links.offer(EmailLink.Verify("a"))
        links.offer(EmailLink.Reset("b"))
        links.taken(EmailLink.Verify("a"))
        assertEquals(EmailLink.Reset("b"), links.pending.value)
        links.taken(EmailLink.Reset("b"))
        assertNull(links.pending.value)
    }

    @Composable
    private fun LoggedOut(link: EmailLink?, taken: MutableList<EmailLink>) = LoggedOutNavigation(
        link = link,
        onLinkTaken = { taken += it },
        login = { Text("login", Modifier.testTag("slot-login")) },
        register = { Text("register", Modifier.testTag("slot-register")) },
        forgotPassword = { Text("forgot", Modifier.testTag("slot-forgot")) },
        verifyEmail = { nav -> Text("verify ${nav.token}", Modifier.testTag("slot-verify")) },
        resetPassword = { nav -> Text("reset ${nav.token}", Modifier.testTag("slot-reset")) },
    )

    // AC3: logged out each link opens its screen above the login
    @Test
    fun loggedOutTheLinkOpensItsScreen() {
        val taken = mutableListOf<EmailLink>()
        var link by mutableStateOf<EmailLink?>(null)
        content { LoggedOut(link, taken) }
        compose.onNodeWithTag("slot-login").assertIsDisplayed()

        link = EmailLink.Verify("abc")
        compose.onNodeWithTag("slot-verify").assertIsDisplayed()
        link = EmailLink.Reset("def")
        compose.onNodeWithTag("slot-reset").assertIsDisplayed()
        link = EmailLink.ForgotPassword
        compose.onNodeWithTag("slot-forgot").assertIsDisplayed()
        link = EmailLink.Login
        compose.onNodeWithTag("slot-login").assertIsDisplayed()

        compose.runOnIdle {
            assertEquals(listOf(EmailLink.Verify("abc"), EmailLink.Reset("def"), EmailLink.ForgotPassword, EmailLink.Login), taken)
        }
    }

    // AC3: logged in Verify and Reset open above the shell; Login and Forgot password open nothing
    @Test
    fun loggedInOnlyVerifyAndResetOpen() {
        val taken = mutableListOf<EmailLink>()
        var link by mutableStateOf<EmailLink?>(null)
        content {
            SignedInEmailLinks(
                link = link,
                onLinkTaken = { taken += it },
                screen = { shown, token, _, open ->
                    Column {
                        Text("$shown $token", Modifier.testTag("slot-$shown"))
                        TextButton(onClick = { open(null) }, modifier = Modifier.testTag("close")) { Text("x") }
                    }
                },
            ) { Text("shell", Modifier.testTag("slot-shell")) }
        }

        link = EmailLink.Login
        compose.onNodeWithTag("email-link-screen").assertDoesNotExist()
        link = EmailLink.ForgotPassword
        compose.onNodeWithTag("email-link-screen").assertDoesNotExist()

        link = EmailLink.Verify("abc")
        compose.onNodeWithTag("slot-VERIFY_EMAIL").assertIsDisplayed()
        compose.onNodeWithTag("close").performClick()
        compose.onNodeWithTag("email-link-screen").assertDoesNotExist()
        compose.onNodeWithTag("slot-shell").assertIsDisplayed()

        link = EmailLink.Reset("def")
        compose.onNodeWithTag("slot-RESET_PASSWORD").assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, taken.size) }
    }

    // AC6: offline the send buttons are disabled with the note
    @Test
    fun offlineTheButtonsAreDisabled() {
        var reset by mutableStateOf(false)
        content {
            CompositionLocalProvider(LocalOffline provides true) {
                if (reset) {
                    ResetPasswordScreen(ResetPasswordUiState(), {}, {}, {}, {}, {}, {})
                } else {
                    VerifyEmailScreen(VerifyEmailUiState(), {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("verify-submit").assertIsNotEnabled()
        compose.onNodeWithTag("offline-note").assertIsDisplayed()
        reset = true
        compose.onNodeWithTag("reset-submit").assertIsNotEnabled()
        compose.onNodeWithTag("offline-note").assertIsDisplayed()
    }

    // AC4, AC5: the screens' states
    @Test
    fun theScreensShowTheirStates() {
        var verify by mutableStateOf(VerifyEmailUiState(wrongPassword = true))
        var reset by mutableStateOf<ResetPasswordUiState?>(null)
        content {
            val current = reset
            if (current == null) VerifyEmailScreen(verify, {}, {}, {}, {}, {}, {})
            else ResetPasswordScreen(current, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithTag("verify-password-supporting", useUnmergedTree = true)
            .assertIsDisplayed()
        verify = VerifyEmailUiState(invalid = true)
        compose.onNodeWithTag("verify-invalid").assertIsDisplayed()
        compose.onNodeWithTag("verify-resend").assertIsDisplayed()
        verify = VerifyEmailUiState(invalid = true, resent = true)
        compose.onNodeWithTag("verify-resent").assertIsDisplayed()

        reset = ResetPasswordUiState(done = true)
        compose.onNodeWithTag("reset-done").assertIsDisplayed()
        compose.onNodeWithTag("reset-login").assertIsDisplayed()
        reset = ResetPasswordUiState(invalid = true)
        compose.onNodeWithTag("reset-new-link").assertIsDisplayed()
    }
}
