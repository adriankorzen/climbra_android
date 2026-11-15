package app.climbra.android.intervals

import app.climbra.android.R
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.common.UiText
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Spec 007: one intervals.icu sync for the app, started by a pull or "Sync now". */
@OptIn(ExperimentalCoroutinesApi::class)
class IntervalsSyncTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val changes = DataChanges()
    private val clock = MovableClock()
    private val session: SessionRepository = backend.session(scope)
    private val sync = backend.intervalsSync(session, scope, changes, clock)
    private val messages = mutableListOf<UiText>()

    private class MovableClock(var now: Instant = Instant.parse("2026-11-15T10:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    init {
        scope.launch { sync.messages.toList(messages) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        backend.close()
    }

    private val connected = """{"connected":true,"athleteId":"i1","athleteName":"Anna"}"""

    private fun count(route: String) = backend.requests(route).size

    private fun job(status: String, imported: Int = 0, unavailable: Int = 0, processed: Int = 0, total: Int? = null) =
        json("""{"id":9,"status":"$status","imported":$imported,"unavailable":$unavailable,"processedFiles":$processed,"selectedRides":$total}""", 202)

    private suspend fun ended() = sync.join()

    // AC1, AC2: without a connection a pull sends no sync request
    @Test
    fun aPullWithoutAConnectionDoesNotSync() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json("""{"connected":false}"""))

        sync.sync(5, SyncOrigin.PULL)
        ended()

        assertEquals(1, count("GET /api/riders/5/intervals"))
        assertEquals(0, count("POST /api/riders/5/intervals/sync"))
        assertEquals(false, sync.state.value.connected)
        assertTrue(messages.isEmpty())
    }

    // AC3: the progress of the running job
    @Test
    fun aRunningSyncShowsItsProgress() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json(connected))
        backend.on("POST /api/riders/5/intervals/sync", job("QUEUED"))
        backend.on("GET /api/imports/9", job("RUNNING", processed = 2, total = 5))

        sync.sync(5, SyncOrigin.PULL)
        val running = sync.state.first { it.total != null }

        assertTrue(running.running)
        assertEquals(2, running.processed)
        assertEquals(5, running.total)
    }

    // AC1, AC6: a connected pull syncs and tells the result when rides came
    @Test
    fun aPullSyncsAndTellsTheImportedRides() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json(connected))
        backend.on("POST /api/riders/5/intervals/sync", job("QUEUED"))
        backend.on("GET /api/imports/9", job("RUNNING", processed = 2, total = 5), job("COMPLETED", imported = 3, processed = 5, total = 5))
        val version = changes.version.value
        val imported = sync.imported.value

        sync.sync(5, SyncOrigin.PULL)
        ended()
        assertFalse(sync.state.value.running)

        assertEquals(listOf(UiText(R.string.intervals_sync_done, listOf(3))), messages)
        assertEquals(version + 1, changes.version.value)
        assertEquals(imported + 1, sync.imported.value)
    }

    // AC7: nothing new after a pull is quiet; "Sync now" tells it, and that it started
    @Test
    fun nothingNewIsQuietOnlyAfterAPull() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json(connected))
        backend.on("POST /api/riders/5/intervals/sync", job("COMPLETED"))
        val version = changes.version.value

        sync.sync(5, SyncOrigin.PULL)
        ended()
        assertTrue(messages.isEmpty())
        assertEquals(version, changes.version.value)

        sync.sync(5, SyncOrigin.BUTTON)
        ended()
        assertEquals(listOf(UiText(R.string.intervals_sync_started), UiText(R.string.intervals_sync_done, listOf(0))), messages)
    }

    // AC4: one sync at a time
    @Test
    fun onlyOneSyncRuns() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json(connected))
        backend.on("POST /api/riders/5/intervals/sync", job("QUEUED"))
        backend.on("GET /api/imports/9", job("RUNNING"))

        sync.sync(5, SyncOrigin.PULL)
        sync.state.first { it.running }
        sync.sync(5, SyncOrigin.PULL)
        sync.sync(5, SyncOrigin.BUTTON)
        // Polled at least once: the start is done
        while (count("GET /api/imports/9") == 0) withContext(Dispatchers.IO) { Thread.sleep(10) }

        assertEquals(1, count("POST /api/riders/5/intervals/sync"))
    }

    // AC5: a pull within 60 s after a sync ended only reloads; "Sync now" is not throttled
    @Test
    fun aPullSoonAfterASyncDoesNotSyncAgain() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json(connected))
        backend.on("POST /api/riders/5/intervals/sync", job("COMPLETED"))

        sync.sync(5, SyncOrigin.PULL)
        ended()
        clock.now = clock.now.plusSeconds(59)
        sync.sync(5, SyncOrigin.PULL)
        ended()
        assertEquals(1, count("POST /api/riders/5/intervals/sync"))

        sync.sync(5, SyncOrigin.BUTTON)
        ended()
        assertEquals(2, count("POST /api/riders/5/intervals/sync"))

        clock.now = clock.now.plusSeconds(61)
        sync.sync(5, SyncOrigin.PULL)
        ended()
        assertEquals(3, count("POST /api/riders/5/intervals/sync"))
        // The connection was read once and kept
        assertEquals(1, count("GET /api/riders/5/intervals"))
    }

    // AC8: a failed job
    @Test
    fun aFailedJobTellsItsMessage() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json(connected))
        backend.on("POST /api/riders/5/intervals/sync", json("""{"id":9,"status":"FAILED","message":"Rate limit"}""", 202))

        sync.sync(5, SyncOrigin.PULL)
        ended()

        assertEquals(listOf(UiText(R.string.intervals_sync_failed, listOf("Rate limit"))), messages)
        assertEquals(0, count("GET /api/imports/9"))
    }

    // AC8, AC10: a refused start tells why and reads the connection again on the next pull
    @Test
    fun aRefusedStartTellsWhyAndReadsTheConnectionAgain() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json(connected))
        backend.on("POST /api/riders/5/intervals/sync", problem(409, null), problem(503, null))

        sync.sync(5, SyncOrigin.PULL)
        ended()
        clock.now = clock.now.plusSeconds(61)
        sync.sync(5, SyncOrigin.PULL)
        ended()

        assertEquals(listOf(UiText(R.string.intervals_sync_conflict), UiText(R.string.intervals_not_configured)), messages)
        assertEquals(2, count("GET /api/riders/5/intervals"))
    }

    // AC8: a failed poll ends the tracking
    @Test
    fun aFailedPollEndsTheTracking() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json(connected))
        backend.on("POST /api/riders/5/intervals/sync", job("QUEUED"))
        backend.on("GET /api/imports/9", problem(500, null))

        sync.sync(5, SyncOrigin.PULL)
        ended()

        assertEquals(listOf(UiText(R.string.error_request_failed, listOf(500))), messages)
        assertEquals(1, count("GET /api/imports/9"))
    }

    // AC9: the sync is followed in the app scope and forgotten on logout
    @Test
    fun aLogoutStopsTheSync() = runTest(dispatcher) {
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        session.login("anna@example.com", "correct-horse-1")
        backend.on("GET /api/riders/5/intervals", json(connected))
        backend.on("POST /api/riders/5/intervals/sync", job("QUEUED"))
        backend.on("GET /api/imports/9", job("RUNNING"))
        sync.sync(5, SyncOrigin.PULL)
        sync.state.first { it.running }

        backend.on("POST /api/auth/logout", noContent())
        session.logout()

        val state = sync.state.first { !it.running }
        assertFalse(state.running)
        assertEquals(null, state.connected)
        val polls = count("GET /api/imports/9")
        testScheduler.advanceTimeBy(10 * IntervalsSync.POLL_MILLIS)
        assertEquals(polls, count("GET /api/imports/9"))
    }

    // AC10: a pull while not connected reads the status again, so a connection made on the web is found
    @Test
    fun aPullWhileNotConnectedReadsTheStatusAgain() = runTest(dispatcher) {
        backend.on("GET /api/riders/5/intervals", json("""{"connected":false}"""), json(connected))
        backend.on("POST /api/riders/5/intervals/sync", job("COMPLETED"))

        sync.sync(5, SyncOrigin.PULL)
        ended()
        sync.sync(5, SyncOrigin.PULL)
        ended()

        assertEquals(2, count("GET /api/riders/5/intervals"))
        assertEquals(1, count("POST /api/riders/5/intervals/sync"))
    }
}
