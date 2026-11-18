package app.climbra.android.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.activities.PlannedWorkoutCard
import app.climbra.android.calendar.CalendarFixtures.COMPLETED
import app.climbra.android.calendar.CalendarFixtures.PLAN
import app.climbra.android.calendar.CalendarFixtures.PLANNED
import app.climbra.android.calendar.CalendarFixtures.REST
import app.climbra.android.calendar.CalendarFixtures.SENT
import app.climbra.android.calendar.CalendarFixtures.calendar
import app.climbra.android.calendar.CalendarFixtures.garmin
import app.climbra.android.data.api.CalendarResponse
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.GarminConnection
import app.climbra.android.data.api.GarminSendResult
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.api.PlannedWorkoutLink
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.Network
import app.climbra.android.shell.Shell
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 005 screens rendered with Robolectric against plain state; texts are English (the default resources). */
@RunWith(AndroidJUnit4::class)
class CalendarScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val fmt = Fmt(Locale.forLanguageTag("en"))
    private val monday = LocalDate.of(2026, 11, 9)

    private fun content(body: @Composable () -> Unit) = compose.setContent { ClimbraTheme(darkTheme = false) { body() } }

    private fun inside(tag: String, text: String) = compose.onNode(
        hasText(text, substring = true) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
        useUnmergedTree = true,
    )

    private fun data(json: String = calendar()) = Network.json.decodeFromString(CalendarResponse.serializer(), json)

    private fun connection(json: String = garmin()) = Network.json.decodeFromString(GarminConnection.serializer(), json)

    private fun workout(json: String) = Network.json.decodeFromString(CalendarWorkout.serializer(), json)

    private fun week(
        data: CalendarResponse? = data(),
        garmin: GarminConnection? = null,
        loading: Boolean = false,
    ) = CalendarUiState(week = monday, today = LocalDate.of(2026, 11, 12), loading = loading, data = data, garmin = garmin)

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("calendar-week").performScrollToNode(hasTestTag(tag))
    }

    // AC1, AC3, AC4
    @Test
    fun theWeekShowsItsDaysWorkoutsRidesAndTheBackendsTotals() {
        val opened = mutableListOf<String>()
        content {
            CalendarScreen(
                week(),
                fmt,
                CalendarActions(onOpenWorkout = { opened += "workout ${it.id}" }, onOpenRide = { opened += "ride $it" }),
            )
        }

        compose.onNodeWithTag("calendar-period").assertIsDisplayed()
        compose.onNodeWithText("Nov 9 – Nov 15", substring = true).assertExists()
        scrollTo("week-summary")
        inside("week-summary", "Week 2").assertExists()
        inside("week-summary", "Build").assertExists()
        inside("week-tss", "103").assertExists()
        inside("week-tss", "/ 215").assertExists()
        inside("week-time", "1:28").assertExists()
        inside("week-time", "/ 4:00").assertExists()

        scrollTo("day-2026-11-09")
        inside("day-2026-11-09", "–").assertExists()
        scrollTo("day-2026-11-10")
        inside("workout-1", "5×4 min VO2max").assertExists()
        inside("workout-1", "1:00 · 75 TSS").assertExists()
        inside("workout-status-1", "Completed").assertExists()
        inside("ride-11", "Morning VO2").assertExists()
        compose.onNodeWithTag("workout-1").performClick()
        compose.onNodeWithTag("ride-11").performClick()
        scrollTo("day-2026-11-11")
        inside("ride-12", "Road").assertExists()
        inside("ride-12", "40 TSS").assertExists()
        compose.onNodeWithTag("ride-hr-12", useUnmergedTree = true).assertExists()
        scrollTo("day-2026-11-12")
        inside("workout-3", "Rest day").assertExists()
        compose.onNodeWithTag("workout-status-3", useUnmergedTree = true).assertDoesNotExist()

        assertEquals(listOf("workout 1", "ride 11"), opened)
    }

    // AC2
    @Test
    fun theHeaderStepsThroughWeeksAndASwipeShowsTheNext() {
        val calls = mutableListOf<String>()
        content {
            CalendarScreen(
                week(),
                fmt,
                CalendarActions(
                    onPrevious = { calls += "previous" },
                    onNext = { calls += "next" },
                    onToday = { calls += "today" },
                    onShowWeek = { calls += "week $it" },
                ),
            )
        }

        compose.onNodeWithTag("calendar-previous").performClick()
        compose.onNodeWithTag("calendar-next").performClick()
        compose.onNodeWithTag("calendar-today").performClick()
        compose.onNodeWithTag("calendar-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(listOf("previous", "next", "today", "week 2026-11-16"), calls)
    }

    // AC2: the period opens the date picker
    @Test
    fun theDatePickerShowsTheWeekOfThePickedDay() {
        var shown: LocalDate? = null
        content { CalendarScreen(week(), fmt, CalendarActions(onShowWeek = { shown = it })) }

        compose.onNodeWithTag("calendar-period").performClick()
        compose.onNodeWithTag("week-pick-ok").performClick()

        assertEquals(monday, shown)
    }

    // AC5
    @Test
    fun aPlanCardShowsThePlanAndItsRemovalAsksFirst() {
        var state by mutableStateOf(week())
        val calls = mutableListOf<String>()
        content {
            CalendarScreen(
                state,
                fmt,
                CalendarActions(onAskRemove = { calls += "ask ${it.id}" }, onConfirmRemove = { calls += "confirm" }),
            )
        }

        inside("plan-7", "FTP build").assertExists()
        inside("plan-7", "Pyramidal").assertExists()
        inside("plan-7", "Goal: Raise FTP").assertExists()
        inside("plan-7", "Nov 2, 2026 – Nov 29, 2026").assertExists()
        compose.onNodeWithTag("remove-plan-7").performScrollTo().performClick()
        state = state.copy(removing = state.plans.single())
        compose.onNodeWithText("The plan “FTP build” is removed", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("confirm-remove").performClick()

        assertEquals(listOf("ask 7", "confirm"), calls)
    }

    // AC6
    @Test
    // Spec 008 AC2, AC7, AC10: the suggestion asks the coach, the proposal opens in the app
    fun theSuggestionListsItsReasonsAndCanBeDismissedTheProposalOpensInTheApp() {
        val calls = mutableListOf<String>()
        content {
            CalendarScreen(
                week(),
                fmt,
                CalendarActions(
                    onDismissSuggestion = { calls += "dismiss ${it.id}" },
                    onOpenWeb = { calls += "web $it" },
                    onAdjust = { plan, suggestion -> calls += "adjust ${plan.id} $suggestion" },
                    onOpenProposal = { calls += "proposal $it" },
                ),
            )
        }

        inside("suggestion-7", "The plan needs an adjustment").assertExists()
        inside("suggestion-7", "Tue, Nov 10 – 5×4 min VO2max: 4 of 5 intervals").assertExists()
        inside("suggestion-7", "Week 1: 220 of 300 TSS").assertExists()
        inside("proposal-7", "The coach proposes changes to the plan").assertExists()
        inside("proposal-7", "from Nov 14, 2026").assertExists()
        compose.onNodeWithTag("dismiss-7").performScrollTo().performClick()
        compose.onNodeWithTag("ask-coach-7").performScrollTo().performClick()
        compose.onNodeWithTag("show-proposal-7").performScrollTo().performClick()
        compose.onNodeWithText("climbra.app", substring = true).assertDoesNotExist()

        assertEquals(listOf("dismiss 7", "adjust 7 true", "proposal 21"), calls)
    }

    // Spec 008 AC1: "Adjust plan" on the plan card, hidden while the coach adjusts the plan
    @Test
    fun thePlanCardOffersAnAdjustmentUnlessOneRuns() {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(week())
        content {
            CalendarScreen(state, fmt, CalendarActions(onAdjust = { plan, suggestion -> calls += "adjust ${plan.id} $suggestion" }))
        }

        compose.onNodeWithTag("adjust-plan-7").performScrollTo().performClick()
        assertEquals(listOf("adjust 7 false"), calls)

        state = week(data(calendar(plans = PLAN.replace("\"adjustmentRunning\":false", "\"adjustmentRunning\":true"))))
        compose.onNodeWithTag("adjust-plan-7").assertDoesNotExist()
        compose.onNodeWithTag("adjusting-7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ask-coach-7").performScrollTo().assertIsNotEnabled()
    }

    // AC6; spec 006 AC18: the note offers the coach's training block form
    @Test
    fun withoutAPlanTheNoteShowsAndTheRidesStay() {
        var created = 0
        content { CalendarScreen(week(data(calendar(plans = "", workouts = ""))), fmt, CalendarActions(onNewTrainingBlock = { created++ })) }

        inside("no-plan", "No training plan in this period").assertExists()
        inside("no-plan", "Create a training block").performClick()
        scrollTo("ride-12")
        compose.onNodeWithTag("ride-12").assertExists()
        assertEquals(1, created)
    }

    // AC9
    @Test
    fun garminOffersTheImportAndTheOutdatedNoticeOnlyWhenAvailable() {
        var state by mutableStateOf(week(garmin = connection(garmin(stale = 1))))
        var imports = 0
        content { CalendarScreen(state, fmt, CalendarActions(onImportGarmin = { imports++ })) }

        compose.onNodeWithTag("garmin-outdated").assertExists()
        compose.onNodeWithTag("garmin-import").performClick()
        assertEquals(1, imports)

        state = state.copy(connectingGarmin = GarminStatus.NO_PERMISSION)
        inside("garmin-connect-dialog", "Workout import is switched off").assertExists()
        compose.onNodeWithTag("garmin-connect-web").assertExists()

        state = week(garmin = connection(garmin(available = false, stale = 1)))
        compose.onNodeWithTag("garmin-import").assertDoesNotExist()
        compose.onNodeWithTag("garmin-outdated").assertDoesNotExist()
    }

    // AC9: the result of a send, line by line
    @Test
    fun aGarminResultShowsItsSummaryFailuresAndReason() {
        val result = Network.json.decodeFromString(GarminSendResult.serializer(), SENT)
        content {
            CalendarScreen(
                week().copy(message = CalendarMessage.GarminPlanSent(result, "2026-11-12", "2026-11-25")),
                fmt,
                CalendarActions(),
            )
        }

        compose.onNodeWithText("Garmin Connect, Nov 12 – Nov 25: sent 3, unchanged 1, removed 2", substring = true).assertExists()
        compose.onNodeWithText("Not sent: Long endurance", substring = true).assertExists()
        compose.onNodeWithText("Garmin's limit of requests is reached", substring = true).assertExists()
    }

    // AC11
    @Test
    fun loadingAndAFailureWithRetry() {
        var state by mutableStateOf(week(data = null, loading = true))
        var retries = 0
        content { CalendarScreen(state, fmt, CalendarActions(onRetry = { retries++ })) }

        compose.onNodeWithTag("calendar-loading").assertExists()
        state = week(data = null).copy(error = ApiError.Network)
        inside("calendar-error", "Cannot load the calendar.").assertExists()
        compose.onNodeWithTag("calendar-retry").performClick()
        assertEquals(1, retries)
    }

    // Workout detail

    private fun detail(json: String = COMPLETED, status: String? = "SENT") = WorkoutDetailUiState(
        loading = false,
        workout = workout(json),
        rides = data().activities,
        garmin = connection(),
        garminStatus = status,
    )

    // AC7
    @Test
    fun theWorkoutDetailShowsItsFiguresAndSteps() {
        content { WorkoutDetailScreen(detail(), fmt, WorkoutActions()) }

        inside("workout-figures", "1:00").assertExists()
        inside("workout-figures", "75").assertExists()
        inside("workout-figures", "0.86").assertExists()
        compose.onNodeWithText("Indoor").assertExists()
        compose.onNodeWithText("Tuesday, November 10, 2026", substring = true).assertExists()
        inside("workout-description", "Hard but even.").assertExists()
        compose.onNodeWithTag("workout-chart").assertExists()
        inside("repeat-1", "5 ×").assertExists()
        inside("step-1-0", "Work").assertExists()
        inside("step-1-0", "Seated").assertExists()
        inside("step-1-0", "110–120").assertExists()
        inside("step-watts-1-0", "286–312 W").assertExists()
        inside("step-1-0", "95–105").assertExists()
        compose.onNodeWithTag("workout-no-ftp").assertDoesNotExist()
    }

    // AC7: without an FTP the watts are a dash and a note says why
    @Test
    fun withoutAnFtpTheWattsAreADash() {
        val noWatts = COMPLETED.replace(Regex(""""power(Low|High)Watts":\d+,"""), "")
        content { WorkoutDetailScreen(detail(noWatts), fmt, WorkoutActions()) }

        inside("step-watts-1-0", "–").assertExists()
        compose.onNodeWithTag("workout-no-ftp").assertExists()
    }

    // AC8
    @Test
    fun aCompletedWorkoutShowsItsRideAndComparisonAndCanBeUnlinked() {
        val calls = mutableListOf<String>()
        content {
            WorkoutDetailScreen(
                detail().copy(actionError = ApiError.Http(409, "WORKOUT_COMPLETED")),
                fmt,
                WorkoutActions(onUnlink = { calls += "unlink" }, onOpenRide = { calls += "ride $it" }, onLink = { calls += "link $it" }),
            )
        }

        inside("workout-status", "Completed").assertExists()
        inside("linked-ride", "Morning VO2").assertExists()
        compose.onNodeWithText("matched automatically").assertExists()
        inside("workout-comparison", "83%").assertExists()
        compose.onNodeWithTag("workout-skip").assertDoesNotExist()
        compose.onNodeWithTag("linked-ride").performScrollTo().performClick()
        compose.onNodeWithTag("workout-unlink").performScrollTo().performClick()
        compose.onNodeWithTag("link-ride").performScrollTo().performClick()
        compose.onNodeWithTag("link-option-12").performClick()
        compose.onNodeWithTag("link-submit").performClick()
        inside("workout-action-error", "The workout has a linked ride").assertExists()

        assertEquals(listOf("ride 11", "unlink", "link 12"), calls)
    }

    // AC8
    @Test
    fun aPlannedWorkoutCanBeSkippedAndASkippedOneUndone() {
        var state by mutableStateOf(detail(PLANNED, status = null))
        val skips = mutableListOf<Boolean>()
        content { WorkoutDetailScreen(state, fmt, WorkoutActions(onSkip = { skips += it })) }

        compose.onNodeWithTag("workout-skip").performScrollTo().performClick()
        state = detail(PLANNED.replace(""""status":"PLANNED"""", """"status":"SKIPPED""""), status = null)
        compose.onNodeWithTag("workout-unskip").performScrollTo().performClick()

        assertEquals(listOf(true, false), skips)
    }

    // AC7: a rest day has nothing to do
    @Test
    fun aRestDayHasNoActions() {
        content { WorkoutDetailScreen(detail(REST, status = null), fmt, WorkoutActions()) }

        compose.onNodeWithText("Rest day").assertExists()
        compose.onNodeWithTag("workout-skip").assertDoesNotExist()
        compose.onNodeWithTag("zwift-download").assertDoesNotExist()
        compose.onNodeWithTag("workout-steps").assertDoesNotExist()
    }

    // AC9, AC10
    @Test
    fun garminSendAndTheZwiftDownloadShowWhereTheyApply() {
        var state by mutableStateOf(detail())
        val calls = mutableListOf<String>()
        content {
            WorkoutDetailScreen(state, fmt, WorkoutActions(onSendToGarmin = { calls += "garmin" }, onDownloadZwift = { calls += "zwift" }))
        }

        inside("garmin-status", "Sent to Garmin Connect").assertExists()
        compose.onNodeWithTag("garmin-send").performScrollTo().performClick()
        compose.onNodeWithTag("zwift-download").performScrollTo().performClick()
        compose.onNodeWithText("Documents\\Zwift\\Workouts", substring = true).assertExists()
        assertEquals(listOf("garmin", "zwift"), calls)

        // Not sendable, no steps
        state = detail(PLANNED, status = null)
        compose.onNodeWithTag("garmin-send").assertDoesNotExist()
        compose.onNodeWithTag("zwift-download").assertDoesNotExist()

        state = detail(status = "NOT_SENT").copy(connectingGarmin = GarminStatus.NOT_CONNECTED)
        inside("garmin-send", "Send to Garmin Connect").assertExists()
        compose.onNodeWithTag("garmin-status").assertDoesNotExist()
        inside("garmin-connect-dialog", "Connect Garmin Connect on climbra.app first").assertExists()
    }

    // AC12
    @Test
    fun thePlannedWorkoutOfARideOpensTheCalendar() {
        var opened: String? = null
        content {
            PlannedWorkoutCard(
                PlannedWorkoutLink(id = 1, date = "2026-11-10", title = "5×4 min VO2max", type = "VO2MAX"),
                emptyList(),
                linking = false,
                linkError = null,
                fmt = fmt,
                onLink = {},
                onOpenCalendar = { opened = it },
            )
        }

        compose.onNodeWithTag("open-calendar").performClick()

        assertEquals("2026-11-10", opened)
    }

    // AC4, AC12: the calendar is a tab; its workouts and rides open on its stack, a ride elsewhere switches to it
    @Test
    fun theCalendarTabKeepsItsWorkoutsAndRidesOnItsStack() {
        content {
            Shell(
                dashboard = { Text("dashboard") },
                activities = { nav -> Button(onClick = { nav.openRide(85) }, modifier = Modifier.testTag("open-activities")) { Text("activities") } },
                profile = { Text("profile") },
                calendar = { nav ->
                    Column {
                        Button(onClick = { nav.openWorkout(1, "2026-11-10") }, modifier = Modifier.testTag("open-workout")) { Text("calendar") }
                        Button(onClick = { nav.openRide(11) }, modifier = Modifier.testTag("open-calendar-ride")) { Text("ride") }
                    }
                },
                workout = { nav -> Button(onClick = nav::back, modifier = Modifier.testTag("workout-screen")) { Text("workout") } },
                ride = { nav -> Button(onClick = nav::openCalendar, modifier = Modifier.testTag("ride-screen")) { Text("ride") } },
            )
        }

        compose.onNodeWithText("Calendar").assertIsDisplayed()
        compose.onNodeWithTag("nav-calendar").performClick()
        compose.onNodeWithTag("open-workout").performClick()
        compose.onNodeWithTag("workout-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-calendar").assertIsSelected()
        compose.onNodeWithTag("workout-screen").performClick()
        compose.onNodeWithTag("open-calendar-ride").performClick()
        compose.onNodeWithTag("ride-screen").assertIsDisplayed()

        // A ride of the training log shows its workout's week on the calendar tab
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("open-activities").performClick()
        compose.onNodeWithTag("ride-screen").performClick()
        compose.onNodeWithTag("nav-calendar").assertIsSelected()
        compose.onNodeWithTag("open-workout").assertIsDisplayed()
    }
}
