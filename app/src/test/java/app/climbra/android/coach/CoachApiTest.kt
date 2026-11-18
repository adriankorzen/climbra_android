package app.climbra.android.coach

import app.climbra.android.coach.CoachFixtures.BLOCK
import app.climbra.android.coach.CoachFixtures.PLAN_CHANGE
import app.climbra.android.coach.CoachFixtures.REVIEW
import app.climbra.android.coach.CoachFixtures.conversation
import app.climbra.android.coach.CoachFixtures.pending
import app.climbra.android.data.api.SavePlanRequest
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.data.net.apiResponseCall
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 006 AC5, AC8, AC10 - AC13, AC15: the coach's DTOs of backend JSON, multipart bodies and problem details. */
class CoachApiTest {

    private val backend = TestBackend()

    @After
    fun tearDown() = backend.close()

    @Test
    fun readsAConversationWithItsReviewBlockPlanAndPendingRequest() = runTest {
        backend.on(
            "GET /api/coach/conversations/12",
            json(conversation(review = REVIEW, block = BLOCK, plan = """{"id":4,"updatedAt":"2026-11-12T10:00:00Z","outdated":true}""", planChange = PLAN_CHANGE, pending = pending(errorCode = "TIMEOUT", status = "FAILED"))),
        )

        val result = apiResponseCall(Network.json) { backend.coachApi.conversation(12) }

        val c = (result as ApiResult.Success).value.body()!!
        assertEquals("ACTIVITY_REVIEW", c.type)
        assertEquals(listOf("USER", "ASSISTANT"), c.messages.map { it.role })
        val review = c.latestReview!!
        assertEquals(6, review.estimatedRpe)
        assertEquals(listOf("RECOVERY", "PACING", "RECOVERY"), review.recommendations.map { it.category })
        assertEquals(251, review.nextSession!!.blocks[1].steps[0].powerHighWatts)
        assertEquals(4, review.nextSession!!.blocks[1].steps[0].zone)
        assertEquals("CHANGE_WORKOUT", review.planAssessment!!.verdict)
        assertEquals("APPLIED", c.reviewPlanChange!!.status)
        assertEquals(2, c.latestTrainingBlock!!.weeks.size)
        assertTrue(c.latestTrainingBlock!!.weeks[1].isRecoveryWeek)
        assertTrue(c.plan!!.outdated)
        assertEquals("TIMEOUT", c.pendingRequest!!.errorCode)
        assertTrue(c.pendingRequest!!.failed)
    }

    // AC8
    @Test
    fun aFollowUpIsMultipartWithItsTextAndFile() = runTest {
        backend.on("POST /api/coach/conversations/12/messages", json(conversation(busy = true), 202))
        val files = FakeFileSource(mapOf("content://ride.fit" to byteArrayOf(1, 2, 3)))

        val result = apiCall(Network.json) {
            backend.coachApi.sendMessage(
                12,
                "And tomorrow?".toRequestBody("text/plain; charset=utf-8".toMediaType()),
                files.part("file", PickedFile("content://ride.fit", "ride.fit")),
            )
        }

        assertTrue((result as ApiResult.Success).value.busy)
        val request = backend.requests("POST /api/coach/conversations/12/messages").single()
        val body = request.body!!.utf8()
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        assertTrue(body.contains("name=\"text\""))
        assertTrue(body.contains("And tomorrow?"))
        assertTrue(body.contains("name=\"file\"; filename=\"ride.fit\""))
        assertTrue(body.contains("Content-Type: application/octet-stream"))
    }

