package app.climbra.android.garmin

import app.climbra.android.R
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.session.SessionRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.common.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 016 AC1 - AC4: connecting Garmin Connect from the app against the backend. */
@OptIn(ExperimentalCoroutinesApi::class)
class GarminConnectFlowTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val session: SessionRepository = backend.session(scope)
    private val flow = backend.garminConnect(session, scope)
    private val messages = mutableListOf<UiText>()
    private val openings = mutableListOf<String>()

    private val authorize = "POST /api/riders/5/garmin-connection/authorization"
    private val complete = "POST /api/garmin-connection/authorization/complete"

    init {
        scope.launch { flow.messages.toList(messages) }
        scope.launch { flow.openings.toList(openings) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        backend.close()
    }

    private suspend fun loggedIn() {
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        session.login("anna@example.com", "correct-horse-1")
    }

    /** The backend answers on OkHttp's threads. */
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

    // AC1
    @Test
    fun theConsentDialogOpensWithTheStatusAndCloses() = runTest(dispatcher) {
        loggedIn()

        flow.ask(5, GarminStatus.NO_PERMISSION)
        assertEquals(GarminConsent(5, GarminStatus.NO_PERMISSION), flow.consent.value)

        flow.dismiss()
        assertNull(flow.consent.value)
    }

    // AC2
    @Test
    fun continueSendsTheConsentForTheAppAndOpensGarminsPage() = runTest(dispatcher) {
        loggedIn()
        backend.on(authorize, json("""{"authorizationUrl":"https://connect.garmin.com/oauth2Confirm?state=s1"}"""))
        flow.ask(5)

        flow.continueToGarmin()

        eventually { assertEquals(listOf("https://connect.garmin.com/oauth2Confirm?state=s1"), openings) }
        assertNull(flow.consent.value)
        assertEquals("""{"consent":true,"returnTo":"APP"}""", backend.requests(authorize).single().body?.utf8())
    }

    // AC2
    @Test
    fun garminNotAvailableShowsTheErrorInTheDialog() = runTest(dispatcher) {
        loggedIn()
        backend.on(authorize, problem(503, "GARMIN_NOT_CONFIGURED"))
        flow.ask(5)

        flow.continueToGarmin()

        eventually { assertEquals(UiText(R.string.api_error_garmin_not_configured), flow.consent.value?.error) }
        assertEquals(false, flow.consent.value?.starting)
        assertTrue(openings.isEmpty())
    }

    // AC2
    @Test
    fun alreadyConnectedClosesTheDialogAndReloadsTheStatus() = runTest(dispatcher) {
        loggedIn()
        backend.on(authorize, problem(409, "GARMIN_ALREADY_CONNECTED"))
        flow.ask(5)

        flow.continueToGarmin()

        eventually { assertEquals(1, flow.completed.value) }
        assertNull(flow.consent.value)
    }

    // AC3
    @Test
    fun theReturnCompletesWithItsStateAndTellsTheOutcome() = runTest(dispatcher) {
        loggedIn()
        backend.on(complete, json("""{"outcome":"connected"}"""), json("""{"outcome":"in-use"}"""), json("""{"outcome":"new"}"""))

        flow.returned("state-1")
        eventually { assertEquals(listOf(UiText(R.string.garmin_outcome_connected)), messages) }
        assertEquals("""{"state":"state-1"}""", backend.requests(complete).single().body?.utf8())
        assertEquals(1, flow.completed.value)

        flow.returned("state-2")
        flow.returned("state-3")
        eventually { assertEquals(3, messages.size) }
        assertEquals(UiText(R.string.garmin_outcome_in_use), messages[1])
        // An outcome the app does not know
        assertEquals(UiText(R.string.garmin_outcome_failed), messages[2])
    }

    // AC4
    @Test
    fun aReturnWhileLoggedOutCompletesAfterTheNextLogin() = runTest(dispatcher) {
        backend.on("GET /api/auth/me", problem(401, "UNAUTHENTICATED"))
        session.start()
        backend.on(complete, json("""{"outcome":"connected"}"""))

        flow.returned("state-1")
        withContext(Dispatchers.IO) { Thread.sleep(100) }
        assertTrue(backend.requests(complete).isEmpty())

        loggedIn()

        eventually { assertEquals(listOf(UiText(R.string.garmin_outcome_connected)), messages) }
        assertEquals(1, backend.requests(complete).size)
    }
}
