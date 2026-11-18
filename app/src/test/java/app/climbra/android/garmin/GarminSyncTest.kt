package app.climbra.android.garmin

import app.climbra.android.R
import app.climbra.android.intervals.SyncOrigin
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.common.DateArg
import app.climbra.android.ui.common.UiText
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 011 AC1 - AC4, AC6: the admin's Garmin sync. */
@OptIn(ExperimentalCoroutinesApi::class)
class GarminSyncTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val changes = DataChanges()
    private val clock = MovableClock()
    private val session: SessionRepository = backend.session(scope)
    private val garmin = backend.garminSync(session, scope, changes, clock)
    private val messages = mutableListOf<UiText>()

    private class MovableClock(var now: Instant = Instant.parse("2026-11-18T10:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    init {
        scope.launch { garmin.messages.toList(messages) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        backend.close()
    }

    private val route = "POST /api/riders/5/garmin/sync"

    private fun count() = backend.requests(route).size

    private suspend fun loggedIn(role: String) {
        backend.on("POST /api/auth/login", json(account(role = role), 200, SESSION_COOKIE))
        session.login("anna@example.com", "correct-horse-1")
    }

    private fun result(imported: Int) = json("""{"since":"2026-11-10","fetched":${imported + 2},"imported":$imported,"other":1}""")

    // AC1: only an admin
    @Test
    fun otherAccountsSendNothing() = runTest(dispatcher) {
        loggedIn("USER")
        assertFalse(garmin.allowed.first { !it })

        garmin.sync(5, SyncOrigin.BUTTON)
        garmin.join()

        assertEquals(0, count())
        assertTrue(messages.isEmpty())
    }

    // AC2: new activities change the data and are told
    @Test
    fun newActivitiesAreToldAndLoad() = runTest(dispatcher) {
        loggedIn("ADMIN")
        assertTrue(garmin.allowed.first { it })
        backend.on(route, result(imported = 3))
        val version = changes.version.value

        garmin.sync(5, SyncOrigin.BUTTON)
        assertTrue(garmin.running.value)
        // A second tap while it runs sends nothing more
        garmin.sync(5, SyncOrigin.BUTTON)
        garmin.join()

        assertEquals(1, count())
        assertFalse(garmin.running.value)
        assertEquals(version + 1, changes.version.value)
        assertEquals(1, garmin.imported.value)
        assertEquals(listOf(UiText(R.plurals.garmin_sync_imported, listOf(3), quantity = 3)), messages)
    }

    // AC2: nothing new since the date
    @Test
    fun nothingNewIsToldWithTheDate() = runTest(dispatcher) {
        loggedIn("ADMIN")
        backend.on(route, result(imported = 0))
        val version = changes.version.value

        garmin.sync(5, SyncOrigin.BUTTON)
        garmin.join()

        assertEquals(version, changes.version.value)
        assertEquals(listOf(UiText(R.string.garmin_sync_nothing_new, listOf(DateArg("2026-11-10")))), messages)
    }

    // AC3
    @Test
    fun aFailureIsTold() = runTest(dispatcher) {
        loggedIn("ADMIN")
        backend.on(route, problem(502, null))

        garmin.sync(5, SyncOrigin.BUTTON)
        garmin.join()

        assertEquals(1, messages.size)
        assertEquals(R.string.garmin_sync_failed, messages.single().res)
        assertEquals(listOf(UiText(R.string.error_request_failed, listOf(502))), messages.single().args)
    }

    // AC4: a pull is quiet when nothing came, throttled to once per 60 s; the button is not
    @Test
    fun aPullIsQuietAndThrottled() = runTest(dispatcher) {
        loggedIn("ADMIN")
        backend.on(route, result(imported = 0))

        garmin.sync(5, SyncOrigin.PULL)
        garmin.join()
        assertEquals(1, count())
        assertTrue(messages.isEmpty())

        clock.now = clock.now.plusSeconds(30)
        garmin.sync(5, SyncOrigin.PULL)
        garmin.join()
        assertEquals(1, count())

        garmin.sync(5, SyncOrigin.BUTTON)
        garmin.join()
        assertEquals(2, count())

        clock.now = clock.now.plusSeconds(61)
        garmin.sync(5, SyncOrigin.PULL)
        garmin.join()
        assertEquals(3, count())
    }

    // AC4: a refusal after a pull is told; a network error is not (the screen's reload tells it)
    @Test
    fun afterAPullOnlyTheBackendsRefusalIsTold() = runTest(dispatcher) {
        loggedIn("ADMIN")
        backend.on(route, problem(403, null))

        garmin.sync(5, SyncOrigin.PULL)
        garmin.join()

        assertEquals(listOf(UiText(R.string.error_request_failed, listOf(403))), messages.single().args)
        backend.server.close()
        clock.now = clock.now.plusSeconds(61)
        garmin.sync(5, SyncOrigin.PULL)
        garmin.join()
        assertEquals(1, messages.size)
    }

    // AC6: logout forgets the sync
    @Test
    fun logoutForgetsTheSync() = runTest(dispatcher) {
        loggedIn("ADMIN")
        backend.on(route, result(imported = 0))
        garmin.sync(5, SyncOrigin.PULL)
        garmin.join()
        backend.on("POST /api/auth/logout", noContent())

        session.logout()

        assertFalse(garmin.allowed.first { !it })
        // The throttle is forgotten too: the next admin's pull syncs at once
        loggedIn("ADMIN")
        garmin.sync(5, SyncOrigin.PULL)
        garmin.join()
        assertEquals(2, count())
    }

}
