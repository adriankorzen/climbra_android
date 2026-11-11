package app.climbra.android.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.data.api.AnalysisSummary
import app.climbra.android.data.api.DailyLoad
import app.climbra.android.data.api.Fitness
import app.climbra.android.data.api.FormRange
import app.climbra.android.data.api.LoadChanges
import app.climbra.android.data.api.LoadPeriod
import app.climbra.android.data.api.Rider
import app.climbra.android.data.api.ThresholdEntry
import app.climbra.android.data.api.TrainingLoadResponse
import app.climbra.android.data.api.WeekLoad
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 002: the dashboard screen shows the state as it is; texts are English (the default resources). */
@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val polish = Fmt(Locale.forLanguageTag("pl"))
    private val english = Fmt(Locale.forLanguageTag("en"))

    private fun load(form: FormRange = FormRange.NEUTRAL, tsb: Double = -7.4) = TrainingLoadResponse(
        today = "2026-11-11",
        days = (0 until 365).map { DailyLoad("2026-11-11", tss = it % 90.0, ctl = it / 10.0, atl = it / 8.0, tsb = -3.0) },
        weeks = (0 until 12).map { WeekLoad("2026-11-09", tss = 30.0 * it, seconds = 3600L * it, rides = it) },
        last7Days = LoadPeriod(tss = 1332.8, seconds = 18_900, meters = 151_200.0, rides = 4),
        previous7Days = LoadPeriod(tss = 350.0, seconds = 20_000, meters = 160_000.0, rides = 4),
        changes = LoadChanges(tss = 0.5, seconds = -0.055, meters = -0.055, rides = 0.0),
        fitness = Fitness(ctl = 36.2, atl = 46.9, tsb = tsb, ctlChange = 0.06, atlChange = null, form = form),
    )

    private val rider = Rider(id = 5, name = "Anna", currentThresholds = ThresholdEntry(260), ftpWattsPerKg = 3.611)

    private fun ride(id: Long, day: String, heartRate: Boolean = false) = AnalysisSummary(
        activityId = id,
        name = "Ride $id",
        startTime = "${day}T06:00:00Z",
        startTimeLocal = "${day}T08:00:00",
        durationSeconds = 3600,
        normalizedPower = 217.4,
        intensityFactor = 0.836,
        trainingLoad = 70.2,
        trainingLoadSource = if (heartRate) "HEART_RATE" else "POWER",
    )

    private val ready = DashboardUiState(
        loading = false,
        rider = rider,
        load = load(),
        rides = listOf(ride(3, "2026-11-11"), ride(2, "2026-11-09", heartRate = true), ride(1, "2026-11-06")),
    )

    private var refreshes = 0
    private var webOpened = 0
    private val selectedRanges = mutableListOf<Int>()

    private fun show(state: () -> DashboardUiState, fmt: Fmt = english) = compose.setContent {
        ClimbraTheme(darkTheme = false) {
            DashboardScreen(
                state = state(),
                fmt = fmt,
                onRefresh = { refreshes++ },
                onSelectRange = { selectedRanges += it },
                onOpenWeb = { webOpened++ },
            )
        }
    }

    private fun tile(tag: String, text: String) =
        compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    // AC1, AC4
    @Test
    fun tilesShowTheBackendsFiguresFormatted() {
        show({ ready })

        compose.onNodeWithTag("dashboard-today").assertTextContains("Wednesday, November 11, 2026")
        tile("kpi-tss", "1,333").assertExists()
        tile("kpi-tss", "▲ 50%").assertExists()
        tile("kpi-hours", "5:15").assertExists()
        tile("kpi-hours", "▼ 6%").assertExists()
        tile("kpi-distance", "151").assertExists()
        tile("kpi-rides", "4").assertExists()
        tile("kpi-rides", "• 0%").assertExists()
        tile("kpi-ftp", "260").assertExists()
        tile("kpi-ftp", "3.61 W/kg").assertExists()
        tile("kpi-ctl", "36").assertExists()
        tile("kpi-ctl", "▲ 6%").assertExists()
        tile("kpi-atl", "47").assertExists()
        // No change to show against a fatigue of 0 a week ago
        tile("kpi-atl", "%").assertDoesNotExist()
    }

    // AC10
    @Test
    fun figuresFollowTheAppLanguage() {
        show({ ready }, fmt = polish)

        compose.onNodeWithTag("dashboard-today").assertTextContains("środa, 11 listopada 2026")
        tile("kpi-tss", "1 333").assertExists()
        tile("kpi-ftp", "3,61 W/kg").assertExists()
    }

    // AC4
    @Test
    fun aRiderWithoutThresholdsHasADashForFtp() {
        show({ ready.copy(rider = Rider(id = 5)) })

        tile("kpi-ftp", "–").assertExists()
        tile("kpi-ftp", "W/kg").assertDoesNotExist()
    }

    // AC2
    @Test
    fun theFormTileShowsTheSignAndTheRangeOfEachForm() {
        var state by mutableStateOf(ready)
        show({ state })
        val expected = mapOf(
            FormRange.TRANSITION to (30.0 to ("+30" to "Transition")),
            FormRange.FRESH to (12.4 to ("+12" to "Fresh")),
            FormRange.NEUTRAL to (0.2 to ("0" to "Neutral")),
            FormRange.OPTIMAL to (-20.0 to ("-20" to "Productive")),
            FormRange.OVERLOAD to (-41.0 to ("-41" to "High risk")),
        )
        for ((form, values) in expected) {
            val (tsb, texts) = values
            state = ready.copy(load = load(form, tsb))
            tile("kpi-tsb", texts.first).assertExists()
            tile("kpi-tsb", texts.second).assertExists()
        }
        // Each range has its own color, as on the web
        assertEquals(5, FormRange.entries.map { formName(it) }.toSet().size)
    }

    // AC3
    @Test
    fun theRangeButtonsReportTheChoiceAndMarkTheCurrentRange() {
        var state by mutableStateOf(ready)
        show({ state })

        compose.onNodeWithTag("range-90").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("range-365").performClick()
        assertEquals(listOf(365), selectedRanges)

        state = ready.copy(range = 365)
        compose.onNodeWithTag("range-365").assertIsSelected()
        assertEquals(365, state.visibleDays.size)
    }

    // AC3, AC4
    @Test
    fun bothChartsAreDrawn() {
        show({ ready })

        compose.onNodeWithTag("chart-pmc").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chart-weekly").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("12 weeks").assertExists()
    }

    // AC5
    @Test
    fun recentRidesAreListedInOrderWithTheHeartRateMarker() {
        show({ ready })

        compose.onNodeWithTag("ride-3").performScrollTo()
        val tops = listOf(3, 2, 1).map { compose.onNodeWithTag("ride-$it").fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops)
        compose.onNode(hasText("Wed, Nov 11 · 1:00:00 · NP 217 W · IF 0.84")).assertExists()
        compose.onNodeWithTag("hr-marker-2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("hr-marker-3", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("rides-none").assertDoesNotExist()
    }

    // AC6
    @Test
    fun aRiderWithoutAnyRideGetsTheImportHintOneWithOldRidesDoesNot() {
        var state by mutableStateOf(ready.copy(rides = emptyList(), noRidesAtAll = true))
        show({ state })

        compose.onNodeWithTag("dashboard-empty").assertIsDisplayed()
        compose.onNodeWithText("Open climbra.app").performClick()
        assertEquals(1, webOpened)

        state = ready.copy(rides = emptyList(), noRidesAtAll = false)
        compose.onNodeWithTag("dashboard-empty").assertDoesNotExist()
        compose.onNodeWithTag("rides-none").performScrollTo().assertIsDisplayed()
    }

    // AC7
    @Test
    fun aFailedLoadShowsTheErrorWithRetryAndDashes() {
        show({ DashboardUiState(loading = false, failed = true) })

        compose.onNodeWithTag("dashboard-error").assertIsDisplayed()
        compose.onNodeWithText("Cannot load the analyses.").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-retry").performClick()
        assertEquals(1, refreshes)
        tile("kpi-tss", "–").assertExists()
        tile("kpi-ctl", "–").assertExists()
    }

    // AC9
    @Test
    fun anAccountWithoutRiderSeesTheHintToCreateTheProfile() {
        show({ DashboardUiState(loading = false, noRider = true) })

        compose.onNodeWithTag("dashboard-no-rider").assertIsDisplayed()
        compose.onNodeWithText("Create your rider profile").assertIsDisplayed()
        compose.onNodeWithTag("kpi-tss").assertDoesNotExist()
    }
}
