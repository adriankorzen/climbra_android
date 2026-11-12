package app.climbra.android.dashboard

import app.climbra.android.data.api.FormRange
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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

/** Spec 002: what the dashboard requests and keeps; the figures themselves are the backend's (cyclingapp spec 051). */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = MovableClock()

    private class MovableClock(var now: Instant = Instant.parse("2026-11-11T10:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private val changes = DataChanges()

    private fun viewModel(deviceZone: String = "Europe/Warsaw") =
        DashboardViewModel(RiderRepository(backend.riderApi) { deviceZone }, backend.riderApi, clock, changes)

    private suspend fun DashboardViewModel.settled() = state.first { !it.loading && !it.refreshing }

    private fun rider(timeZone: String = "Europe/Warsaw") = """
        [{"id":5,"name":"Anna","weightKg":72.0,"currentThresholds":{"id":1,"effectiveFrom":"2026-01-01","ftpWatts":260},
          "ftpWattsPerKg":3.61,"timeZone":"$timeZone","createdAt":"2026-01-01T10:00:00Z"}]
    """.trimIndent()

    /** 365 days ending 2026-11-11 with a recognisable CTL per day. */
    private fun trainingLoad(form: String = "NEUTRAL") = buildString {
        append("""{"today":"2026-11-11","days":[""")
        append((0 until 365).joinToString(",") { """{"date":"2026-01-01","tss":$it.0,"ctl":$it.5,"atl":1.0,"tsb":-1.0}""" })
        append("""],"weeks":[{"weekStart":"2026-11-02","tss":310.5,"seconds":28800,"rides":4},""")
        append("""{"weekStart":"2026-11-09","tss":160.0,"seconds":9000,"rides":2}],""")
        append(""""last7Days":{"tss":332.8,"seconds":18900,"meters":151200.0,"rides":4},""")
        append(""""previous7Days":{"tss":350.0,"seconds":20000,"meters":160000.0,"rides":4},""")
        append(""""changes":{"tss":-0.049,"seconds":-0.055,"meters":-0.055,"rides":0.0},""")
        append(""""fitness":{"ctl":36.2,"atl":46.9,"tsb":-7.4,"ctlChange":0.06,"atlChange":null,"form":"$form"},""")
        append(""""addedLater":true}""")
    }

    private fun ride(id: Int, start: String, source: String = "POWER") = """
        {"activityId":$id,"name":"Ride $id","sportType":"road_biking","startTime":"${start}T06:00:00Z",
         "startTimeLocal":"${start}T08:00:00","durationSeconds":3600,"normalizedPower":217.4,"intensityFactor":0.835,
         "trainingLoad":70.2,"trainingLoadSource":"$source","intervalCount":3}
    """.trimIndent()

    private fun withRides() {
        backend.on("GET /api/riders", json(rider()))
        backend.on("GET /api/riders/5/training-load", json(trainingLoad()))
        backend.on(
            "GET /api/riders/5/analyses",
            // The backend returns the latest rides oldest first
            json("[${ride(1, "2026-11-06")},${ride(2, "2026-11-09", "HEART_RATE")},${ride(3, "2026-11-11")}]"),
        )
    }

    // AC1
    @Test
    fun loadsTheBackendsFiguresWithOneRequestEach() = runTest(dispatcher) {
        withRides()

        val state = viewModel().settled()

        val load = backend.requests("GET /api/riders/5/training-load").single().url
        assertEquals("365", load.queryParameter("days"))
        assertEquals("12", load.queryParameter("weeks"))
        val analyses = backend.requests("GET /api/riders/5/analyses").single().url
        assertEquals(setOf("limit"), analyses.queryParameterNames)
        assertEquals("7", analyses.queryParameter("limit"))
        // Nothing else: no history of rides, no list of all rides
        assertEquals(3, backend.requests.size)

        assertFalse(state.failed)
        assertEquals("2026-11-11", state.load!!.today)
        assertEquals(332.8, state.load!!.last7Days.tss, 0.0)
        assertEquals(-0.049, state.load!!.changes.tss!!, 0.0)
        assertEquals(36.2, state.load!!.fitness.ctl, 0.0)
        assertNull(state.load!!.fitness.atlChange)
        assertEquals(FormRange.NEUTRAL, state.load!!.fitness.form)
        assertEquals(260, state.rider!!.currentThresholds!!.ftpWatts)
        assertEquals(3.61, state.rider!!.ftpWattsPerKg!!, 0.0)
    }

    // AC2: a range the app does not know yet does not break the dashboard
    @Test
    fun readsEveryFormRangeAndFallsBackForAnUnknownOne() {
        for (form in FormRange.entries) {
            TestBackend().use { other ->
                other.on("GET /api/riders", json(rider()))
                other.on("GET /api/riders/5/training-load", json(trainingLoad(form.name)))
                other.on("GET /api/riders/5/analyses", json("[${ride(1, "2026-11-06")}]"))
                runTest(dispatcher) {
                    val viewModel = DashboardViewModel(RiderRepository(other.riderApi) { "Europe/Warsaw" }, other.riderApi, clock, DataChanges())
                    assertEquals(form, viewModel.settled().load!!.fitness.form)
                }
            }
        }
        backend.on("GET /api/riders", json(rider()))
        backend.on("GET /api/riders/5/training-load", json(trainingLoad("SOMETHING_NEW")))
        backend.on("GET /api/riders/5/analyses", json("[${ride(1, "2026-11-06")}]"))
        runTest(dispatcher) {
            assertEquals(FormRange.NEUTRAL, viewModel().settled().load!!.fitness.form)
        }
    }

    // AC3
    @Test
    fun aRangeIsATailOfTheLoadedDaysWithoutANewRequest() = runTest(dispatcher) {
        withRides()
        val viewModel = viewModel()
        viewModel.settled()
        val requests = backend.requests.size

        assertEquals(90, viewModel.state.value.visibleDays.size)
        // The tail ends with the last day (CTL 364.5)
        assertEquals(364.5, viewModel.state.value.visibleDays.last().ctl, 0.0)
        for (days in listOf(42, 180, 365)) {
            viewModel.selectRange(days)
            assertEquals(days, viewModel.state.value.visibleDays.size)
            assertEquals(364.5, viewModel.state.value.visibleDays.last().ctl, 0.0)
        }
        viewModel.selectRange(7)
        assertEquals(365, viewModel.state.value.range)
        assertEquals(requests, backend.requests.size)
    }

    // AC5
    @Test
    fun recentRidesAreNewestFirst() = runTest(dispatcher) {
        withRides()

        val state = viewModel().settled()

        assertEquals(listOf(3L, 2L, 1L), state.rides.map { it.activityId })
        assertEquals(listOf(false, true, false), state.rides.map { it.loadFromHeartRate })
        assertFalse(state.noRidesAtAll)
    }

    // AC6
    @Test
    fun withoutRecentRidesTheListOfAllRidesTellsANewRiderFromAnOldHistory() {
        backend.on("GET /api/riders", json(rider()))
        backend.on("GET /api/riders/5/training-load", json(trainingLoad()))
        backend.on("GET /api/riders/5/analyses", json("[]"))
        backend.on("GET /api/riders/5/activities", json("[]"), json("""[{"id":9,"name":"Old ride"}]"""))
        runTest(dispatcher) {
            val viewModel = viewModel()
            assertTrue(viewModel.settled().noRidesAtAll)

            viewModel.refresh()
            val state = viewModel.settled()
            assertFalse(state.noRidesAtAll)
            assertTrue(state.rides.isEmpty())
            assertEquals(2, backend.requests("GET /api/riders/5/activities").size)
        }
    }

    // AC7
    @Test
    fun aFailedRequestIsReportedAndRetryLoadsAgain() {
        backend.on("GET /api/riders", json(rider()))
        backend.on("GET /api/riders/5/training-load", problem(503, null), json(trainingLoad()))
        backend.on("GET /api/riders/5/analyses", json("[${ride(1, "2026-11-06")}]"))
        runTest(dispatcher) {
            val viewModel = viewModel()
            val failed = viewModel.settled()
            assertTrue(failed.failed)
            assertNull(failed.load)
            // What did load is shown
            assertEquals(1, failed.rides.size)

            viewModel.refresh()
            val state = viewModel.settled()
            assertFalse(state.failed)
            assertEquals("2026-11-11", state.load!!.today)
        }
    }

    // AC7
    @Test
    fun aFailedRefreshKeepsWhatWasShown() {
        backend.on("GET /api/riders", json(rider()), problem(500, null))
        backend.on("GET /api/riders/5/training-load", json(trainingLoad()))
        backend.on("GET /api/riders/5/analyses", json("[${ride(1, "2026-11-06")}]"))
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.settled()

            viewModel.refresh()
            val state = viewModel.settled()

            assertTrue(state.failed)
            assertEquals("2026-11-11", state.load!!.today)
            assertEquals(1, state.rides.size)
        }
    }

    // Behaviour: figures older than 5 minutes are loaded again when the tab is shown
    @Test
    fun showingTheTabAgainReloadsOnlyStaleFigures() = runTest(dispatcher) {
        withRides()
        val viewModel = viewModel()
        viewModel.settled()

        clock.now += Duration.ofMinutes(4)
        viewModel.onShown()
        assertEquals(1, backend.requests("GET /api/riders/5/training-load").size)

        clock.now += Duration.ofMinutes(2)
        viewModel.onShown()
        viewModel.settled()
        assertEquals(2, backend.requests("GET /api/riders/5/training-load").size)
    }

    // Spec 004: a change elsewhere (a new rider or new thresholds in the profile) is shown when the tab is
    @Test
    fun showingTheTabAfterAChangeElsewhereReloads() = runTest(dispatcher) {
        withRides()
        val viewModel = viewModel()
        viewModel.settled()

        changes.changed()
        viewModel.onShown()
        viewModel.settled()
        assertEquals(2, backend.requests("GET /api/riders/5/training-load").size)

        viewModel.onShown()
        assertEquals(2, backend.requests("GET /api/riders/5/training-load").size)
    }

    // AC8
    @Test
    fun theRidersTimeZoneFollowsTheDevice() {
        backend.on("GET /api/riders", json(rider(timeZone = "Europe/Warsaw")))
        backend.on("PUT /api/riders/5/time-zone", noContent())
        backend.on("GET /api/riders/5/training-load", json(trainingLoad()))
        backend.on("GET /api/riders/5/analyses", json("[${ride(1, "2026-11-06")}]"))
        runTest(dispatcher) {
            viewModel(deviceZone = "Europe/Warsaw").settled()
            assertTrue(backend.requests("PUT /api/riders/5/time-zone").isEmpty())

            viewModel(deviceZone = "America/New_York").settled()
            val request = backend.requests("PUT /api/riders/5/time-zone").single()
            assertEquals("""{"timeZone":"America/New_York"}""", request.body!!.utf8())
            // Sent before the figures are asked for, so their "today" is already the device's
            val order = backend.requests.map { "${it.method} ${it.url.encodedPath}" }
            assertTrue(order.indexOf("PUT /api/riders/5/time-zone") < order.lastIndexOf("GET /api/riders/5/training-load"))
        }
    }

    // AC8: a failed time zone request does not stop the dashboard
    @Test
    fun aFailedTimeZoneRequestIsIgnored() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider(timeZone = "UTC")))
        backend.on("PUT /api/riders/5/time-zone", problem(500, null))
        backend.on("GET /api/riders/5/training-load", json(trainingLoad()))
        backend.on("GET /api/riders/5/analyses", json("[${ride(1, "2026-11-06")}]"))

        val state = viewModel().settled()

        assertFalse(state.failed)
        assertEquals("2026-11-11", state.load!!.today)
    }

    // AC9
    @Test
    fun anAccountWithoutRiderAsksForNothingElse() = runTest(dispatcher) {
        backend.on("GET /api/riders", json("[]"))

        val state = viewModel().settled()

        assertTrue(state.noRider)
        assertFalse(state.failed)
        assertEquals(1, backend.requests.size)
    }
}
