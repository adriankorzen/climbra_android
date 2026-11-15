package app.climbra.android.coach

import app.climbra.android.coach.CoachFixtures.conversation
import app.climbra.android.coach.CoachFixtures.list
import app.climbra.android.coach.CoachFixtures.parse
import app.climbra.android.coach.CoachFixtures.parseQuota
import app.climbra.android.coach.CoachFixtures.pending
import app.climbra.android.coach.CoachFixtures.quota
import app.climbra.android.coach.CoachFixtures.summary
import app.climbra.android.session.SessionRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 006 AC16, AC17: requests followed in the background, the credits of the whole app. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachActivityTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val session: SessionRepository = backend.session(scope)
    private val foreground = MutableStateFlow(true)
    private val quotas = backend.quotas(session, scope)
    private val activity = backend.coachActivity(session, scope, quotas, foreground)

    @After
    fun tearDown() {
        scope.cancel()
        backend.close()
    }

    private suspend fun loggedIn() {
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        session.login("anna@example.com", "correct-horse-1")
    }

    /** Requests reach the server on OkHttp's threads: waits in real time while the dispatcher runs. */
    private suspend fun eventually(check: () -> Unit) {
        val end = System.currentTimeMillis() + 5_000
        while (true) {
            try {
                check()
                return
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > end) throw e
                withContext(Dispatchers.IO) { Thread.sleep(20) }
            }
        }
    }

    // AC16
    @Test
    fun anAcceptedRequestIsFollowedUntilItEndsAndThenTold() = runTest(dispatcher) {
        backend.on("GET /api/account/coach-quota", json(quota()))
        backend.on(
            "GET /api/coach/conversations/12",
            json(conversation(busy = true, pending = pending())),
            json(conversation(busy = false)),
        )
        val changes = activity.changes.value

        activity.track(parse(conversation(busy = true, title = "Tempo ride")))
        assertTrue(activity.atCapacity.value)

        // Told with the title the backend has now
        val event = activity.events.first()
        assertEquals(CoachFinished(12, "Ride review", failed = false), event)
        assertFalse(activity.atCapacity.value)
        assertEquals(1, activity.finished.value)
        assertTrue(activity.changes.value > changes)
        // The credits reload when the request ended
        eventually { assertEquals(25, quotas.quota.value?.remaining) }
    }

    // AC16
    @Test
    fun aFailedRequestEndsAsFailed() = runTest(dispatcher) {
        backend.on("GET /api/coach/conversations/12", json(conversation(busy = false, pending = pending(status = "FAILED", errorCode = "TIMEOUT"))))

        activity.track(parse(conversation(busy = true, title = "Tempo ride")))

        assertTrue(activity.events.first().failed)
    }

    // AC16: a shown conversation is loaded by its screen, and ends without a snackbar
    @Test
    fun aShownConversationIsNotPolledAndEndsQuietly() = runTest(dispatcher) {
        backend.on("GET /api/coach/conversations/12", json(conversation(busy = false)))
        val unwatch = activity.watch(12)

        activity.track(parse(conversation(busy = true)))
        withContext(Dispatchers.IO) { Thread.sleep(200) }
        assertEquals(0, backend.requests("GET /api/coach/conversations/12").size)

        activity.seen(parse(conversation(busy = false)))
        assertEquals(1, activity.finished.value)
        assertFalse(activity.atCapacity.value)
        unwatch()
        assertNull(withTimeoutOrNull { activity.events.first() })
    }

    // AC16: in the background nothing is polled
    @Test
    fun nothingIsPolledInTheBackground() = runTest(dispatcher) {
        backend.on("GET /api/coach/conversations/12", json(conversation(busy = false)))
        foreground.value = false

        activity.track(parse(conversation(busy = true)))
        withContext(Dispatchers.IO) { Thread.sleep(200) }
        assertEquals(0, backend.requests("GET /api/coach/conversations/12").size)

        foreground.value = true
        activity.events.first()
        assertEquals(1, backend.requests("GET /api/coach/conversations/12").size)
    }

    // AC16
    @Test
    fun aDeletedConversationIsForgottenSilently() = runTest(dispatcher) {
        backend.on("GET /api/coach/conversations/12", problem(404, null))

        activity.track(parse(conversation(busy = true)))

        activity.running.first { it.isEmpty() }
        assertEquals(0, activity.finished.value)
    }

    // AC16
    @Test
    fun busyConversationsAreFoundAfterLoginAndClearedOnLogout() = runTest(dispatcher) {
        backend.on("GET /api/riders/3/coach/conversations", json(list(summary(12, status = "BUSY", title = "Build"), summary(13))))
        backend.on("GET /api/coach/conversations/12", json(conversation(busy = true)))
        backend.on("POST /api/auth/logout", noContent())

        loggedIn()

        assertEquals(mapOf(12L to "Build"), activity.running.first { it.isNotEmpty() })
        session.logout()
        activity.running.first { it.isEmpty() }
        assertFalse(activity.atCapacity.value)
    }

    // AC17
    @Test
    fun theCreditsLoadWithTheSessionAndClearOnLogout() = runTest(dispatcher) {
        backend.on("GET /api/account/coach-quota", json(quota(remaining = 3)))
        backend.on("POST /api/auth/logout", noContent())

        loggedIn()
        assertEquals(3, quotas.quota.first { it != null }!!.remaining)

        session.logout()
        assertNull(quotas.quota.first { it == null })
    }

    // AC17
    @Test
    fun theCreditsDecideWhatIsCovered() {
        val quota = parseQuota(quota(remaining = 3, review = 2, block = 5))
        assertTrue(quota.covers(CostKind.REVIEW))
        assertFalse(quota.covers(CostKind.TRAINING_BLOCK))
        assertEquals(2, quota.cost(CostKind.BLOCK_MESSAGE))
        assertTrue(parseQuota(quota(remaining = 0, unlimited = true)).covers(CostKind.TRAINING_BLOCK))
        // Not loaded yet: the backend decides
        assertTrue(null.covers(CostKind.TRAINING_BLOCK))
        assertTrue(parseQuota(quota(remaining = 0)).exhausted)
        // The free first review of a ride goes without credits, but not while another request runs
        assertTrue(CoachLimits(parseQuota(quota(remaining = 0))).allows(CostKind.REVIEW, free = true))
        assertFalse(CoachLimits(parseQuota(quota()), atCapacity = true).allows(CostKind.REVIEW, free = true))
        assertEquals(CostKind.BLOCK_MESSAGE, messageCost("PLAN_ADJUSTMENT"))
        assertEquals(CostKind.REVIEW_MESSAGE, messageCost("FILE_REVIEW"))
        assertEquals(CostKind.TRAINING_BLOCK, startCost("TRAINING_BLOCK"))
        assertEquals(CostKind.REVIEW, startCost("ACTIVITY_REVIEW"))
    }

    private suspend fun <T> withTimeoutOrNull(block: suspend () -> T): T? =
        withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeoutOrNull(300) { block() } }
}
