package app.climbra.android.shell

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.data.net.OfflineState
import app.climbra.android.ui.common.LocalOffline
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 018 AC5: the shell's offline banner and the offline flag for the screens. */
@RunWith(AndroidJUnit4::class)
class OfflineShellTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offlineTheBannerShowsTheOldestDataAndRetriesAndScreensKnow() {
        val dataFrom = Instant.parse("2026-11-19T18:05:00Z")
        var offline: OfflineState? by mutableStateOf(OfflineState(dataFrom))
        var retries = 0
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                Shell(
                    dashboard = { Text("offline ${LocalOffline.current}", Modifier.testTag("dashboard")) },
                    activities = {},
                    profile = {},
                    offline = offline,
                    onRetryOffline = { retries++ },
                )
            }
        }

        compose.onNodeWithTag("offline-banner").assertIsDisplayed()
        val time = Fmt(Locale.forLanguageTag("en")).instant(dataFrom.toString())
        compose.onNodeWithText("Offline · data from", substring = true).assertIsDisplayed()
        compose.onNodeWithText(time, substring = true).assertExists()
        compose.onNodeWithTag("dashboard").assert(hasText("offline true"))

        compose.onNodeWithTag("offline-retry").performClick()
        assertEquals(1, retries)

        offline = null
        compose.onNodeWithTag("offline-banner").assertDoesNotExist()
        compose.onNodeWithTag("dashboard").assert(hasText("offline false"))
    }
}
