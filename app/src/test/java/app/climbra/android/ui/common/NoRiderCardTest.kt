package app.climbra.android.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.calendar.CalendarActions
import app.climbra.android.calendar.CalendarScreen
import app.climbra.android.calendar.CalendarUiState
import app.climbra.android.coach.CoachListActions
import app.climbra.android.coach.CoachListScreen
import app.climbra.android.coach.CoachListUiState
import app.climbra.android.coach.CoachNavigation
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 022 AC1, AC2: the calendar's and the coach's no-rider cards open the profile, not the web. */
@RunWith(AndroidJUnit4::class)
class NoRiderCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val fmt = Fmt(Locale.forLanguageTag("en"))
    private var opened = 0

    @Test
    fun theCalendarsCardOpensTheProfile() {
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                CalendarScreen(
                    CalendarUiState(week = LocalDate.of(2026, 11, 16), today = LocalDate.of(2026, 11, 22), loading = false, noRider = true),
                    fmt,
                    CalendarActions(onCreateProfile = { opened++ }),
                )
            }
        }
        compose.onNodeWithTag("calendar-no-rider").assertIsDisplayed()
        compose.onNodeWithText("Open climbra.app").assertDoesNotExist()
        compose.onNodeWithTag("calendar-no-rider-button").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theCoachsCardOpensTheProfile() {
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                CoachListScreen(
                    CoachListUiState(loading = false, noRider = true),
                    fmt,
                    Instant.parse("2026-11-22T10:00:00Z"),
                    CoachNavigation(),
                    CoachListActions(onCreateProfile = { opened++ }),
                )
            }
        }
        compose.onNodeWithText("Create it with your name and weight", substring = true).assertExists()
        compose.onNodeWithTag("coach-no-rider-button").performClick()
        assertEquals(1, opened)
    }
}
