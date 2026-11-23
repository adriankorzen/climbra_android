package app.climbra.android.powercurve

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.data.api.PowerCurvePeriod
import app.climbra.android.data.api.PowerCurvePoint
import app.climbra.android.data.api.PowerCurveResponse
import app.climbra.android.shell.Shell
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 026: the power curve screen shows the backend's figures; texts are English (the default resources). */
@RunWith(AndroidJUnit4::class)
class PowerCurveScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val fmt = Fmt(Locale.ENGLISH)
    private val opened = mutableListOf<Long>()
    private val selected = mutableListOf<CurvePeriod>()

    private fun point(duration: Int, watts: Int, change: Int? = null, ride: Long = 7) =
        PowerCurvePoint(duration, watts, wattsPerKg = watts / 60.0, activityId = ride, date = "2026-10-04", changeWatts = change)

    private val curve = PowerCurveResponse(
        days = 90,
        current = PowerCurvePeriod("2026-08-26", "2026-11-23", listOf(point(5, 1012, 35), point(60, 520, -10, 8), point(300, 360))),
        previous = PowerCurvePeriod("2026-05-28", "2026-08-25", listOf(point(5, 977), point(60, 530))),
    )

    private fun show(state: PowerCurveUiState) = compose.setContent {
        ClimbraTheme(darkTheme = false) {
            PowerCurveScreen(state, fmt, onBack = {}, onSelect = { selected += it }, onRefresh = {}, onOpenRide = { opened += it })
        }
    }

    // AC2: the axis and the pick
    @Test
    fun theNearestDurationIsPicked() {
        assertEquals(0f, xFraction(1), 0.0001f)
        assertEquals(1f, xFraction(MAX_SECONDS), 0.0001f)
        val points = curve.current.points
        assertEquals(5, pointAt(points, xFraction(6))?.durationSeconds)
        assertEquals(300, pointAt(points, 0.95f)?.durationSeconds)
        assertNull(pointAt(points, 1.2f))
        assertNull(pointAt(emptyList(), 0.5f))
    }

    // AC1, AC2: both lines' legend; a tap shows the duration's figures with the previous watts
    @Test
    fun aTapOnTheChartShowsTheDuration() {
        show(PowerCurveUiState(loading = false, curve = curve))
        compose.onNodeWithText("Period before", substring = true).assertExists()

        compose.onNodeWithTag("power-curve-chart").performTouchInput {
            click(Offset(width * xFraction(5), height / 2f))
        }

        compose.onNodeWithTag("power-curve-picked")
            .assertTextEquals("5 s · 1,012 W · 16.87 W/kg · Oct 4, 2026 · before 977 W")
    }

    // AC3: the key durations with the backend's change and the ride
    @Test
    fun theTableShowsTheKeyDurationsAndOpensTheRide() {
        show(PowerCurveUiState(loading = false, curve = curve))

        compose.onNodeWithTag("key-5-watts", useUnmergedTree = true).assertTextEquals("1,012 W")
        compose.onNodeWithTag("key-5-change", useUnmergedTree = true).assertTextEquals("+35 W")
        compose.onNodeWithTag("key-60-change", useUnmergedTree = true).assertTextEquals("-10 W")
        compose.onNodeWithTag("key-300-change", useUnmergedTree = true).assertTextEquals("–")
        compose.onNodeWithTag("key-3600-watts", useUnmergedTree = true).assertTextEquals("–")
        compose.onNodeWithTag("key-60").performScrollTo().performClick()
        assertEquals(listOf(8L), opened)
    }

    // AC1: all rides have no previous period, so no change and no second line
    @Test
    fun allRidesHaveOneCurve() {
        show(PowerCurveUiState(loading = false, period = CurvePeriod.ALL, curve = curve.copy(days = null, previous = null)))

        compose.onNodeWithText("Period before", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("key-5-change", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("period-DAYS_28").performClick()
        assertEquals(listOf(CurvePeriod.DAYS_28), selected)
    }

    // AC4
    @Test
    fun ridesStillWithoutACurveAndAnEmptyPeriodAreTold() {
        show(
            PowerCurveUiState(
                loading = false,
                curve = curve.copy(ridesWithoutCurve = 12, current = PowerCurvePeriod("2026-08-26", "2026-11-23", emptyList())),
            ),
        )

        compose.onNodeWithTag("power-curve-pending")
            .assertTextContains("12 rides are still being analysed for the power curve", substring = true)
        compose.onNodeWithTag("power-curve-empty").assertTextEquals("No rides with power in this period.")
        compose.onNodeWithTag("power-curve-table").assertDoesNotExist()
    }

    // AC5
    @Test
    fun anErrorOffersRetry() {
        var retried = 0
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                PowerCurveScreen(
                    PowerCurveUiState(loading = false, error = UiText(app.climbra.android.R.string.error_unreachable)),
                    fmt, onBack = {}, onSelect = {}, onRefresh = { retried++ }, onOpenRide = {},
                )
            }
        }

        compose.onNodeWithTag("power-curve-error").assertIsDisplayed()
        compose.onNodeWithTag("power-curve-retry").performClick()
        assertEquals(1, retried)
    }

    // AC7: opened from the training log, Back returns to it; from the dashboard, on its stack
    @Test
    fun theScreenOpensOnTheStackOfItsTab() {
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                Shell(
                    dashboard = { nav -> Button(onClick = nav::openPowerCurve, Modifier.testTag("dashboard-open")) { Text("open") } },
                    activities = { nav -> Button(onClick = nav::openPowerCurve, Modifier.testTag("log-open")) { Text("open") } },
                    profile = {},
                    powerCurve = { nav ->
                        Column {
                            Text("curve", Modifier.testTag("curve-screen"))
                            Button(onClick = nav::back, Modifier.testTag("curve-back")) { Text("back") }
                        }
                    },
                )
            }
        }

        compose.onNodeWithTag("dashboard-open").performClick()
        compose.onNodeWithTag("curve-screen").assertIsDisplayed()
        compose.onNodeWithTag("curve-back").performClick()
        compose.onNodeWithTag("dashboard-open").assertIsDisplayed()

        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("log-open").performClick()
        compose.onNodeWithTag("curve-screen").assertIsDisplayed()
        compose.onNodeWithTag("curve-back").performClick()
        compose.onNodeWithTag("log-open").assertIsDisplayed()
    }
}
