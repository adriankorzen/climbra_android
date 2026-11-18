package app.climbra.android.calendar

import androidx.lifecycle.SavedStateHandle
import app.climbra.android.calendar.CalendarFixtures.RIDERS
import app.climbra.android.calendar.CalendarFixtures.SENT
import app.climbra.android.calendar.CalendarFixtures.calendar
import app.climbra.android.calendar.CalendarFixtures.garmin
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.net.ApiError
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 005 AC1 - AC3, AC5, AC6, AC9, AC11, AC12: the calendar's requests and actions against the backend. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val changes = DataChanges()
    private val requests = CalendarRequests()
    private val saved = SavedStateHandle()

    /** Thursday noon in UTC: the same day in every zone the tests may run in. */
    private val clock = Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private fun viewModel() = CalendarViewModel(
        saved,
        RiderRepository(backend.riderApi) { "Europe/Warsaw" },
        backend.calendarApi,
        changes,
        requests,
        clock,
    )

    private suspend fun CalendarViewModel.settled() = state.first { !it.loading && !it.refreshing && !it.deleting && !it.sendingPlan }

    private fun standard(garmin: String = garmin()) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on("GET /api/riders/5/calendar", json(calendar()))
        backend.on("GET /api/riders/5/garmin-connection", json(garmin))
    }

    private fun calendarRanges() = backend.requests("GET /api/riders/5/calendar")
        .map { it.url.queryParameter("from") to it.url.queryParameter("to") }

    // AC1, AC3
    @Test
    fun showsTheWeekOfTodayWithTheDaysPlacedAndTheBackendsFigures() = runTest(dispatcher) {
        standard()

        val state = viewModel().settled()

        assertEquals(listOf("2026-11-09" to "2026-11-15"), calendarRanges())
        assertEquals(LocalDate.of(2026, 11, 9), state.week)
        val days = state.days
        assertEquals((9..15).map { LocalDate.of(2026, 11, it) }, days.map { it.date })
        // The linked ride sits under its workout, the unplanned one on its own day
        val tuesday = days[1]
        assertEquals(listOf(1L), tuesday.workouts.map { it.workout.id })
        assertEquals(11L, tuesday.workouts.single().ride?.activityId)
        assertTrue(tuesday.unplannedRides.isEmpty())
        assertEquals(listOf(12L), days[2].unplannedRides.map { it.activityId })
        assertTrue(days[0].empty)
        assertTrue(days[3].today)
        assertTrue(days[2].past)
        // Figures as the backend answered them
        assertEquals(215, state.figures?.plannedTss)
        assertEquals(103, state.figures?.riddenTss)
        assertEquals(2, state.figures?.planWeek?.weekNumber)
        assertEquals("FTP build", state.plans.single().name)
    }

    // AC2
    @Test
    fun previousNextTodayAndAPickedDayLoadTheirWeekAndTheWeekIsKept() = runTest(dispatcher) {
        standard()
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.next()
        viewModel.settled()
        viewModel.previous()
        viewModel.settled()
        viewModel.previous()
        viewModel.settled()
        viewModel.showWeek(LocalDate.of(2026, 12, 24))
        viewModel.settled()

        assertEquals(LocalDate.of(2026, 12, 21), viewModel.state.value.week)
        assertEquals("2026-12-21", saved.get<String>("week"))
        viewModel.showToday()
        viewModel.settled()
        assertEquals(
            listOf(
                "2026-11-09" to "2026-11-15", "2026-11-16" to "2026-11-22", "2026-11-09" to "2026-11-15",
                "2026-11-02" to "2026-11-08", "2026-12-21" to "2026-12-27", "2026-11-09" to "2026-11-15",
            ),
            calendarRanges(),
        )
        // A new view model (the tab shown again after the process died) keeps the week
        viewModel.next()
        viewModel.settled()
        assertEquals(LocalDate.of(2026, 11, 16), viewModel().settled().week)
    }

    // AC12
    @Test
    fun aWeekAskedForByAnotherScreenIsShown() = runTest(dispatcher) {
        standard()
        requests.show(LocalDate.of(2026, 10, 22))
        val viewModel = viewModel()

        assertEquals(LocalDate.of(2026, 10, 19), viewModel.settled().week)
        assertNull(requests.week.value)

        requests.show(LocalDate.of(2026, 11, 26))
        viewModel.state.first { it.week == LocalDate.of(2026, 11, 23) && !it.loading }
        assertEquals("2026-11-23" to "2026-11-29", calendarRanges().last())
        assertNull(requests.week.value)
    }

    // AC5
    @Test
    fun removingAPlanAsksFirstDeletesItAndLoadsAgain() = runTest(dispatcher) {
        standard()
        backend.on("DELETE /api/riders/5/plans/7", noContent())
        val viewModel = viewModel()
        val plan = viewModel.settled().plans.single()

        viewModel.askRemove(plan)
        assertEquals(plan, viewModel.state.value.removing)
        assertTrue(backend.requests("DELETE /api/riders/5/plans/7").isEmpty())
        viewModel.confirmRemove()
        val state = viewModel.settled()

        assertEquals(1, backend.requests("DELETE /api/riders/5/plans/7").size)
        assertNull(state.removing)
        assertEquals(CalendarMessage.PlanRemoved("FTP build"), state.message)
        assertEquals(1, changes.version.value)
        assertEquals(2, calendarRanges().size)
    }

    // AC5
    @Test
    fun aFailedRemovalShowsItsMessageAndKeepsThePlan() = runTest(dispatcher) {
        standard()
        backend.on("DELETE /api/riders/5/plans/7", problem(500, null))
        val viewModel = viewModel()
        viewModel.askRemove(viewModel.settled().plans.single())

        viewModel.confirmRemove()
        val state = viewModel.settled()

        assertEquals(CalendarMessage.Failed(ApiError.Http(500, null)), state.message)
        assertEquals("FTP build", state.removing?.name)
        assertEquals(0, changes.version.value)
    }

    // AC6
    @Test
    fun dismissingASuggestionLoadsTheWeekAgain() = runTest(dispatcher) {
        standard()
        backend.on("POST /api/riders/5/plans/7/suggestion/dismiss", noContent())
        val viewModel = viewModel()

        viewModel.dismissSuggestion(viewModel.settled().plans.single())
        viewModel.settled()

        assertEquals(1, backend.requests("POST /api/riders/5/plans/7/suggestion/dismiss").size)
        assertEquals(2, calendarRanges().size)
    }

    // AC9
    @Test
    fun importingThePlanToGarminReportsTheResultAndLoadsTheConnectionAgain() = runTest(dispatcher) {
        standard()
        backend.on("POST /api/riders/5/garmin-connection/send", json(SENT))
        val viewModel = viewModel()
        assertTrue(viewModel.settled().showGarminImport)

        viewModel.sendPlanToGarmin()
        val state = viewModel.settled()

        val message = state.message as CalendarMessage.GarminPlanSent
        assertEquals(3, message.result.sent)
        assertEquals("GARMIN_RATE_LIMITED", message.result.reason)
        assertEquals("2026-11-12" to "2026-11-25", message.from to message.to)
        assertEquals(2, backend.requests("GET /api/riders/5/garmin-connection").size)
    }

    // AC9
    @Test
    fun withoutAConnectionTheImportExplainsWhereToConnect() = runTest(dispatcher) {
        standard(garmin(status = "NEEDS_RECONNECT"))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.sendPlanToGarmin()

        assertEquals(GarminStatus.NEEDS_RECONNECT, viewModel.state.value.connectingGarmin)
        assertTrue(backend.requests("POST /api/riders/5/garmin-connection/send").isEmpty())
        viewModel.closeGarminDialog()
        assertNull(viewModel.state.value.connectingGarmin)
    }

    // AC9
    @Test
    fun withoutGarminOnTheServerNothingOfItShows() = runTest(dispatcher) {
        standard(garmin(available = false, stale = 2))

        val state = viewModel().settled()

        assertFalse(state.showGarminImport)
        assertFalse(state.garminStale)
    }

    // AC9: the out-of-date notice
    @Test
    fun outOfDateWorkoutsOnGarminShowTheNotice() = runTest(dispatcher) {
        standard(garmin(sendable = 0, stale = 1))

        val state = viewModel().settled()

        assertTrue(state.garminStale)
        assertTrue(state.showGarminImport)
    }

    // AC11
    @Test
    fun aFailureShowsTheErrorAndRetryLoadsAgain() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on("GET /api/riders/5/garmin-connection", json(garmin()))
        backend.on("GET /api/riders/5/calendar", problem(503, null), json(calendar()))
        val viewModel = viewModel()

        assertEquals(ApiError.Http(503, null), viewModel.settled().error)
        viewModel.retry()
        val state = viewModel.settled()

        assertNull(state.error)
        assertEquals(3, state.data?.plannedWorkouts?.size)
    }

    // AC11
    @Test
    fun aChangeElsewhereLoadsTheWeekAgainWhenShown() = runTest(dispatcher) {
        standard()
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.onShown()
        assertEquals(1, calendarRanges().size)
        changes.changed()
        viewModel.onShown()
        viewModel.settled()
        assertEquals(2, calendarRanges().size)
    }

    // AC11: pull to refresh keeps the week shown
    @Test
    fun refreshKeepsTheWeekVisibleWhileItLoads() = runTest(dispatcher) {
        standard()
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.refresh()
        val state = viewModel.settled()

        assertFalse(state.loading)
        assertEquals(2, calendarRanges().size)
        assertEquals(3, state.data?.plannedWorkouts?.size)
    }

    @Test
    fun anAccountWithoutARiderSeesTheHint() = runTest(dispatcher) {
        backend.on("GET /api/riders", json("[]"))

        val state = viewModel().settled()

        assertTrue(state.noRider)
        assertTrue(calendarRanges().isEmpty())
    }

    // ---- Spec 015: the month view

    // AC1: the width picks the month; it asks for the whole weeks covering it
    @Test
    fun aWideWindowOpensTheMonthWithItsWholeWeeks() = runTest(dispatcher) {
        standard()
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.defaultMode(CalendarMode.MONTH)
        val state = viewModel.settled()

        assertEquals(CalendarMode.MONTH, state.mode)
        assertEquals(java.time.YearMonth.of(2026, 11), state.month)
        assertEquals("2026-10-26" to "2026-12-06", calendarRanges().last())
        assertEquals(6, state.monthWeeks.size)
        assertEquals(LocalDate.of(2026, 10, 26), state.monthWeeks.first().first().date)
        assertEquals(LocalDate.of(2026, 12, 6), state.monthWeeks.last().last().date)
    }

    // AC1: the rider's choice holds against the width
    @Test
    fun theRidersChoiceHolds() = runTest(dispatcher) {
        standard()
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.setMode(CalendarMode.MONTH)
        viewModel.settled()
        viewModel.defaultMode(CalendarMode.WEEK)

        assertEquals(CalendarMode.MONTH, viewModel.state.value.mode)
        assertTrue(viewModel.state.value.modeChosen)
    }

    // AC4: arrows, Today and the picker change the month; back to the week keeps the date
    @Test
    fun theMonthIsSteppedAndTheWeekComesBack() = runTest(dispatcher) {
        standard()
        val viewModel = viewModel()
        viewModel.settled()
        viewModel.setMode(CalendarMode.MONTH)
        viewModel.settled()

        viewModel.next()
        assertEquals(java.time.YearMonth.of(2026, 12), viewModel.settled().month)
        viewModel.previous()
        viewModel.previous()
        assertEquals(java.time.YearMonth.of(2026, 10), viewModel.settled().month)
        viewModel.showWeek(LocalDate.of(2027, 3, 18))
        assertEquals(java.time.YearMonth.of(2027, 3), viewModel.settled().month)
        assertEquals("2027-03-01" to "2027-04-04", calendarRanges().last())

        // The week of the month's 1st (today is in another month)
        viewModel.setMode(CalendarMode.WEEK)
        assertEquals(LocalDate.of(2027, 3, 1), viewModel.settled().week)
        viewModel.setMode(CalendarMode.MONTH)
        viewModel.showToday()
        assertEquals(java.time.YearMonth.of(2026, 11), viewModel.settled().month)
        // Today's month: back to today's week
        viewModel.setMode(CalendarMode.WEEK)
        assertEquals(LocalDate.of(2026, 11, 9), viewModel.settled().week)
        assertEquals("2026-11-09" to "2026-11-15", calendarRanges().last())
    }

    // AC5: a day opens the week view at its week
    @Test
    fun aDayOpensItsWeek() = runTest(dispatcher) {
        standard()
        val viewModel = viewModel()
        viewModel.settled()
        viewModel.setMode(CalendarMode.MONTH)
        viewModel.settled()

        viewModel.openWeekOf(LocalDate.of(2026, 11, 26))

        val state = viewModel.settled()
        assertEquals(CalendarMode.WEEK, state.mode)
        assertEquals(LocalDate.of(2026, 11, 23), state.week)
        assertEquals("2026-11-23" to "2026-11-29", calendarRanges().last())
    }
}
