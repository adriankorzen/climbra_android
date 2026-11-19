package app.climbra.android.calendar

import androidx.lifecycle.SavedStateHandle
import app.climbra.android.activities.FileSaver
import app.climbra.android.calendar.CalendarFixtures.COMPLETED
import app.climbra.android.calendar.CalendarFixtures.PLANNED
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
import app.climbra.android.testing.TestBackend.Companion.problem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 005 AC7 - AC10: the workout detail's requests and actions against the backend. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val changes = DataChanges()
    private val saved = mutableListOf<Triple<String, String, ByteArray>>()
    private val files = FileSaver { name, mime, content -> saved += Triple(name, mime, content.readBytes()) }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val garminConnect by lazy { backend.garminConnect(backend.session(scope), scope) }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        backend.close()
    }

    private fun viewModel(id: Long = 1, date: String = "2026-11-10") = WorkoutDetailViewModel(
        SavedStateHandle(mapOf("id" to id, "date" to date)),
        RiderRepository(backend.riderApi) { "Europe/Warsaw" },
        backend.calendarApi,
        changes,
        files,
        garminConnect,
    )

    private suspend fun WorkoutDetailViewModel.settled() =
        state.first { !it.loading && !it.busy && !it.sending && !it.downloading }

    private fun standard(garmin: String = garmin(), statuses: String = """{"workouts":[{"workoutId":1,"status":"SENT"}]}""") {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on("GET /api/riders/5/calendar", json(calendar(from = "2026-11-07", to = "2026-11-13")))
        backend.on("GET /api/riders/5/garmin-connection", json(garmin))
        backend.on("GET /api/riders/5/garmin-connection/workouts", json(statuses))
    }

    // AC7, AC8
    @Test
    fun loadsTheWorkoutAndTheRidesOfThreeDaysAroundIt() = runTest(dispatcher) {
        standard()

        val state = viewModel().settled()

        val request = backend.requests("GET /api/riders/5/calendar").single().url
        assertEquals("2026-11-07", request.queryParameter("from"))
        assertEquals("2026-11-13", request.queryParameter("to"))
        val workout = state.workout!!
        assertEquals("5×4 min VO2max", workout.title)
        assertEquals(5, workout.blocks[1].repetitions)
        assertEquals(312, workout.blocks[1].steps[0].powerHighWatts)
        assertEquals(5, workout.blocks[1].steps[0].zone)
        assertFalse(state.noFtp)
        assertEquals(11L, state.linkedRide?.activityId)
        // The linked ride is no candidate
        assertEquals(listOf(12L), state.candidates.map { it.activityId })
        assertEquals("SENT", state.garminStatus)
        val statuses = backend.requests("GET /api/riders/5/garmin-connection/workouts").single().url
        assertEquals("2026-11-10" to "2026-11-10", statuses.queryParameter("from") to statuses.queryParameter("to"))
    }

    // AC7: watts only with an FTP
    @Test
    fun stepsWithoutWattsMeanNoFtp() = runTest(dispatcher) {
        standard()
        val noWatts = COMPLETED.replace(Regex(""""power(Low|High)Watts":\d+,"""), "")
        backend.replace("GET /api/riders/5/calendar", json(calendar(workouts = noWatts)))

        val state = viewModel().settled()

        assertTrue(state.noFtp)
        assertNull(state.workout!!.blocks[0].steps[0].powerLowWatts)
    }

    @Test
    fun aWorkoutNoLongerInTheCalendarIsNotFound() = runTest(dispatcher) {
        standard()

        val state = viewModel(id = 99).settled()

        assertTrue(state.notFound)
        assertNull(state.workout)
    }

    // AC8
    @Test
    fun unlinkingAnswersTheWorkoutReloadsTheRidesAndMarksDataChanged() = runTest(dispatcher) {
        standard()
        val unlinked = COMPLETED.replace(""""status":"COMPLETED","activityId":11,"matchSource":"AUTO"""", """"status":"MISSED","activityId":null,"matchSource":null""")
        backend.on("DELETE /api/riders/5/planned-workouts/1/activity", json(unlinked))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.unlink()
        val state = viewModel.settled()

        assertEquals(1, backend.requests("DELETE /api/riders/5/planned-workouts/1/activity").size)
        assertEquals(1, changes.version.value)
        assertEquals(2, backend.requests("GET /api/riders/5/calendar").size)
        assertEquals(2, state.rides.size)
    }

    // AC8
    @Test
    fun linkingAndSkippingSendTheirRequests() = runTest(dispatcher) {
        standard()
        backend.replace("GET /api/riders/5/calendar", json(calendar(workouts = PLANNED, from = "2026-11-11", to = "2026-11-17")))
        backend.on("PUT /api/riders/5/planned-workouts/2/activity", json(PLANNED.replace(""""status":"PLANNED","activityId":null""", """"status":"COMPLETED","activityId":12""")))
        backend.on("PUT /api/riders/5/planned-workouts/2/skipped", json(PLANNED.replace(""""status":"PLANNED"""", """"status":"SKIPPED"""")))
        val viewModel = viewModel(id = 2, date = "2026-11-14")
        viewModel.settled()

        viewModel.link(12)
        viewModel.settled()
        viewModel.skip(true)
        viewModel.settled()

        assertEquals("""{"activityId":12}""", backend.requests("PUT /api/riders/5/planned-workouts/2/activity").single().body?.utf8())
        assertEquals("""{"skipped":true}""", backend.requests("PUT /api/riders/5/planned-workouts/2/skipped").single().body?.utf8())
        assertEquals(2, changes.version.value)
    }

    // AC8
    @Test
    fun aFailedActionShowsItsErrorInTheDetail() = runTest(dispatcher) {
        standard()
        backend.on("PUT /api/riders/5/planned-workouts/1/skipped", problem(409, "WORKOUT_COMPLETED"))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.skip(true)
        val state = viewModel.settled()

        assertEquals(ApiError.Http(409, "WORKOUT_COMPLETED"), state.actionError)
        assertEquals("COMPLETED", state.workout?.status)
        assertEquals(0, changes.version.value)
    }

    // AC9
    @Test
    fun sendingToGarminReportsTheResultAndTheNewStatus() = runTest(dispatcher) {
        standard(statuses = """{"workouts":[{"workoutId":1,"status":"OUTDATED"}]}""")
        backend.on("POST /api/riders/5/planned-workouts/1/garmin", json(SENT))
        val viewModel = viewModel()
        assertEquals("OUTDATED", viewModel.settled().garminStatus)
        backend.replace("GET /api/riders/5/garmin-connection/workouts", json("""{"workouts":[{"workoutId":1,"status":"SENT"}]}"""))

        viewModel.sendToGarmin()
        val state = viewModel.settled()

        assertEquals(3, (state.message as CalendarMessage.GarminWorkoutSent).result.sent)
        assertEquals("SENT", state.garminStatus)
    }

    // AC9
    @Test
    fun withoutAConnectionSendingExplainsWhereToConnect() = runTest(dispatcher) {
        standard(garmin = garmin(status = "NOT_CONNECTED"), statuses = """{"workouts":[{"workoutId":1,"status":"NOT_SENT"}]}""")
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.sendToGarmin()

        assertEquals(GarminStatus.NOT_CONNECTED, viewModel.state.value.connectingGarmin)
        assertTrue(backend.requests("POST /api/riders/5/planned-workouts/1/garmin").isEmpty())

        // Spec 016 AC1: Connect opens the app's consent dialog
        viewModel.connectGarmin()
        assertNull(viewModel.state.value.connectingGarmin)
        assertEquals(app.climbra.android.garmin.GarminConsent(5, GarminStatus.NOT_CONNECTED), garminConnect.consent.value)
    }

    // AC9
    @Test
    fun withoutGarminOnTheServerNoStatusIsAskedFor() = runTest(dispatcher) {
        standard(garmin = garmin(available = false))

        val state = viewModel().settled()

        assertNull(state.garminStatus)
        assertTrue(backend.requests("GET /api/riders/5/garmin-connection/workouts").isEmpty())
    }

    // AC10
    @Test
    fun theZwiftFileIsSavedToDownloadsUnderTheServersName() = runTest(dispatcher) {
        standard()
        backend.on(
            "GET /api/riders/5/planned-workouts/1/zwo",
            MockResponse.Builder()
                .headers(headersOf("Content-Type", "application/xml", "Content-Disposition", "attachment; filename=\"vo2max.zwo\""))
                .body("<workout_file/>")
                .build(),
        )
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.downloadZwift()
        val state = viewModel.settled()

        assertEquals(CalendarMessage.Saved("vo2max.zwo"), state.message)
        assertEquals("vo2max.zwo", saved.single().first)
        assertEquals("<workout_file/>", saved.single().third.decodeToString())
    }

    // AC10
    @Test
    fun aFailedZwiftDownloadShowsTheError() = runTest(dispatcher) {
        standard()
        backend.on("GET /api/riders/5/planned-workouts/1/zwo", problem(404, null))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.downloadZwift()

        assertEquals(CalendarMessage.Failed(ApiError.Http(404, null)), viewModel.settled().message)
        assertTrue(saved.isEmpty())
    }
}
