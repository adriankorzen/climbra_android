package app.climbra.android.coach

import androidx.lifecycle.SavedStateHandle
import app.climbra.android.calendar.CalendarFixtures.PLAN
import app.climbra.android.calendar.CalendarFixtures.calendar
import app.climbra.android.coach.CoachFixtures.conversation
import app.climbra.android.coach.CoachFixtures.quota
import app.climbra.android.data.api.PlanAdjustmentRequest
import app.climbra.android.data.api.SuggestionReason
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.Network
import app.climbra.android.rider.DataChanges
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 008 AC1 - AC4, AC9: the adjustment form's prefill, validation, body and answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdjustmentFormViewModelTest : CoachTestBase() {

    private val changes = DataChanges()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)
    private val calendarRoute = "GET /api/riders/5/calendar"
    private val adjustRoute = "POST /api/riders/5/plans/7/adjustments"

    private fun form(
        planId: Long = 7,
        date: String = "2026-11-12",
        suggestion: Boolean = false,
        fromDate: String? = null,
        note: String? = null,
    ): AdjustmentFormViewModel {
        val args = buildMap<String, Any> {
            put("planId", planId)
            put("date", date)
            put("suggestion", suggestion)
            fromDate?.let { put("fromDate", it) }
            note?.let { put("note", it) }
        }
        return AdjustmentFormViewModel(
            SavedStateHandle(args), riders, backend.calendarApi, backend.coachApi, activity, quotas, changes, session, clock,
        ).tracked()
    }

    private suspend fun AdjustmentFormViewModel.loaded() = state.first { !it.loading }

    /** Accepted, and the credits reloaded after it: nothing is left running when the test ends. */
    private suspend fun AdjustmentFormViewModel.accepted(): Long {
        val id = state.first { it.accepted != null }.accepted!!
        // Joins the reload the form started
        quotas.reload()
        return id
    }

    private fun withPlan(plan: String = PLAN) {
        withRider()
        backend.on(calendarRoute, json(calendar(plans = plan)))
    }

    private fun sent(): PlanAdjustmentRequest = Network.json.decodeFromString(PlanAdjustmentRequest.serializer(), body(adjustRoute))

    // AC1: nothing preselected, tomorrow as the first day; the plan found through the day it covers
    @Test
    fun fromThePlanCardNothingIsPreselectedAndTomorrowIsTheFirstDay() = runTest(dispatcher) {
        withPlan()

        val state = form().loaded()

        assertEquals("FTP build", state.plan!!.name)
        assertTrue(state.reasons.isEmpty())
        assertNull(state.fatigue)
        assertEquals("", state.note)
        assertFalse(state.fromSuggestion)
        assertEquals(LocalDate.of(2026, 11, 13), state.fromDate)
        assertEquals(LocalDate.of(2026, 11, 29), state.endDate)
        val request = backend.requests(calendarRoute).single()
        assertEquals("2026-11-12", request.url.queryParameter("from"))
        assertEquals("2026-11-12", request.url.queryParameter("to"))
    }

    // AC1: a plan that ends today starts today
    @Test
    fun aPlanEndingTodayStartsToday() = runTest(dispatcher) {
        withPlan(PLAN.replace("2026-11-29", "2026-11-12"))

        assertEquals(LocalDate.of(2026, 11, 12), form().loaded().fromDate)
    }

    // AC2: the web's reasonsOfSuggestion
    @Test
    fun suggestionReasonsMapLikeTheWeb() {
        fun reason(code: String) = SuggestionReason(code = code, date = "2026-11-10")

        assertEquals(setOf("MISSED_WORKOUTS"), reasonsOfSuggestion(listOf(reason("KEY_WORKOUT_MISSED"), reason("WORKOUTS_MISSED"))))
        assertEquals(setOf("DEVIATION"), reasonsOfSuggestion(listOf(reason("WORKOUT_DEVIATION"), reason("WEEK_LOAD_DEVIATION"))))
    }

    // AC2: from the suggestion its reasons are preselected and the suggestion is sent along
    @Test
    fun fromTheSuggestionItsReasonsArePreselectedAndSent() = runTest(dispatcher) {
        withPlan(PLAN.replace("\"WORKOUT_DEVIATION\"", "\"KEY_WORKOUT_MISSED\""))
        backend.on(adjustRoute, json(conversation(id = 40, type = "PLAN_ADJUSTMENT", busy = true, planId = 7, activityId = null), 202))
        backend.on("GET /api/account/coach-quota", json(quota()))
        val viewModel = form(suggestion = true)

        val state = viewModel.loaded()
        assertTrue(state.fromSuggestion)
        assertEquals(setOf("MISSED_WORKOUTS", "DEVIATION"), state.reasons)

        viewModel.send()
        viewModel.accepted()
        val request = sent()
        assertTrue(request.includeSuggestion)
        assertEquals(listOf("MISSED_WORKOUTS", "DEVIATION"), request.reasons)
    }

    // AC3: no reason, no credits: no Send
    @Test
    fun sendNeedsAReasonAndEnoughCredits() = runTest(dispatcher) {
        backend.on("GET /api/account/coach-quota", json(quota(remaining = 2)))
        withPlan()
        loggedIn()
        quotas.quota.first { it != null }
        val viewModel = form()
        viewModel.loaded()

        assertFalse(viewModel.state.value.canSend)
        viewModel.toggleReason("FATIGUE")
        // An adjustment costs 3 credits
        assertFalse(viewModel.state.value.canSend)
        viewModel.send()
        assertEquals(0, count(adjustRoute))
    }

    // AC3: reasons in the fixed order, fatigue, trimmed note, first day, the chosen days and the language
    @Test
    fun theRequestCarriesTheWebsBody() = runTest(dispatcher) {
        loggedIn(language = "pl")
        withPlan()
        backend.on(adjustRoute, json(conversation(id = 40, type = "PLAN_ADJUSTMENT", busy = true, planId = 7, activityId = null), 202))
        backend.on("GET /api/account/coach-quota", json(quota()))
        val viewModel = form()
        viewModel.loaded()

        viewModel.toggleReason("OTHER")
        viewModel.toggleReason("FATIGUE")
        viewModel.toggleReason("ILLNESS")
        viewModel.toggleReason("ILLNESS")
        viewModel.setFatigue(7)
        viewModel.setFatigue(4)
        viewModel.setNote("  Tired after travel  ")
        viewModel.setFromDate(LocalDate.of(2026, 11, 16))
        // Switched on with days chosen: sent; minutes stay within 15 – 600
        viewModel.setChangeDays(true)
        viewModel.updateDay(DayOfWeek.TUESDAY) { it.copy(available = true, minutes = 90) }
        viewModel.updateDay(DayOfWeek.SATURDAY) { it.copy(available = true, minutes = 900) }
        assertTrue(viewModel.state.value.canSend)
        viewModel.send()

        viewModel.accepted()
        val request = sent()
        assertEquals(listOf("FATIGUE", "OTHER"), request.reasons)
        assertEquals(4, request.fatigue)
        assertEquals("Tired after travel", request.note)
        assertEquals("2026-11-16", request.fromDate)
        assertEquals(mapOf("TUESDAY" to 90, "SATURDAY" to 600), request.availability!!.days)
        assertFalse(request.includeSuggestion)
        assertEquals("pl", request.language)
    }

    // AC3: tapping the chosen fatigue clears it; a blank note and no chosen day are null
    @Test
    fun emptyValuesAreNull() = runTest(dispatcher) {
        withPlan()
        backend.on(adjustRoute, json(conversation(id = 40, type = "PLAN_ADJUSTMENT", busy = true, planId = 7, activityId = null), 202))
        backend.on("GET /api/account/coach-quota", json(quota()))
        val viewModel = form()
        viewModel.loaded()

        viewModel.toggleReason("LACK_OF_TIME")
        viewModel.setFatigue(5)
        viewModel.setFatigue(5)
        viewModel.setNote("   ")
        viewModel.setChangeDays(true)
        viewModel.send()

        viewModel.accepted()
        val request = sent()
        assertNull(request.fatigue)
        assertNull(request.note)
        assertNull(request.availability)
    }

    // AC4: accepted, the conversation is tracked, data changed, and it replaces the form
    @Test
    fun anAcceptedRequestOpensItsConversation() = runTest(dispatcher) {
        // No polling: while the test waits for real HTTP calls, virtual time runs and a poll would forget the request
        foreground.value = false
        withPlan()
        backend.on(adjustRoute, json(conversation(id = 40, type = "PLAN_ADJUSTMENT", busy = true, planId = 7, activityId = null, title = "Plan adjustment"), 202))
        backend.on("GET /api/account/coach-quota", json(quota()))
        val viewModel = form()
        viewModel.loaded()
        val version = changes.version.value

        viewModel.toggleReason("FATIGUE")
        viewModel.send()

        assertEquals(40L, viewModel.accepted())
        assertEquals(mapOf(40L to "Plan adjustment"), activity.running.value)
        assertEquals(version + 1, changes.version.value)
    }

    // AC4: the backend's refusals keep the form
    @Test
    fun refusalsShowTheirErrorAndKeepTheForm() = runTest(dispatcher) {
        withPlan()
        backend.on(
            adjustRoute,
            problem(409, "PLAN_FINISHED"),
            problem(409, "PLAN_ADJUSTMENT_RUNNING"),
            problem(429, "COACH_MONTHLY_LIMIT"),
        )
        val viewModel = form()
        viewModel.loaded()
        viewModel.toggleReason("FATIGUE")
        viewModel.setNote("Busy week")

        for (code in listOf("PLAN_FINISHED", "PLAN_ADJUSTMENT_RUNNING", "COACH_MONTHLY_LIMIT")) {
            viewModel.send()
            val state = viewModel.state.first { it.error != null && !it.sending }
            assertEquals(code, (state.error as ApiError.Http).code)
            assertEquals(setOf("FATIGUE"), state.reasons)
            assertEquals("Busy week", state.note)
            assertNull(state.accepted)
        }
        assertTrue(activity.running.value.isEmpty())
    }

    // AC9: from a review: Deviation, the change day, the note
    @Test
    fun fromAReviewTheFormIsPrefilled() = runTest(dispatcher) {
        withPlan()

        val state = form(date = "2026-11-17", fromDate = "2026-11-17", note = "Coach's review of Ride: Legs need a lighter day.").loaded()

        assertEquals(setOf("DEVIATION"), state.reasons)
        assertEquals(LocalDate.of(2026, 11, 17), state.fromDate)
        assertEquals("Coach's review of Ride: Legs need a lighter day.", state.note)
        assertFalse(state.fromSuggestion)
        assertEquals("2026-11-17", backend.requests(calendarRoute).single().url.queryParameter("from"))
    }

    // AC9: a change day in the past becomes tomorrow; a long note is cut
    @Test
    fun aPastChangeDayBecomesTomorrowAndTheNoteIsCut() = runTest(dispatcher) {
        withPlan()

        val state = form(fromDate = "2026-11-10", note = "x".repeat(2100)).loaded()

        assertEquals(LocalDate.of(2026, 11, 13), state.fromDate)
        assertEquals(2000, state.note.length)
    }

    // AC9: the plan is not in the calendar any more: no Send
    @Test
    fun aMissingPlanDisablesSend() = runTest(dispatcher) {
        withPlan()
        val viewModel = form(planId = 99, fromDate = "2026-11-17", note = "Review")

        val state = viewModel.loaded()
        assertTrue(state.planMissing)
        assertFalse(state.canSend)
        viewModel.send()
        assertEquals(0, count("POST /api/riders/5/plans/99/adjustments"))
    }
}
