package app.climbra.android.garmin

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.R
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.theme.ClimbraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 016 AC1, AC2: the consent dialog; AC3: the return address the app takes. */
@RunWith(AndroidJUnit4::class)
class GarminConsentDialogTest {

    @get:Rule
    val compose = createComposeRule()

    // AC1
    @Test
    fun continueNeedsTheConsent() {
        val calls = mutableListOf<String>()
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                GarminConsentDialog(
                    GarminConsent(5),
                    onContinue = { calls += "continue" },
                    onDismiss = { calls += "dismiss" },
                    onOpenPrivacy = { calls += "privacy" },
                )
            }
        }

        compose.onNodeWithText("Climbra will transfer the planned workouts", substring = true).assertExists()
        compose.onNodeWithTag("garmin-consent-again").assertDoesNotExist()
        compose.onNodeWithTag("garmin-consent-continue").assertIsNotEnabled()

        compose.onNodeWithTag("garmin-privacy").performScrollTo().performClick()
        compose.onNodeWithTag("garmin-consent").performScrollTo().performClick()
        compose.onNodeWithTag("garmin-consent-continue").assertIsEnabled().performClick()

        assertEquals(listOf("privacy", "continue"), calls)
    }

    // AC1, AC2
    @Test
    fun theStatusTheErrorAndTheRunningRequestShow() {
        var consent by mutableStateOf(GarminConsent(5, GarminStatus.NEEDS_RECONNECT))
        compose.setContent {
            ClimbraTheme(darkTheme = false) { GarminConsentDialog(consent, onContinue = {}, onDismiss = {}, onOpenPrivacy = {}) }
        }

        compose.onNodeWithText("The connection with Garmin Connect has ended", substring = true).assertExists()
        compose.onNodeWithTag("garmin-consent").performScrollTo().performClick()

        consent = consent.copy(starting = true)
        compose.onNodeWithTag("garmin-consent-progress", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("garmin-consent-continue").assertIsNotEnabled()

        consent = consent.copy(starting = false, error = UiText(R.string.api_error_garmin_not_configured))
        compose.onNodeWithTag("garmin-consent-error").performScrollTo()
        compose.onNodeWithText("Garmin Connect is not available.").assertExists()
        compose.onNodeWithTag("garmin-consent-continue").assertIsEnabled()
    }

    // AC3
    @Test
    fun onlyTheGarminReturnAddressGivesAState() {
        fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

        assertEquals("s-1", view("climbra://garmin-connect?state=s-1").garminConnectState())
        assertNull(view("climbra://garmin-connect").garminConnectState())
        assertNull(view("climbra://other?state=s-1").garminConnectState())
        assertNull(view("https://climbra.app/garmin-connect?state=s-1").garminConnectState())
        assertNull(Intent(Intent.ACTION_SEND).garminConnectState())
        assertNull((null as Intent?).garminConnectState())
    }
}
