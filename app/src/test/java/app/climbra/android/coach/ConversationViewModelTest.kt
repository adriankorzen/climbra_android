package app.climbra.android.coach

import androidx.lifecycle.SavedStateHandle
import app.climbra.android.coach.CoachFixtures.BLOCK
import app.climbra.android.coach.CoachFixtures.REVIEW
import app.climbra.android.coach.CoachFixtures.conversation
import app.climbra.android.coach.CoachFixtures.latestProposal
import app.climbra.android.coach.CoachFixtures.proposal
import app.climbra.android.coach.CoachFixtures.pending
import app.climbra.android.coach.CoachFixtures.quota
import app.climbra.android.data.net.ApiError
import app.climbra.android.rider.DataChanges
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import java.time.Clock
import java.time.Instant
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

/** Spec 006 AC5 - AC9, AC12: a conversation's loading, polling, follow-ups, failures and the plan's save. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest : CoachTestBase() {

    private val changes = DataChanges()
    private val files = FakeFileSource(mapOf("content://ride.fit" to byteArrayOf(1, 2, 3)))
    private val clock = Clock.fixed(Instant.parse("2026-11-12T09:03:30Z"), ZoneOffset.UTC)

    private fun viewModel(id: Long = 12) =
        ConversationViewModel(SavedStateHandle(mapOf("id" to id)), backend.coachApi, activity, quotas, files, changes, clock).tracked()

    private suspend fun ConversationViewModel.settled() = state.first { !it.loading && !it.submitting && !it.savingPlan }

    private val route = "GET /api/coach/conversations/12"

    // AC5
    @Test
    fun loadsTheConversationWithTheServersClock() = runTest(dispatcher) {
        backend.on(
            route,
            MockResponse.Builder().code(200)
                .headers(headersOf("Content-Type", "application/json", "Date", "Thu, 12 Nov 2026 09:03:40 GMT"))
                .body(conversation()).build(),
        )

        val state = viewModel().settled()

        assertEquals(2, state.conversation!!.messages.size)
        assertEquals(10_000L, state.serverOffsetMillis)
        assertFalse(state.hasPanel)
    }

    // AC5
    @Test
    fun aMissingConversationIsNotFound() = runTest(dispatcher) {
        backend.on(route, problem(404, null))

        assertTrue(viewModel().settled().notFound)
    }

    // AC5: the block's call-to-action stays under the newest message that carries it
    @Test
    fun theBlockCallToActionSitsUnderTheNewestBlockMessage() = runTest(dispatcher) {
        val messages = "[" + listOf(
            CoachFixtures.message(1, "USER", "Plan"),
            CoachFixtures.message(2, "ASSISTANT", "Block v1", ""","trainingBlock":true"""),
            CoachFixtures.message(3, "USER", "Easier"),
            CoachFixtures.message(4, "ASSISTANT", "Block v2", ""","trainingBlock":true"""),
            CoachFixtures.message(5, "ASSISTANT", "Note"),
        ).joinToString(",") + "]"
        backend.on(route, json(conversation(type = "TRAINING_BLOCK", messages = messages, block = BLOCK)))

        val state = viewModel().settled()

        assertEquals(4L, state.blockMessageId)
        assertTrue(state.hasPanel)
    }

    // AC6, AC17: a busy conversation is loaded again until it is answered; then the list and the credits reload
    @Test
    fun aBusyConversationIsPolledWhileShownUntilAnswered() = runTest(dispatcher) {
        backend.on(route, json(conversation(busy = true, pending = pending())), json(conversation(busy = true, pending = pending())), json(conversation(review = REVIEW)))
        backend.on("GET /api/account/coach-quota", json(quota(remaining = 20)))
        val viewModel = viewModel()
        assertTrue(viewModel.settled().busy)
        assertTrue(activity.atCapacity.value)
        val finished = activity.finished.value

        viewModel.setShown(true)

        viewModel.state.first { !it.busy && it.conversation?.latestReview != null }
        assertEquals(3, count(route))
        assertFalse(activity.atCapacity.value)
        assertEquals(finished + 1, activity.finished.value)
        eventually { assertEquals(20, quotas.quota.value?.remaining) }
    }

    // AC6: not shown, it is not polled here
    @Test
    fun aConversationNotShownIsNotPolledHere() = runTest(dispatcher) {
        foreground.value = false
        backend.on(route, json(conversation(busy = true, pending = pending())))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.setShown(true)
        viewModel.setShown(false)
        eventually { assertEquals(1, count(route)) }
        assertTrue(viewModel.state.value.busy)
    }

    // AC8: a follow-up sends text and file, clears the input and shows the request
    @Test
    fun aFollowUpSendsTextAndFileAndClearsTheInput() = runTest(dispatcher) {
        backend.on(route, json(conversation()))
        backend.on("POST /api/coach/conversations/12/messages", json(conversation(busy = true, pending = pending()), 202))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.onDraftChange("  And tomorrow?  ")
        viewModel.attach(PickedFile("content://ride.fit", "ride.fit"))
        viewModel.send()

        val state = viewModel.state.first { it.busy }
        assertEquals("", state.draft)
        assertNull(state.file)
        assertEquals("And tomorrow?", state.pending?.text)
        val body = body("POST /api/coach/conversations/12/messages")
        assertTrue(body.contains("And tomorrow?"))
        assertTrue(body.contains("filename=\"ride.fit\""))
        assertTrue(state.locked)
        assertEquals(mapOf(12L to "Ride review"), activity.running.value)
    }

    // AC8: a wrong file type is rejected before sending
    @Test
    fun aFileOfAnotherTypeIsNotTaken() = runTest(dispatcher) {
        backend.on(route, json(conversation()))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.attach(PickedFile("content://photo.jpg", "photo.jpg"))

        assertNull(viewModel.state.value.file)
        assertTrue(viewModel.state.value.fileError)
    }

    // AC8: a refused request shows its error, and goes again or back into the input
    @Test
    fun aRefusedRequestCanBeSentAgainOrEdited() = runTest(dispatcher) {
        backend.on(route, json(conversation()))
        backend.on(
            "POST /api/coach/conversations/12/messages",
            problem(429, "COACH_MONTHLY_LIMIT"),
            problem(409, "COACH_BUSY"),
            json(conversation(busy = true, pending = pending()), 202),
        )
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.onDraftChange("And tomorrow?")
        viewModel.send()
        val refused = viewModel.state.first { it.refused != null && !it.submitting }.refused!!
        assertEquals("And tomorrow?", refused.text)
        assertEquals(ApiError.Http(429, "COACH_MONTHLY_LIMIT"), refused.error)
        assertEquals(app.climbra.android.R.string.coach_error_limit, coachErrorHeadline(refused.error))

        viewModel.edit()
        assertEquals("And tomorrow?", viewModel.state.value.draft)
        assertNull(viewModel.state.value.refused)

        viewModel.send()
        viewModel.state.first { it.refused != null && !it.submitting }
        viewModel.sendAgain()
        assertTrue(viewModel.state.first { it.busy }.refused == null)
        assertEquals(3, count("POST /api/coach/conversations/12/messages"))
    }

    // AC7: a failed follow-up offers retry and edit again; a failed start retry and delete
    @Test
    fun aFailedRequestIsRetriedOrEditedAgain() = runTest(dispatcher) {
        backend.on(route, json(conversation(pending = pending(status = "FAILED", errorCode = "PROVIDER_ERROR"))))
        backend.on("POST /api/coach/conversations/12/retry", json(conversation(busy = true, pending = pending()), 202))
        val viewModel = viewModel()
        val state = viewModel.settled()
        assertTrue(state.pending!!.failed)
        assertTrue(state.canRetry)
        assertEquals(CostKind.REVIEW_MESSAGE, state.retryKind)

        viewModel.edit()
        assertEquals("And tomorrow?", viewModel.state.value.draft)

        viewModel.retry()
        viewModel.state.first { it.busy }
        assertEquals(1, count("POST /api/coach/conversations/12/retry"))
        // Joins the credits' reload the retry started: nothing is left running when the test ends
        quotas.reload()
    }

    // AC7: no credits or another request running: no retry
    @Test
    fun retryWaitsForCreditsAndForTheOtherRequest() = runTest(dispatcher) {
        backend.on("GET /api/account/coach-quota", json(quota(remaining = 0)))
        backend.on(route, json(conversation(pending = pending(status = "FAILED", kind = "START", text = null))))
        loggedIn()
        quotas.quota.first { it != null }
        val viewModel = viewModel()

        val state = viewModel.settled()
        assertEquals(CostKind.REVIEW, state.retryKind)
        assertFalse(state.canRetry)

        viewModel.retry()
        assertEquals(0, count("POST /api/coach/conversations/12/retry"))
    }

    // AC7: a failed start is deleted; back to the list
    @Test
    fun aFailedStartIsDeleted() = runTest(dispatcher) {
        backend.on(route, json(conversation(pending = pending(status = "FAILED", kind = "START", text = null))))
        backend.on("DELETE /api/coach/conversations/12", noContent())
        val viewModel = viewModel()
        viewModel.settled()
        val changes = activity.changes.value

        viewModel.delete()

        assertTrue(viewModel.state.first { it.deleted }.deleted)
        assertTrue(activity.changes.value > changes)
    }

    // AC9
    @Test
    fun aReadOnlyConversationOrAnotherRunningRequestLocksTheInput() = runTest(dispatcher) {
        backend.on(route, json(conversation(readOnly = true)))
        val viewModel = viewModel()
        assertFalse(viewModel.settled().inputEnabled)

        backend.replace(route, json(conversation()))
        viewModel.retryLoad()
        assertTrue(viewModel.state.first { it.conversation?.readOnly == false }.inputEnabled)

        activity.track(CoachFixtures.parse(conversation(id = 99, busy = true)))
        val state = viewModel.state.first { it.limits.atCapacity }
        viewModel.onDraftChange("Hello")
        assertFalse(viewModel.state.value.canSend)
        assertTrue(state.locked)
    }

    // AC9: without enough credits the input is off
    @Test
    fun notEnoughCreditsLockTheInput() = runTest(dispatcher) {
        backend.on("GET /api/account/coach-quota", json(quota(remaining = 0)))
        backend.on(route, json(conversation()))
        loggedIn()
        quotas.quota.first { it != null }

        val state = viewModel().settled()

        assertFalse(state.inputEnabled)
    }

    // AC12: an overlap asks, then replaces; the save reloads and tells the calendar
    @Test
    fun savingTheBlockAsksAboutAnOverlapAndReplaces() = runTest(dispatcher) {
        backend.on(route, json(conversation(type = "TRAINING_BLOCK", block = BLOCK)), json(conversation(type = "TRAINING_BLOCK", block = BLOCK, plan = """{"id":4,"outdated":false}""")))
        backend.on(
            "POST /api/coach/conversations/12/plan",
            MockResponse.Builder().code(409).headers(headersOf("Content-Type", "application/problem+json"))
                .body("""{"status":409,"code":"PLAN_OVERLAP","overlappingPlan":{"id":3,"name":"Autumn base"}}""").build(),
            json("""{"id":4}"""),
        )
        val viewModel = viewModel()
        viewModel.settled()
        val version = changes.version.value

        viewModel.savePlan()
        assertEquals("Autumn base", viewModel.state.first { it.overlap != null }.overlap)
        assertEquals("""{"replaceOverlapping":false}""", backend.requests("POST /api/coach/conversations/12/plan")[0].body!!.utf8())

        viewModel.savePlan(replaceOverlapping = true)

        val state = viewModel.state.first { it.message != null }
        assertEquals(ConversationMessage.PlanAdded, state.message)
        assertNull(state.overlap)
        assertEquals("""{"replaceOverlapping":true}""", backend.requests("POST /api/coach/conversations/12/plan")[1].body!!.utf8())
        assertEquals(version + 1, changes.version.value)
        assertEquals(4L, viewModel.state.first { it.conversation?.plan != null }.conversation!!.plan!!.id)
    }

    // AC12: an outdated plan is updated
    @Test
    fun anOutdatedPlanIsUpdated() = runTest(dispatcher) {
        backend.on(route, json(conversation(type = "TRAINING_BLOCK", block = BLOCK, plan = """{"id":4,"outdated":true}""")))
        backend.on("POST /api/coach/conversations/12/plan", json("""{"id":4}"""))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.savePlan()

        assertEquals(ConversationMessage.PlanUpdated, viewModel.state.first { it.message != null }.message)
    }

    // An answer that changed the review flags the panel
    @Test
    fun anAnswerThatChangedTheReviewFlagsThePanel() = runTest(dispatcher) {
        backend.on(route, json(conversation(review = REVIEW)), json(conversation(review = REVIEW.replace("A solid", "A strong"))))
        backend.on("POST /api/coach/conversations/12/messages", json(conversation(busy = true, review = REVIEW, pending = pending()), 202))
        val viewModel = viewModel()
        viewModel.settled()
        viewModel.setShown(true)

        viewModel.onDraftChange("Rewrite it")
        viewModel.send()

        assertTrue(viewModel.state.first { it.updated }.updated)
    }

    // ---- Spec 008: the proposal of a plan adjustment, the review's change of the plan

    private val proposalRoute = "GET /api/riders/5/plans/7/proposals/9"
    private val acceptRoute = "POST /api/riders/5/plans/7/proposals/9/accept"
    private val rejectRoute = "POST /api/riders/5/plans/7/proposals/9/reject"

    private fun adjustment(proposal: String = latestProposal()) =
        conversation(type = "PLAN_ADJUSTMENT", planId = 7, activityId = null, proposal = proposal)

    private suspend fun ConversationViewModel.withProposal(status: String = "PENDING") =
        state.first { it.proposal?.status == status && !it.deciding }

    // Spec 008 AC5: the proposal tab loads the conversation's latest proposal
    @Test
    fun aPlanAdjustmentLoadsItsProposal() = runTest(dispatcher) {
        backend.on(route, json(adjustment()))
        backend.on(proposalRoute, json(proposal()))

        val state = viewModel().withProposal()

        assertTrue(state.hasProposal)
        assertTrue(state.hasPanel)
        assertEquals(4, state.proposal!!.days.size)
        assertEquals(ConversationTab.CHAT, state.tab)
    }

    // Spec 008 AC5: a follow-up's new proposal loads again; a failure offers a retry
    @Test
    fun aNewProposalLoadsAgainAndAFailureCanBeRetried() = runTest(dispatcher) {
        backend.on(route, json(adjustment()), json(adjustment(latestProposal(id = 10))))
        backend.on(proposalRoute, problem(503, null), json(proposal()))
        backend.on("GET /api/riders/5/plans/7/proposals/10", json(proposal(id = 10)))
        val viewModel = viewModel()

        assertEquals(ApiError.Http(503, null), viewModel.state.first { it.proposalError != null }.proposalError)
        viewModel.retryProposal()
        viewModel.withProposal()

        viewModel.retryLoad()
        assertEquals(10L, viewModel.state.first { it.proposal?.id == 10L }.proposal!!.id)
    }

    // Spec 008 AC7: the calendar's banner opens the proposal tab
    @Test
    fun theProposalTabCanBeTheFirst() = runTest(dispatcher) {
        backend.on(route, json(adjustment()))
        backend.on(proposalRoute, json(proposal()))

        val viewModel = ConversationViewModel(
            SavedStateHandle(mapOf("id" to 12L, "tab" to "PANEL")), backend.coachApi, activity, quotas, files, changes, clock,
        ).tracked()

        assertEquals(ConversationTab.PANEL, viewModel.withProposal().tab)
    }

    // Spec 008 AC6: accepted, the plan changed and the proposal reloads
    @Test
    fun acceptingAdjustsThePlanAndReloads() = runTest(dispatcher) {
        backend.on(route, json(adjustment()))
        backend.on(proposalRoute, json(proposal()), json(proposal(status = "ACCEPTED")))
        backend.on(acceptRoute, json("""{"id":7}"""))
        val viewModel = viewModel()
        viewModel.withProposal()
        val version = changes.version.value

        viewModel.acceptProposal()

        val state = viewModel.withProposal("ACCEPTED")
        assertEquals(ConversationMessage.ProposalAccepted, state.message)
        assertEquals(1, count(acceptRoute))
        assertEquals(version + 1, changes.version.value)
        // Decided: nothing more is sent
        viewModel.acceptProposal()
        viewModel.askReject()
        viewModel.rejectProposal()
        assertEquals(1, count(acceptRoute))
        assertEquals(0, count(rejectRoute))
    }

    // Spec 008 AC6: rejecting asks first
    @Test
    fun rejectingAsksFirstAndReloads() = runTest(dispatcher) {
        backend.on(route, json(adjustment()))
        backend.on(proposalRoute, json(proposal()), json(proposal(status = "REJECTED")))
        backend.on(rejectRoute, json("""{"id":9}"""))
        val viewModel = viewModel()
        viewModel.withProposal()

        viewModel.askReject()
        assertTrue(viewModel.state.value.confirmingReject)
        viewModel.cancelReject()
        assertFalse(viewModel.state.value.confirmingReject)
        assertEquals(0, count(rejectRoute))

        viewModel.askReject()
        viewModel.rejectProposal()

        val state = viewModel.withProposal("REJECTED")
        assertFalse(state.confirmingReject)
        assertEquals(ConversationMessage.ProposalRejected, state.message)
        assertEquals(1, count(rejectRoute))
    }

    // Spec 008 AC6: an outdated proposal tells why and reloads
    @Test
    fun anOutdatedProposalTellsWhyAndReloads() = runTest(dispatcher) {
        backend.on(route, json(adjustment()))
        backend.on(proposalRoute, json(proposal()), json(proposal(status = "OUTDATED")))
        backend.on(acceptRoute, problem(409, "PROPOSAL_OUTDATED"))
        val viewModel = viewModel()
        viewModel.withProposal()
        val version = changes.version.value

        viewModel.acceptProposal()

        val state = viewModel.withProposal("OUTDATED")
        assertEquals(ConversationMessage.Failed(ApiError.Http(409, "PROPOSAL_OUTDATED")), state.message)
        assertEquals(version, changes.version.value)
    }

    // Spec 008 AC8: the review's workout goes into the plan
    @Test
    fun replacingInThePlanAppliesTheReviewsChange() = runTest(dispatcher) {
        val applicable = CoachFixtures.PLAN_CHANGE.replace("APPLIED", "APPLICABLE")
        backend.on(route, json(conversation(review = REVIEW, planChange = applicable)), json(conversation(review = REVIEW, planChange = CoachFixtures.PLAN_CHANGE)))
        backend.on("POST /api/coach/conversations/12/review/plan-change", json(CoachFixtures.PLAN_CHANGE))
        val viewModel = viewModel()
        viewModel.settled()
        val version = changes.version.value

        viewModel.replaceInPlan()

        val state = viewModel.state.first { it.conversation?.reviewPlanChange?.status == "APPLIED" && !it.replacing }
        assertEquals(ConversationMessage.WorkoutReplaced, state.message)
        assertEquals(version + 1, changes.version.value)
        assertEquals(2, count(route))
    }

    // Spec 008 AC8: the plan-change errors
    @Test
    fun aRefusedReplacementTellsWhy() = runTest(dispatcher) {
        backend.on(route, json(conversation(review = REVIEW, planChange = CoachFixtures.PLAN_CHANGE.replace("APPLIED", "APPLICABLE"))))
        backend.on("POST /api/coach/conversations/12/review/plan-change", problem(409, "PLAN_CHANGE_NOT_APPLICABLE"))
        val viewModel = viewModel()
        viewModel.settled()
        val version = changes.version.value

        viewModel.replaceInPlan()

        val state = viewModel.state.first { it.message != null && !it.replacing }
        assertEquals(ConversationMessage.Failed(ApiError.Http(409, "PLAN_CHANGE_NOT_APPLICABLE")), state.message)
        assertEquals(version, changes.version.value)
    }
}
