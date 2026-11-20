package app.climbra.android.coach

import androidx.lifecycle.SavedStateHandle
import app.climbra.android.coach.CoachFixtures.REVIEW
import app.climbra.android.coach.CoachFixtures.conversation
import app.climbra.android.coach.CoachFixtures.list
import app.climbra.android.coach.CoachFixtures.summary
import app.climbra.android.data.net.ApiError
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 006 AC13 - AC15: the training block form, the file review and a ride's review. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachFormsViewModelTest : CoachTestBase() {

    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)

    private fun blockForm() = TrainingBlockFormViewModel(riders, backend.coachApi, activity, quotas, session, clock).tracked()

    // AC14
    @Test
    fun theBlockFormStartsWithTheWebsDefaults() = runTest(dispatcher) {
        loggedIn(language = "pl")
        val state = blockForm().state.value

        assertEquals(LocalDate.of(2026, 11, 13), state.startDate)
        assertEquals(4, state.weeks)
        assertEquals(
            mapOf(DayOfWeek.TUESDAY to 60, DayOfWeek.THURSDAY to 60, DayOfWeek.SATURDAY to 180, DayOfWeek.SUNDAY to 120),
            state.days.filter { it.available }.associate { it.day to it.minutes },
        )
        assertEquals(DayOfWeek.SATURDAY, state.longRideDay)
        assertEquals("AUTO", state.methodology)
        assertEquals("pl", state.language)
    }

    // AC14
    @Test
    fun theBlockFormValidatesLikeTheWeb() = runTest(dispatcher) {
        val viewModel = blockForm()

        viewModel.update { it.copy(startDate = LocalDate.of(2026, 11, 11)) }
        viewModel.update { it.copy(days = it.days.map { day -> day.copy(available = false) }) }
        viewModel.start()

        val state = viewModel.state.value
        assertTrue(state.submitted)
        assertTrue(state.startInPast)
        assertTrue(state.goalMissing)
        assertTrue(state.noDay)
        assertEquals(0, count("POST /api/riders/5/coach/conversations/training-block"))
        // Minutes stay within 15 – 720
        viewModel.updateDay(DayOfWeek.MONDAY) { it.copy(minutes = 5) }
        assertEquals(15, viewModel.state.value.days.first().minutes)
        viewModel.updateDay(DayOfWeek.MONDAY) { it.copy(minutes = 900) }
        assertEquals(720, viewModel.state.value.days.first().minutes)
    }

    // AC14: the web's body; accepted, the conversation replaces the form
    @Test
    fun theBlockFormSendsTheWebsBodyAndOpensTheConversation() = runTest(dispatcher) {
        // No polling: while the test waits for real HTTP calls, virtual time runs and a poll would forget the request
        foreground.value = false
        withRider()
        backend.on("POST /api/riders/5/coach/conversations/training-block", json(conversation(id = 40, type = "TRAINING_BLOCK", busy = true), 202))
        val viewModel = blockForm()

        viewModel.update {
            it.copy(
                goal = "  Raise FTP  ",
                weeks = 6,
                eventDate = LocalDate.of(2027, 3, 1),
                // The long ride day is not available: not sent
                longRideDay = DayOfWeek.MONDAY,
                methodology = "SWEET_SPOT",
                notes = "   ",
                language = "en",
            )
        }
        viewModel.updateDay(DayOfWeek.SUNDAY) { it.copy(indoor = true) }
        viewModel.updateDay(DayOfWeek.WEDNESDAY) { it.copy(available = true, minutes = 90, indoor = true) }
        viewModel.updateDay(DayOfWeek.THURSDAY) { it.copy(indoor = true, available = false) }
        viewModel.start()

        assertEquals(40L, viewModel.state.first { it.accepted != null }.accepted)
        assertEquals(
            """{"startDate":"2026-11-13","weeks":6,"goal":"Raise FTP","goalEventDate":"2027-03-01",""" +
                """"availability":{"days":{"TUESDAY":60,"WEDNESDAY":90,"SATURDAY":180,"SUNDAY":120}},""" +
                """"indoorDays":["WEDNESDAY","SUNDAY"],"methodology":"SWEET_SPOT","language":"en"}""",
            body("POST /api/riders/5/coach/conversations/training-block"),
        )
        assertEquals(mapOf(40L to "Ride review"), activity.running.value)
    }

    // AC14: an error keeps the values
    @Test
    fun aRefusedBlockKeepsTheForm() = runTest(dispatcher) {
        withRider()
        backend.on("POST /api/riders/5/coach/conversations/training-block", problem(503, null))
        val viewModel = blockForm()
        viewModel.update { it.copy(goal = "Gran fondo") }

        viewModel.start()

        val state = viewModel.state.first { it.error != null }
        assertEquals(ApiError.Http(503, null), state.error)
        assertEquals("Gran fondo", state.goal)
        assertNull(state.accepted)
    }

    private val files = FakeFileSource(mapOf("content://ride.fit" to byteArrayOf(1, 2, 3)))

    private fun fileReview() = FileReviewViewModel(riders, backend.coachApi, activity, quotas, files, session).tracked()

    // AC15
    @Test
    fun aFileReviewNeedsARideFileAndSendsItWithRpeNoteAndLanguage() = runTest(dispatcher) {
        withRider()
        backend.on("POST /api/riders/5/coach/conversations/file-review", json(conversation(id = 41, type = "FILE_REVIEW", busy = true), 202))
        val viewModel = fileReview()
        assertFalse(viewModel.state.value.canStart)

        viewModel.choose(PickedFile("content://notes.txt", "notes.txt"))
        assertTrue(viewModel.state.value.fileError)
        assertFalse(viewModel.state.value.canStart)

        viewModel.choose(PickedFile("content://ride.fit", "ride.fit"))
        viewModel.update { it.copy(rpe = 8, note = " Hard ", language = "pl") }
        viewModel.start()

        assertEquals(41L, viewModel.state.first { it.accepted != null }.accepted)
        val request = backend.requests("POST /api/riders/5/coach/conversations/file-review").single()
        assertEquals("8", request.url.queryParameter("rpe"))
        assertEquals("Hard", request.url.queryParameter("note"))
        assertEquals("pl", request.url.queryParameter("language"))
        assertTrue(request.body!!.utf8().contains("filename=\"ride.fit\""))
    }

    // AC15: "The file can't be read"
    @Test
    fun anUnreadableFileIsTold() = runTest(dispatcher) {
        withRider()
        backend.on("POST /api/riders/5/coach/conversations/file-review", problem(422, null))
        val viewModel = fileReview()
        viewModel.choose(PickedFile("content://ride.fit", "ride.fit"))

        viewModel.start()

        val error = viewModel.state.first { it.error != null }.error!!
        assertEquals(app.climbra.android.R.string.coach_error_unprocessable, coachErrorHeadline(error))
    }

    private fun askCoach() = AskCoachViewModel(SavedStateHandle(mapOf("id" to 85L)), riders, backend.coachApi, activity, quotas, session).tracked()

    // AC13: the ride's newest review conversation and its latest review
    @Test
    fun aRideShowsItsLatestReview() = runTest(dispatcher) {
        withRider()
        backend.on(
            "GET /api/riders/5/coach/conversations",
            json(list(summary(33, "TRAINING_BLOCK"), summary(31, activityId = 85), summary(30, activityId = 85), summary(29, activityId = 7))),
        )
        backend.on("GET /api/coach/conversations/31", json(conversation(id = 31, review = REVIEW)))

        val state = askCoach().state.first { it.review != null }

        assertEquals(31L, state.conversationId)
        assertTrue(state.canContinue)
        assertEquals(7, state.review!!.riderRpe)
    }

    // AC13: the sheet sends RPE, note and language; accepted, the conversation opens
    @Test
    fun askingTheCoachStartsAReviewAndOpensIt() = runTest(dispatcher) {
        withRider()
        backend.on("GET /api/riders/5/coach/conversations", json("[]"))
        backend.on("POST /api/activities/85/coach/conversations", json(conversation(id = 50, busy = true), 202))
        val viewModel = askCoach()

        viewModel.openSheet()
        viewModel.update { it.copy(rpe = 6, note = " Legs heavy ", language = "en") }
        viewModel.start(free = true)

        val state = viewModel.state.first { it.open != null }
        assertEquals(50L, state.open)
        assertFalse(state.sheetOpen)
        assertEquals("""{"rpe":6,"note":"Legs heavy","language":"en"}""", body("POST /api/activities/85/coach/conversations"))
        assertTrue(activity.atCapacity.value)
    }

    // AC13: an existing review opens with its snackbar
    @Test
    fun anExistingReviewOpensInstead() = runTest(dispatcher) {
        withRider()
        backend.on("GET /api/riders/5/coach/conversations", json("[]"))
        backend.on(
            "POST /api/activities/85/coach/conversations",
            MockResponse.Builder().code(409).headers(headersOf("Content-Type", "application/problem+json"))
                .body("""{"status":409,"code":"COACH_REVIEW_EXISTS","conversationId":31}""").build(),
        )
        val viewModel = askCoach()
        viewModel.openSheet()

        viewModel.start(free = false)

        assertEquals(31L, viewModel.state.first { it.open != null }.open)
        assertEquals(app.climbra.android.R.string.ask_coach_review_exists, activity.notices.first().res)
    }

    // AC13: other errors stay in the sheet
    @Test
    fun anErrorStaysInTheSheet() = runTest(dispatcher) {
        withRider()
        backend.on("GET /api/riders/5/coach/conversations", json("[]"))
        backend.on("POST /api/activities/85/coach/conversations", problem(502, null))
        val viewModel = askCoach()
        viewModel.openSheet()

        viewModel.start(free = false)

        val state = viewModel.state.first { it.error != null }
        assertTrue(state.sheetOpen)
        assertNull(state.open)
    }
}
