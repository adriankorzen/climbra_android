package app.climbra.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.app.ClimbraContent
import app.climbra.android.auth.LoginScreen
import app.climbra.android.auth.LoginUiState
import app.climbra.android.auth.TermsScreen
import app.climbra.android.auth.TermsUiState
import app.climbra.android.data.api.AccountResponse
import app.climbra.android.data.net.ApiError
import app.climbra.android.session.SessionState
import app.climbra.android.shell.Shell
import app.climbra.android.ui.theme.ClimbraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 001 screens rendered with Robolectric, against plain state (no backend). */
@RunWith(AndroidJUnit4::class)
class ScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val account = AccountResponse(id = 7, email = "anna@example.com", role = "USER", termsAccepted = true)

    private fun setContent(content: @Composable () -> Unit) = compose.setContent { ClimbraTheme(darkTheme = false) { content() } }

    @Composable
    private fun Login(state: LoginUiState, expired: Boolean = false) =
        LoginScreen(state, expired, {}, {}, {}, {}, {}, {}, {})

    // AC6
    @Test
    fun theGoogleButtonIsShownOnlyWhenAvailable() {
        var state by mutableStateOf(LoginUiState(googleAvailable = false))
        setContent { Login(state) }
        compose.onNodeWithTag("login-google").assertDoesNotExist()

        state = LoginUiState(googleAvailable = true)
        compose.onNodeWithTag("login-google").performScrollTo().assertIsDisplayed()
    }

    // AC5, AC8
    @Test
    fun loginShowsTheErrorOrThatTheSessionHasEnded() {
        var state by mutableStateOf(LoginUiState())
        var expired by mutableStateOf(true)
        setContent { Login(state, expired) }
        compose.onNodeWithText("Your session has ended. Please log in again.").assertIsDisplayed()

        expired = false
        state = LoginUiState(error = app.climbra.android.ui.common.UiText(app.climbra.android.R.string.api_error_bad_credentials))
        compose.onNodeWithText("Wrong email or password.").assertIsDisplayed()
    }

    // AC7
    @Test
    fun acceptIsEnabledOnlyWithBothConsents() {
        var state by mutableStateOf(TermsUiState())
        setContent {
            TermsScreen(
                state = state,
                documentsChanged = false,
                onTermsChecked = { state = state.copy(termsChecked = it) },
                onHealthDataChecked = { state = state.copy(healthDataChecked = it) },
                onAccept = {},
                onLogout = {},
                onOpenTerms = {},
                onOpenPrivacy = {},
            )
        }
        compose.onNodeWithTag("terms-submit").assertIsNotEnabled()

        compose.onNodeWithTag("terms-accept-terms").performClick()
        compose.onNodeWithTag("terms-submit").assertIsNotEnabled()

        compose.onNodeWithTag("terms-accept-health-data").performClick()
        compose.onNodeWithTag("terms-submit").assertIsEnabled()

        compose.onNodeWithTag("terms-accept-terms").performClick()
        compose.onNodeWithTag("terms-submit").assertIsNotEnabled()
    }

    // AC11
    @Test
    fun theBottomNavigationSwitchesTabsAndKeepsTheirState() {
        setContent {
            Shell(
                dashboard = { Counter("dashboard") },
                activities = { Counter("activities") },
                profile = { Text("Profile content") },
            )
        }
        compose.onNodeWithTag("nav-dashboard").assertIsSelected()
        compose.onNodeWithText("Activities").assertIsDisplayed()
        compose.onNodeWithText("Profile").assertIsDisplayed()

        compose.onNodeWithTag("dashboard-count").performClick().performClick()
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("nav-activities").assertIsSelected()
        compose.onNodeWithTag("activities-count").performClick()
        compose.onNodeWithTag("nav-profile").performClick()
        compose.onNodeWithText("Profile content").assertIsDisplayed()

        compose.onNodeWithTag("nav-dashboard").performClick()
        compose.onNodeWithText("dashboard 2").assertIsDisplayed()
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithText("activities 1").assertIsDisplayed()
    }

    @Composable
    private fun Counter(name: String) {
        var count by rememberSaveable { mutableStateOf(0) }
        Column {
            Button(onClick = { count++ }, modifier = androidx.compose.ui.Modifier.testTag("$name-count")) {
                Text("$name $count")
            }
        }
    }

    // AC1, AC7, AC8
    @Test
    fun theSessionDecidesTheScreenAndLeavesNothingBehind() {
        var state by mutableStateOf<SessionState>(SessionState.Unavailable(ApiError.Network))
        var retries = 0
        setContent {
            ClimbraContent(
                state = state,
                onRetry = { retries++ },
                login = { loggedOut -> Text(if (loggedOut.expired) "login expired" else "login") },
                terms = { Text("terms") },
                shell = { Text("shell of ${it.email}") },
            )
        }
        compose.onNodeWithTag("start-retry").performClick()
        assertEquals(1, retries)

        state = SessionState.LoggedIn(account)
        compose.onNodeWithText("shell of anna@example.com").assertIsDisplayed()

        state = SessionState.NeedsTerms(account.copy(termsAccepted = false))
        compose.onNodeWithText("terms").assertIsDisplayed()
        compose.onNodeWithText("shell of anna@example.com").assertDoesNotExist()

        state = SessionState.LoggedOut(expired = true)
        compose.onNodeWithText("login expired").assertIsDisplayed()
        compose.onNodeWithText("terms").assertDoesNotExist()
    }
}
