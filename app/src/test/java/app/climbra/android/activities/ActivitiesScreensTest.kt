package app.climbra.android.activities

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.data.api.ActivityDetail
import app.climbra.android.data.api.ActivityAnalysis
import app.climbra.android.data.api.AnalysisResponse
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.ComparisonRatio
import app.climbra.android.data.api.ComplianceLevel
import app.climbra.android.data.api.HeartRateMetrics
import app.climbra.android.data.api.IntervalBlock
import app.climbra.android.data.api.Intervals
import app.climbra.android.data.api.PlannedWorkoutLink
import app.climbra.android.data.api.PowerMetrics
import app.climbra.android.data.api.Recording
import app.climbra.android.data.api.RideRow
import app.climbra.android.intervals.SyncState
import app.climbra.android.data.api.RideTotals
import app.climbra.android.data.api.Samples
import app.climbra.android.data.api.ThresholdsUsed
import app.climbra.android.data.api.WorkoutComparison
import app.climbra.android.data.api.ZoneTime
import app.climbra.android.shell.Shell
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 003 screens rendered with Robolectric against plain state; texts are English (the default resources). */
@RunWith(AndroidJUnit4::class)
class ActivitiesScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val fmt = Fmt(Locale.forLanguageTag("en"))

    private fun content(body: @Composable () -> Unit) = compose.setContent { ClimbraTheme(darkTheme = false) { body() } }

    private fun inside(tag: String, text: String) =
        compose.onNode(
            hasText(text, substring = true) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true,
        )

    private fun row(id: Long, name: String, heartRate: Boolean = false) = RideRow(
        id = id, start = "2026-11-0${id}T08:00:00", date = "2026-11-0$id", name = name, source = "GARMIN",
        type = "road_biking", duration = 3600, distance = 30_100.0, np = 217.0, intensityFactor = 0.84, tss = 70.2,
        tssFromHeartRate = heartRate, intervals = 3, kj = 724.0,
    )

    private val period = Period.of(RangePreset.FOUR_WEEKS, LocalDate.of(2026, 11, 11))

    private val log = ActivitiesUiState(
        period = period,
        loading = false,
        rows = listOf(row(3, "Tempo"), row(2, "Easy", heartRate = true)),
        totals = RideTotals(rides = 12, seconds = 51_300, meters = 410_000.0, elevation = 5200.0, kj = 9800.0, tss = 905.4),
        totalRows = 12,
    )

    private val opened = mutableListOf<Long>()
    private val presets = mutableListOf<RangePreset>()
    private val texts = mutableListOf<String>()
    private var webOpened = 0

    private fun showLog(sync: () -> SyncState = { SyncState() }, state: () -> ActivitiesUiState) = content {
        ActivitiesScreen(
            state = state(),
            fmt = fmt,
            onPreset = { presets += it },
            onCustom = { _, _ -> },
            onTextChange = { texts += it },
            onLoadMore = {},
            onRefresh = {},
            onOpenRide = { opened += it },
            onOpenWeb = { webOpened++ },
            sync = sync(),
        )
    }

    // AC2, AC3
    @Test
    fun theLogShowsTheBackendsTotalsAndRowsAndOpensARide() {
        showLog { log }

        inside("activities-totals", "12").assertExists()
        inside("activities-totals", "14:15").assertExists()
        inside("activities-totals", "410 km").assertExists()
        inside("activities-totals", "9,800 kJ").assertExists()
        inside("activities-totals", "905").assertExists()
        inside("ride-row-3", "Tempo").assertExists()
        inside("ride-row-3", "1:00:00 · 30.1 km · NP 217 W").assertExists()
        compose.onNodeWithTag("ride-if-3", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("activities-list").performScrollToNode(hasTestTag("ride-row-2"))
        compose.onNodeWithTag("ride-hr-2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ride-hr-3", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag("ride-row-2").performClick()
        assertEquals(listOf(2L), opened)
    }

    // AC1, AC2
    @Test
    fun presetsAndTheSearchReportTheChoice() {
        showLog { log }

        compose.onNodeWithTag("preset-four_weeks").assertIsSelected()
        compose.onNodeWithTag("preset-week").performClick()
        compose.onNodeWithTag("preset-year").performClick()
        compose.onNodeWithTag("activities-search").performTextInput("vo2")

        assertEquals(listOf(RangePreset.WEEK, RangePreset.YEAR), presets)
        assertEquals(listOf("vo2"), texts)
    }

    // AC4
    @Test
    fun emptyStatesTellANewRiderFromAnEmptyPeriodOrSearch() {
        var state by mutableStateOf(log.copy(rows = emptyList(), hasRides = false, totals = null, totalRows = 0))
        showLog { state }
        compose.onNodeWithTag("activities-empty-all").assertIsDisplayed()
        compose.onNodeWithText("Open climbra.app").performClick()
        assertEquals(1, webOpened)

        state = log.copy(rows = emptyList(), totalRows = 0)
        compose.onNodeWithTag("activities-empty-all").assertDoesNotExist()
        compose.onNodeWithText("No rides in this period.").assertExists()

        state = log.copy(rows = emptyList(), totalRows = 0, text = "vo2")
        compose.onNodeWithText("No rides match the search in this period.").assertExists()
    }

    private fun block(number: Int, start: Int) =
        IntervalBlock(number, start, start + 300, 300, 285.0, 300.0, percentOfTarget = 101.0, heartRateStart = 150.0,
            heartRateEnd = 165.0, maxHeartRate = 166.0, efficiency = 1.8)

    private fun analysis(power: Boolean = true, stored: Boolean = true, zonesFromMax: Boolean = false) = AnalysisResponse(
        activityId = 85,
        stored = stored,
        thresholds = ThresholdsUsed(ftpWatts = 260, lthrBpm = 165, maxHeartRateBpm = 185, effectiveFrom = "2026-01-01"),
        analysis = ActivityAnalysis(
            durationSeconds = 2700,
            distanceMeters = 21_600.0,
            power = if (power) PowerMetrics(230.0, 287.0, 245.0, 1.07, 0.94, 67.0, 620.0) else null,
            heartRate = HeartRateMetrics(163.0, 165.0, stressScore = if (power) null else 72.0),
            averageCadence = 88.0,
            powerZones = if (power) listOf(ZoneTime(2, "Endurance", 1500, 56.0), ZoneTime(5, "VO2 Max", 1200, 44.0)) else emptyList(),
            heartRateZones = listOf(ZoneTime(4, "Threshold", 600, 22.0)),
            heartRateZoneBasis = if (zonesFromMax) "MAX_HR" else "LTHR",
            intervals = if (power) {
                Intervals(detected = true, targetPower = 280.0, targetSource = "AUTO", blocks = listOf(block(1, 600), block(2, 1200)),
                    structure = "INTERVALS", lapsAvailable = true, useLaps = false, durationConsistency = 0.02, powerConsistency = 0.03)
            } else null,
        ),
    )

    private val ride = ActivityDetail(
        id = 85, source = "UPLOAD", name = "Threshold", sportType = "road_biking", startTime = "2026-11-08T07:00:00Z",
        startTimeLocal = "2026-11-08T08:00:00", distanceMeters = 21_600.0, deviceName = "Edge 840",
    )

    private val recording = Recording(
        resolutionSeconds = 5,
        samples = Samples(
            t = (0 until 540).map { it * 5 },
            power = List(540) { 250.0 },
            heartRate = List(540) { 150.0 },
            cadence = List(540) { 88.0 },
            speed = List(540) { null },
            altitude = List(540) { null },
        ),
    )

    private val detail = RideDetailUiState(loading = false, recordingLoading = false, activity = ride, analysis = analysis(), recording = recording)

    private var deletes = 0
    private val sources = mutableListOf<Boolean>()
    private val links = mutableListOf<Long>()

    private fun showDetail(state: () -> RideDetailUiState) = content {
        RideDetailScreen(
            state = state(),
            fmt = fmt,
            onBack = {},
            onRetry = {},
            onReanalyse = {},
            onDownload = {},
            onDelete = { deletes++ },
            onIntervalSource = { sources += it },
            onFormChange = {},
            onApplyDetection = {},
            onResetDetection = {},
            onLink = { links += it },
            onMessageShown = {},
        )
    }

    // AC5
    @Test
    fun theDetailShowsTheFiguresThresholdsZonesAndIntervals() {
        showDetail { detail }

        compose.onNodeWithTag("ride-subtitle").assertExists()
        inside("ride-subtitle", "Road · File · Edge 840").assertExists()
        inside("kpi-tss", "67").assertExists()
        inside("kpi-if", "0.94").assertExists()
        inside("kpi-avg-power", "max 287 W").assertExists()
        inside("kpi-thresholds", "260").assertExists()
        inside("kpi-thresholds", "LTHR 165 · max 185 · valid from Jan 1, 2026").assertExists()
        compose.onNodeWithTag("kpi-hr-tss").assertDoesNotExist()
        compose.onNodeWithTag("ride-no-power").assertDoesNotExist()
        // Zone names come from the app's translations, not from the backend's English names
        compose.onNodeWithTag("power-zones").performScrollTo()
        inside("power-zone-bars", "VO2max").assertExists()
        inside("power-zone-bars", "56%").assertExists()
        compose.onNodeWithTag("intervals").performScrollTo()
        inside("interval-summary", "280 W").assertExists()
        compose.onNodeWithTag("block-WORK-1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("block-WORK-2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("not-stored", useUnmergedTree = true).assertDoesNotExist()
    }

    // AC5
    @Test
    fun aRideWithoutPowerShowsHrTssTheNoteAndTheHints() {
        showDetail { detail.copy(analysis = analysis(power = false, zonesFromMax = true)) }

        compose.onNodeWithTag("ride-no-power", useUnmergedTree = true).assertExists()
        inside("kpi-hr-tss", "72").assertExists()
        compose.onNodeWithTag("power-zones").performScrollTo()
        inside("power-zones", "No power data.").assertExists()
        inside("hr-zones", "Threshold").assertExists()
        compose.onNodeWithTag("hr-zones-from-max", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("intervals").assertDoesNotExist()
    }

    // AC6, AC7
    @Test
    fun theChartShowsOnlyRecordedSeriesAndAnAdHocResultIsMarked() {
        showDetail { detail.copy(analysis = analysis(stored = false)) }

        compose.onNodeWithTag("series-power").assertExists()
        compose.onNodeWithTag("series-heart_rate").assertExists()
        compose.onNodeWithTag("series-speed").assertDoesNotExist()
        compose.onNodeWithTag("series-altitude").assertDoesNotExist()
        inside("ride-chart-window", "0:00 – 45:00").assertExists()

        // Tapping an interval zooms the chart to it
        compose.onNodeWithTag("intervals").performScrollTo()
        compose.onNodeWithTag("not-stored", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("detection-reset", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("block-WORK-1").performScrollTo().performClick()
        compose.onNodeWithTag("ride-chart-window").performScrollTo()
        inside("ride-chart-window", "9:00 – 16:00").assertExists()
        compose.onNodeWithTag("ride-chart-reset").assertExists()
    }


    // AC6
    @Test
    fun theRangeSliderNarrowsTheChartToTheChosenPart() {
        showDetail { detail }

        compose.onNodeWithTag("ride-chart-range").performScrollTo()
        val thumbs = compose.onAllNodes(
            hasAnyAncestor(hasTestTag("ride-chart-range")) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
        )
        thumbs[0].performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }
        thumbs[1].performSemanticsAction(SemanticsActions.SetProgress) { it(1500f) }

        inside("ride-chart-window", "5:00 – 25:00").assertExists()
        compose.onNodeWithTag("ride-chart-reset").performScrollTo().performClick()
        inside("ride-chart-window", "0:00 – 45:00").assertExists()
    }

    // AC6
    @Test
    fun withoutARecordingThePageSaysSo() {
        showDetail { detail.copy(recording = null) }

        compose.onNodeWithTag("ride-no-recording", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ride-chart").assertDoesNotExist()
    }

    // AC8
    @Test
    fun theIntervalSourceSwitchReportsTheChoice() {
        showDetail { detail }

        compose.onNodeWithTag("source-laps").performScrollTo().performClick()

        assertEquals(listOf(true), sources)
    }

    // AC10
    @Test
    fun deletingAsksFirstNamingTheRide() {
        showDetail { detail }

        compose.onNodeWithTag("ride-menu").performClick()
        compose.onNodeWithTag("action-delete").performClick()
        compose.onNodeWithText("Delete the ride?").assertIsDisplayed()
        compose.onNodeWithText("“Threshold”", substring = true).assertIsDisplayed()
        assertEquals(0, deletes)

        compose.onNodeWithTag("confirm-delete").performClick()
        assertEquals(1, deletes)
    }

    // AC11
    @Test
    fun thePlannedWorkoutCardShowsTheComparisonOrOffersLinking() {
        val planned = PlannedWorkoutLink(
            id = 31, date = "2026-11-08", title = "FTP 2x20", type = "THRESHOLD", matchSource = "MANUAL",
            comparison = WorkoutComparison(
                level = ComplianceLevel.MODERATE,
                tss = ComparisonRatio(85.0, 67.0, 0.79, ComplianceLevel.MODERATE),
            ),
        )
        var state by mutableStateOf(detail.copy(activity = ride.copy(plannedWorkout = planned)))
        showDetail { state }
        inside("planned-workout", "FTP 2x20").assertExists()
        inside("planned-workout", "Threshold").assertExists()
        inside("planned-workout", "linked by you").assertExists()
        inside("comparison-level", "Deviation").assertExists()
        inside("planned-workout", "79%").assertExists()

        state = detail.copy(candidates = listOf(CalendarWorkout(id = 31, date = "2026-11-08", title = "FTP 2x20", type = "THRESHOLD", status = "MISSED")))
        compose.onNodeWithTag("unplanned-ride").assertExists()
        compose.onNodeWithTag("link-workout").performClick()
        compose.onNodeWithTag("link-option-31").performClick()
        compose.onNodeWithTag("link-submit").performClick()
        assertEquals(listOf(31L), links)
    }

    // AC12
    @Test
    fun aMissingRideSaysSo() {
        showDetail { RideDetailUiState(loading = false, recordingLoading = false, notFound = true) }

        compose.onNodeWithText("Activity not found.").assertIsDisplayed()
        compose.onNodeWithTag("ride-menu").assertDoesNotExist()
    }

    // AC3: a ride opens on the stack of the tab it was opened from, which stays selected
    @Test
    fun aRideOpensOnTheTabsStackAndBackReturns() {
        content {
            Shell(
                dashboard = { nav -> OpenButton("dashboard", nav::openRide) },
                activities = { nav -> OpenButton("activities", nav::openRide) },
                profile = { Text("profile") },
                ride = { nav -> Button(onClick = nav::back, modifier = Modifier.testTag("ride-screen")) { Text("ride") } },
            )
        }

        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("open-activities").performClick()
        compose.onNodeWithTag("ride-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-activities").assertIsSelected()

        compose.onNodeWithTag("ride-screen").performClick()
        compose.onNodeWithTag("open-activities").assertIsDisplayed()
    }

    // AC3: tapping a tab shows its page, not a ride left open on its stack
    @Test
    fun tappingATabShowsItsPageNotTheRideOpenedFromIt() {
        content {
            Shell(
                dashboard = { nav -> OpenButton("dashboard", nav::openRide) },
                activities = { nav -> OpenButton("activities", nav::openRide) },
                profile = { Text("profile") },
                ride = { nav -> Button(onClick = nav::back, modifier = Modifier.testTag("ride-screen")) { Text("ride") } },
            )
        }

        // The selected tab again
        compose.onNodeWithTag("open-dashboard").performClick()
        compose.onNodeWithTag("ride-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-dashboard").performClick()
        compose.onNodeWithTag("open-dashboard").assertIsDisplayed()

        // Another tab and back
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("open-activities").performClick()
        compose.onNodeWithTag("ride-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-dashboard").performClick()
        compose.onNodeWithTag("open-dashboard").assertIsDisplayed()
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("open-activities").assertIsDisplayed()
    }

    @Composable
    private fun OpenButton(name: String, open: (Long) -> Unit) {
        Button(onClick = { open(85) }, modifier = Modifier.testTag("open-$name")) { Text(name) }
    }

    // Spec 007 AC3: a running sync shows above the list
    @Test
    fun aRunningSyncShowsAboveTheList() {
        var sync by mutableStateOf(SyncState(running = true, processed = 1, total = 3))
        showLog(sync = { sync }) { log }
        compose.onNodeWithTag("sync-progress").assertIsDisplayed()
        compose.onNodeWithText("Syncing with intervals.icu… 1 of 3").assertIsDisplayed()

        sync = SyncState()
        compose.onNodeWithTag("sync-progress").assertDoesNotExist()
    }
}