    // AC15
    @Test
    fun aFileReviewSendsTheFileWithLanguageRpeAndNoteInTheQuery() = runTest {
        backend.on("POST /api/riders/5/coach/conversations/file-review", json(conversation(type = "FILE_REVIEW", busy = true), 202))
        val files = FakeFileSource(mapOf("content://rides.zip" to byteArrayOf(9)))

        apiCall(Network.json) {
            backend.coachApi.startFileReview(5, files.part("file", PickedFile("content://rides.zip", "rides.zip")), "pl", 7, "Heavy legs")
        }

        val request = backend.requests("POST /api/riders/5/coach/conversations/file-review").single()
        assertEquals("pl", request.url.queryParameter("language"))
        assertEquals("7", request.url.queryParameter("rpe"))
        assertEquals("Heavy legs", request.url.queryParameter("note"))
        assertTrue(request.body!!.utf8().contains("filename=\"rides.zip\""))
        assertTrue(request.body!!.utf8().contains("Content-Type: application/zip"))
    }

    // AC13
    @Test
    fun anExistingReviewNamesItsConversation() = runTest {
        backend.on(
            "POST /api/activities/85/coach/conversations",
            problemWith(409, """"code":"COACH_REVIEW_EXISTS","conversationId":31"""),
        )

        val result = apiCall(Network.json) {
            backend.coachApi.startActivityReview(85, app.climbra.android.data.api.ActivityReviewRequest(7, null, "en"))
        }

        assertEquals(ApiError.Http(409, "COACH_REVIEW_EXISTS", conversationId = 31), (result as ApiResult.Failure).error)
        // No note: the field is left out, as the backend's optional field
        assertEquals("""{"rpe":7,"language":"en"}""", backend.requests.last().body!!.utf8())
    }

    // AC12
    @Test
    fun anOverlapNamesThePlan() = runTest {
        backend.on(
            "POST /api/coach/conversations/12/plan",
            problemWith(409, """"code":"PLAN_OVERLAP","overlappingPlan":{"id":3,"name":"Autumn base","startDate":"2026-10-01","endDate":"2026-11-30"}"""),
        )

        val result = apiCall(Network.json) { backend.coachApi.savePlan(12, SavePlanRequest(replaceOverlapping = false)) }

        val error = (result as ApiResult.Failure).error as ApiError.Http
        assertEquals("Autumn base", error.overlappingPlanName)
        assertNull(error.conversationId)
        assertEquals("""{"replaceOverlapping":false}""", backend.requests.last().body!!.utf8())
    }

    // Spec 008 AC5: the proposal as the backend writes it
    @Test
    fun readsAProposal() = runTest {
        backend.on("GET /api/riders/5/plans/7/proposals/9", json(CoachFixtures.proposal()))

        val proposal = (apiCall(Network.json) { backend.coachApi.proposal(5, 7, 9) } as ApiResult.Success).value

        assertEquals("PENDING", proposal.status)
        assertEquals("2026-11-14", proposal.fromDate)
        assertEquals(listOf("CHANGED", "UNCHANGED", "REMOVED", "ADDED"), proposal.days.map { it.change })
        assertEquals("Easy spin", proposal.days[0].after!!.title)
        assertNull(proposal.days[2].after)
        assertTrue(proposal.days[2].completed)
        assertEquals(250, proposal.weeks[0].plannedTssAfter)
        assertNull(proposal.weeks[1].focusAfter)
    }

    // Spec 008 AC11: the new error codes are translated
    @Test
    fun thePlanAdjustmentErrorCodesAreTranslated() {
        listOf("PLAN_FINISHED", "PLAN_ADJUSTMENT_RUNNING", "PROPOSAL_OUTDATED", "REVIEW_NO_PLAN_CHANGE", "PLAN_CHANGE_NOT_APPLICABLE")
            .forEach { assertTrue(it, it in app.climbra.android.ui.common.TRANSLATED_ERROR_CODES) }
    }

    private fun problemWith(status: Int, fields: String): MockResponse = MockResponse.Builder()
        .code(status)
        .headers(headersOf("Content-Type", "application/problem+json"))
        .body("""{"type":"about:blank","title":"Conflict","status":$status,"detail":"Conflict",$fields}""")
        .build()
}
