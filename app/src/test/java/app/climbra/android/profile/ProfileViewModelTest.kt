package app.climbra.android.profile

import app.climbra.android.R
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.intervals.IntervalsSync
import app.climbra.android.intervals.SyncOrigin
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.testing.FakeThemeSettings
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.theme.ThemeMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 004: what the profile requests, sends and shows; the figures are the backend's (cyclingapp spec 053). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val changes = DataChanges()
    private val themes = FakeThemeSettings()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var session: SessionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        session = backend.session(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        backend.close()
    }

    private suspend fun loggedIn(accountJson: String = account()) {
        backend.on("POST /api/auth/login", json(accountJson, 200, SESSION_COOKIE))
        session.login("anna@example.com", "correct-horse-1")
    }

    private fun viewModel() = ProfileViewModel(
        RiderRepository(backend.riderApi) { "Europe/Warsaw" },
        backend.profileApi,
        session,
        changes,
        themes,
        clock,
        backend.quotas(session, scope),
        sync,
    ).apply { zone = { ZoneId.of("Europe/Warsaw") } }

    private val sync by lazy { backend.intervalsSync(session, scope, changes, clock) }

    /** Nothing loading or running (the backend answers on OkHttp's threads, so states are awaited). */
    private suspend fun ProfileViewModel.settled() = state.first { s ->
        !s.loading && !s.refreshing && !s.busy && !s.syncing && !s.languageBusy &&
            !s.riderForm.saving && s.thresholdForm?.saving != true && !s.intervalsForm.busy && !s.passwordForm.saving &&
            s.deleteAccount?.busy != true &&
            listOf(s.thresholds, s.zones, s.intervals, s.intervalsThresholds, s.garmin, s.quota).none { it.loading }
    }

    /**
     * Requests made after an action's result arrive on the server a little later; waits in real time while the test
     * dispatcher keeps running the view model's coroutines.
     */
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

    private val rider = """
        {"id":5,"name":"Anna","weightKg":60.0,"restingHeartRateBpm":48,
         "currentThresholds":{"id":11,"effectiveFrom":"2026-10-20","ftpWatts":270,"lthrBpm":165,"maxHeartRateBpm":185,
           "ftpWattsPerKg":4.5,"resolved":{"ftpWatts":270,"lthrBpm":165,"maxHeartRateBpm":185}},
         "ftpWattsPerKg":4.5,"timeZone":"Europe/Warsaw"}
    """.trimIndent()

    private val thresholds = """
        [{"id":11,"effectiveFrom":"2026-10-20","ftpWatts":270,"lthrBpm":null,"maxHeartRateBpm":null,"ftpWattsPerKg":4.5,
          "resolved":{"ftpWatts":270,"lthrBpm":165,"maxHeartRateBpm":185},"createdAt":"2026-10-20T10:00:00Z"},
         {"id":10,"effectiveFrom":"2026-09-01","ftpWatts":250,"lthrBpm":165,"maxHeartRateBpm":185,"ftpWattsPerKg":4.17,
          "resolved":{"ftpWatts":250,"lthrBpm":165,"maxHeartRateBpm":185},"createdAt":"2026-09-01T10:00:00Z"}]
    """.trimIndent()

    private val zones = """
        {"thresholdsEffectiveFrom":"2026-10-20","ftpWatts":270,"lthrBpm":165,"maxHeartRateBpm":185,
         "powerZones":[{"number":4,"name":"Lactate Threshold","minPercent":91,"maxPercent":105,"min":246,"max":284}],
         "heartRateZoneBasis":"LTHR","heartRateZones":[]}
    """.trimIndent()

    private val notConnected = """{"connected":false}"""
    private val connected = """
        {"connected":true,"athleteId":"i12345","athleteName":"Anna Nowak","keyHint":"x7Qa",
         "connectedAt":"2026-10-26T08:00:00Z","lastSyncAt":"2026-10-26T09:00:00Z"}
    """.trimIndent()
    private val proposal = """{"ftpWatts":280,"indoorFtpWatts":270,"lthrBpm":168,"maxHeartRateBpm":188,"differsFromCurrent":true}"""
    private val garmin = """{"available":true,"status":"CONNECTED","connectedAt":"2026-10-01T08:00:00Z","sent":4,"stale":1}"""
    private val quota = """
        {"plan":"FREE","limit":30,"used":5,"remaining":40,"unlimited":false,
         "monthly":{"limit":30,"used":5,"remaining":25,"resetsAt":"2026-12-01T00:00:00Z"},
         "bonus":[{"remaining":15,"expiresAt":"2027-01-31T00:00:00Z"}],"subscription":null}
    """.trimIndent()

    private fun withProfile(intervals: String = notConnected) {
        backend.on("GET /api/riders", json("[$rider]"))
        backend.on("GET /api/riders/5/thresholds", json(thresholds))
        backend.on("GET /api/riders/5/zones", json(zones))
        backend.on("GET /api/riders/5/intervals", json(intervals))
        backend.on("GET /api/riders/5/intervals/thresholds", json(proposal))
        backend.on("GET /api/riders/5/garmin-connection", json(garmin))
        backend.on("GET /api/account/coach-quota", json(quota))
    }

    private fun count(route: String) = backend.requests(route).size

    private fun body(route: String) = backend.requests(route).last().body?.utf8()

    // AC1
    @Test
    fun theProfileLoadsEachSectionOnce() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        val state = viewModel().settled()

        assertEquals(5L, state.rider?.id)
        assertEquals(listOf(11L, 10L), state.thresholds.value?.map { it.id })
        assertEquals(4.5, state.thresholds.value?.first()?.ftpWattsPerKg)
        assertEquals(246, state.zones.value?.powerZones?.single()?.min)
        assertFalse(state.intervals.value!!.connected)
        assertTrue(state.garmin.value!!.available)
        assertEquals(40, state.quota.value?.remaining)
        assertEquals("anna@example.com", state.account?.email)
        listOf(
            "GET /api/riders/5/thresholds", "GET /api/riders/5/zones", "GET /api/riders/5/intervals",
            "GET /api/riders/5/garmin-connection", "GET /api/account/coach-quota",
        ).forEach { assertEquals(it, 1, count(it)) }
        // Not connected: no proposal
        assertEquals(0, count("GET /api/riders/5/intervals/thresholds"))
        assertEquals(RiderForm(name = "Anna", weight = "60", restingHr = "48"), state.riderForm)
    }

    // AC1
    @Test
    fun aFailingSectionShowsItsErrorWhileTheOthersShowTheirData() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.replace("GET /api/riders/5/zones", problem(500, null), json(zones))
        val viewModel = viewModel()
        val state = viewModel.settled()

        assertTrue(state.zones.failed)
        assertEquals(2, state.thresholds.value?.size)

        viewModel.retry(ProfileSection.ZONES)
        eventually { assertEquals(270, viewModel.state.value.zones.value?.ftpWatts) }
        assertFalse(viewModel.settled().zones.failed)
    }

    // AC1
    @Test
    fun pullToRefreshLoadsEverythingAgain() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.refresh()
        viewModel.settled()

        assertEquals(2, count("GET /api/riders"))
        assertEquals(2, count("GET /api/riders/5/thresholds"))
        assertEquals(2, count("GET /api/account/coach-quota"))
    }

    // AC2
    @Test
    fun savingTheRiderSendsTheFields() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("PUT /api/riders/5", json(rider.replace("\"Anna\"", "\"Anna N.\"")))
        val viewModel = viewModel()
        viewModel.settled()
        val version = changes.version.value

        viewModel.onRiderChange(RiderForm(name = " Anna N. ", weight = "61,5", restingHr = ""))
        viewModel.saveRider()
        val state = viewModel.state.first { !it.riderForm.saving && it.message != null }

        assertEquals("""{"name":"Anna N.","weightKg":61.5}""", body("PUT /api/riders/5"))
        assertEquals("Anna N.", state.rider?.name)
        assertEquals(UiText(R.string.profile_saved), state.message)
        assertEquals(version + 1, changes.version.value)
    }

    // AC2
    @Test
    fun invalidRiderFieldsSendNothing() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        val viewModel = viewModel()
        viewModel.settled()

        for (form in listOf(
            RiderForm(name = "", weight = "60"),
            RiderForm(name = "Anna", weight = "19.9"),
            RiderForm(name = "Anna", weight = "60.123"),
            RiderForm(name = "Anna", weight = "60", restingHr = "130"),
        )) {
            viewModel.onRiderChange(form)
            viewModel.saveRider()
            assertTrue(form.toString(), viewModel.state.value.riderForm.invalid)
        }
        assertEquals(0, count("PUT /api/riders/5"))
    }

    // AC3
    @Test
    fun anAccountWithoutRiderCreatesItWithTheDeviceTimeZone() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.replace("GET /api/riders", json("[]"))
        backend.on("POST /api/riders", json(rider))
        val viewModel = viewModel()
        val empty = viewModel.settled()
        assertTrue(empty.noRider)
        assertEquals(0, count("GET /api/riders/5/thresholds"))

        viewModel.onRiderChange(RiderForm(name = "Anna", weight = "60", restingHr = "48"))
        viewModel.saveRider()
        eventually { assertEquals(2, viewModel.state.value.thresholds.value?.size) }
        val state = viewModel.settled()

        assertEquals(
            """{"name":"Anna","weightKg":60.0,"restingHeartRateBpm":48,"timeZone":"Europe/Warsaw"}""",
            body("POST /api/riders"),
        )
        assertFalse(state.noRider)
        assertEquals(5L, state.rider?.id)
        assertEquals(2, state.thresholds.value?.size)
        assertEquals(UiText(R.string.profile_created), state.message)
    }

    // AC5
    @Test
    fun addingAnEntrySendsTheFormAndShowsTheReanalysedCount() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("POST /api/riders/5/thresholds", json("""{"threshold":{"id":12,"effectiveFrom":"2026-11-12"},"reanalysed":3}""", 201))
        val viewModel = viewModel()
        viewModel.settled()
        val version = changes.version.value

        viewModel.openThresholdForm()
        assertEquals("2026-11-12", viewModel.state.value.thresholdForm?.effectiveFrom)
        viewModel.onThresholdChange(viewModel.state.value.thresholdForm!!.copy(ftp = "280", maxHr = "190"))
        viewModel.saveThreshold()
        viewModel.state.first { it.message != null }
        // History, zones and the rider again
        eventually {
            assertEquals(2, count("GET /api/riders/5/thresholds"))
            assertEquals(2, count("GET /api/riders/5/zones"))
            assertEquals(2, count("GET /api/riders"))
        }
        val state = viewModel.settled()

        assertEquals("""{"effectiveFrom":"2026-11-12","ftpWatts":280,"maxHeartRateBpm":190}""", body("POST /api/riders/5/thresholds"))
        assertNull(state.thresholdForm)
        assertEquals(UiText(R.plurals.profile_thresholds_saved, listOf(3), quantity = 3), state.message)
        assertEquals(version + 1, changes.version.value)
    }

    // AC5
    @Test
    fun editingAnEntrySendsItsValues() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("PUT /api/riders/5/thresholds/10", json("""{"threshold":{"id":10,"effectiveFrom":"2026-09-01"},"reanalysed":0}"""))
        val viewModel = viewModel()
        val loaded = viewModel.settled()

        viewModel.openThresholdForm(loaded.thresholds.value!!.last())
        val form = viewModel.state.value.thresholdForm!!
        assertEquals(ThresholdForm(editingId = 10, effectiveFrom = "2026-09-01", ftp = "250", lthr = "165", maxHr = "185"), form)
        viewModel.onThresholdChange(form.copy(ftp = "255"))
        viewModel.saveThreshold()
        val state = viewModel.state.first { it.message != null }

        assertEquals(
            """{"effectiveFrom":"2026-09-01","ftpWatts":255,"lthrBpm":165,"maxHeartRateBpm":185}""",
            body("PUT /api/riders/5/thresholds/10"),
        )
        assertEquals(UiText(R.plurals.profile_thresholds_saved, listOf(0), quantity = 0), state.message)
    }

    // AC5
    @Test
    fun outOfRangeValuesOrAFutureDaySendNothing() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        val viewModel = viewModel()
        viewModel.settled()
        viewModel.openThresholdForm()
        val form = viewModel.state.value.thresholdForm!!

        for (invalid in listOf(
            form.copy(ftp = "29"), form.copy(ftp = "2501"), form.copy(lthr = "59"), form.copy(maxHr = "241"),
            form.copy(effectiveFrom = "2026-11-13"),
        )) {
            viewModel.onThresholdChange(invalid)
            viewModel.saveThreshold()
            assertTrue(invalid.toString(), viewModel.state.value.thresholdForm!!.invalid)
        }
        assertEquals(0, count("POST /api/riders/5/thresholds"))
    }

    // AC5
    @Test
    fun aBackendErrorStaysInTheForm() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("POST /api/riders/5/thresholds", problem(409, null))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.openThresholdForm()
        viewModel.onThresholdChange(viewModel.state.value.thresholdForm!!.copy(ftp = "280"))
        viewModel.saveThreshold()
        val state = viewModel.state.first { it.thresholdError != null }

        assertEquals(UiText(R.string.profile_threshold_conflict), state.thresholdError)
        assertFalse(state.thresholdForm!!.saving)
    }

    // AC5
    @Test
    fun deletingAnEntryAsksFirst() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("DELETE /api/riders/5/thresholds/10", json("""{"threshold":null,"reanalysed":2}"""))
        val viewModel = viewModel()
        val loaded = viewModel.settled()
        val entry = loaded.thresholds.value!!.last()

        viewModel.askDeleteThreshold(entry)
        assertEquals(Confirmation.DeleteThreshold(entry), viewModel.state.value.confirmation)
        viewModel.dismissConfirmation()
        assertEquals(0, count("DELETE /api/riders/5/thresholds/10"))

        viewModel.askDeleteThreshold(entry)
        viewModel.confirm()
        val state = viewModel.state.first { it.message != null }

        assertEquals(1, count("DELETE /api/riders/5/thresholds/10"))
        assertEquals(UiText(R.plurals.profile_threshold_deleted, listOf(2), quantity = 2), state.message)
        eventually { assertEquals(2, count("GET /api/riders/5/thresholds")) }
    }

    // AC7
    @Test
    fun connectingIntervalsSendsTheIdAndTheKey() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("PUT /api/riders/5/intervals", problem(422, null), problem(503, null), json(connected))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.onIntervalsChange(IntervalsForm(athleteId = " i12345 ", apiKey = " secret "))
        viewModel.connectIntervals()
        assertEquals(UiText(R.string.intervals_rejected), viewModel.state.first { it.intervalsError != null }.intervalsError)
        viewModel.connectIntervals()
        val notConfigured = UiText(R.string.intervals_not_configured)
        assertEquals(notConfigured, viewModel.state.first { it.intervalsError == notConfigured }.intervalsError)
        assertFalse(viewModel.state.value.intervals.value!!.connected)
        viewModel.connectIntervals()
        viewModel.state.first { it.message != null }
        eventually { assertEquals(280, viewModel.state.value.intervalsThresholds.value?.ftpWatts) }
        val state = viewModel.settled()

        assertEquals("""{"athleteId":"i12345","apiKey":"secret"}""", body("PUT /api/riders/5/intervals"))
        assertTrue(state.intervals.value!!.connected)
        assertEquals(IntervalsForm(), state.intervalsForm)
        assertEquals(UiText(R.string.intervals_connected, listOf("Anna Nowak")), state.message)
        // AC8: the proposal of the new connection
        assertEquals(280, state.intervalsThresholds.value?.ftpWatts)
    }

    // AC7; spec 007 AC3: "Sync now" runs the app's one sync, whose messages go to the shell (IntervalsSyncTest)
    @Test
    fun aSyncIsFollowedUntilItsJobEnds() = runTest(dispatcher) {
        loggedIn()
        withProfile(intervals = connected)
        backend.on("POST /api/riders/5/intervals/sync", json("""{"id":9,"status":"QUEUED"}""", 202))
        backend.on(
            "GET /api/imports/9",
            json("""{"id":9,"status":"RUNNING"}"""),
            json("""{"id":9,"status":"COMPLETED","imported":3,"unavailable":0}"""),
        )
        val viewModel = viewModel()
        viewModel.settled()
        val version = changes.version.value
        val started = testScheduler.currentTime

        viewModel.syncIntervals()
        viewModel.state.first { it.syncing }
        val state = viewModel.state.first { !it.syncing }

        // Polled every 2 s until the job ended
        assertEquals(2, count("GET /api/imports/9"))
        assertTrue(testScheduler.currentTime - started >= 2 * IntervalsSync.POLL_MILLIS)
        assertFalse(state.syncing)
        assertEquals(version + 1, changes.version.value)
        eventually { assertEquals(2, count("GET /api/riders/5/intervals")) }
    }

    // Spec 007 AC3: a sync started elsewhere (a pull on the dashboard) shows on "Sync now"
    @Test
    fun aSyncStartedElsewhereDisablesSyncNow() = runTest(dispatcher) {
        loggedIn()
        withProfile(intervals = connected)
        backend.on("POST /api/riders/5/intervals/sync", json("""{"id":9,"status":"QUEUED"}""", 202))
        backend.on("GET /api/imports/9", json("""{"id":9,"status":"RUNNING"}"""))
        val viewModel = viewModel()
        viewModel.settled()

        sync.sync(5, SyncOrigin.PULL)

        viewModel.state.first { it.syncing }
        viewModel.syncIntervals()
        eventually { assertEquals(1, count("GET /api/imports/9").coerceAtMost(1)) }
        assertEquals(1, count("POST /api/riders/5/intervals/sync"))
    }

    // Spec 007 AC10: connecting tells the app's sync, so a pull syncs without reading the status first
    @Test
    fun connectingTellsTheSync() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        val viewModel = viewModel()
        viewModel.settled()
        assertEquals(false, sync.state.value.connected)

        backend.on("PUT /api/riders/5/intervals", json(connected))
        viewModel.onIntervalsChange(IntervalsForm(apiKey = "secret"))
        viewModel.connectIntervals()
        viewModel.settled()

        assertEquals(true, sync.state.value.connected)
    }

    // AC7
    @Test
    fun disconnectingIntervalsAsksFirst() = runTest(dispatcher) {
        loggedIn()
        withProfile(intervals = connected)
        backend.on("DELETE /api/riders/5/intervals", noContent())
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.askDisconnectIntervals()
        assertEquals(0, count("DELETE /api/riders/5/intervals"))
        viewModel.confirm()
        val state = viewModel.state.first { it.message != null }

        assertEquals(1, count("DELETE /api/riders/5/intervals"))
        assertFalse(state.intervals.value!!.connected)
        assertNull(state.intervalsThresholds.value)
        assertEquals(UiText(R.string.intervals_disconnected), state.message)
    }

    // AC8
    @Test
    fun theProposalPrefillsANewEntryFromToday() = runTest(dispatcher) {
        loggedIn()
        withProfile(intervals = connected)
        val viewModel = viewModel()
        val state = viewModel.settled()

        viewModel.proposeThresholds(state.intervalsThresholds.value!!)

        assertEquals(
            ThresholdForm(effectiveFrom = "2026-11-12", ftp = "280", lthr = "168", maxHr = "188"),
            viewModel.state.value.thresholdForm,
        )
        assertEquals(0, count("POST /api/riders/5/thresholds"))
    }

    // AC9
    @Test
    fun disconnectingGarminAsksFirstAndShowsTheNewStatus() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("DELETE /api/riders/5/garmin-connection", noContent())
        val viewModel = viewModel()
        viewModel.settled()
        backend.replace("GET /api/riders/5/garmin-connection", json("""{"available":true,"status":"NOT_CONNECTED"}"""))

        viewModel.askDisconnectGarmin()
        viewModel.confirm()
        assertEquals(UiText(R.string.garmin_disconnected), viewModel.state.first { it.message != null }.message)

        assertEquals(1, count("DELETE /api/riders/5/garmin-connection"))
        eventually { assertEquals(GarminStatus.NOT_CONNECTED, viewModel.state.value.garmin.value?.status) }
    }

    // AC9: connecting happens on the web; back in the app the status is read again
    @Test
    fun returningToTheAppReloadsTheGarminStatus() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.onResumed()

        eventually { assertEquals(2, count("GET /api/riders/5/garmin-connection")) }
    }

    // AC10
    @Test
    fun theLanguageIsStoredOnTheAccount() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("PUT /api/account/language", noContent())
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.setLanguage("en")
        val state = viewModel.state.first { it.account?.language == "en" && !it.languageBusy }

        assertEquals("""{"language":"en"}""", body("PUT /api/account/language"))
        assertEquals("account:en", backend.locales.calls.last())
        assertNull(state.message)
    }

    // AC10
    @Test
    fun aFailedLanguageChangeKeepsTheOldOne() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("PUT /api/account/language", problem(500, null))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.setLanguage("en")
        val state = viewModel.state.first { it.message != null }

        assertEquals("pl", state.account?.language)
        assertEquals("account:pl", backend.locales.calls.last())
    }

    // AC11
    @Test
    fun theThemeChoiceIsStored() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.setTheme(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, viewModel.state.first { it.theme == ThemeMode.DARK }.theme)
        assertEquals(ThemeMode.DARK, themes.mode.value)
    }

    // AC13
    @Test
    fun aPasswordChangeValidatesLikeTheBackend() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("PUT /api/account/password", problem(400, "WRONG_PASSWORD"), noContent())
        val viewModel = viewModel()
        viewModel.settled()

        for (form in listOf(
            PasswordForm(current = "", password = "battery-staple-2", repeat = "battery-staple-2"),
            PasswordForm(current = "old", password = "short", repeat = "short"),
            PasswordForm(current = "old", password = "battery-staple-2", repeat = "battery-staple-3"),
            PasswordForm(current = "old", password = "anna@example.com", repeat = "anna@example.com"),
            PasswordForm(current = "old", password = "ż".repeat(37), repeat = "ż".repeat(37)),
        )) {
            viewModel.onPasswordChange(form)
            viewModel.changePassword()
            assertTrue(form.toString(), viewModel.state.value.passwordForm.showErrors)
        }
        assertEquals(0, count("PUT /api/account/password"))

        viewModel.onPasswordChange(PasswordForm(current = "old", password = "battery-staple-2", repeat = "battery-staple-2"))
        viewModel.changePassword()
        assertEquals(UiText(R.string.api_error_wrong_password), viewModel.state.first { it.passwordError != null }.passwordError)

        viewModel.changePassword()
        val state = viewModel.state.first { it.message != null }
        assertEquals(PasswordForm(), state.passwordForm)
        assertEquals(UiText(R.string.password_changed), state.message)
    }

    // AC14
    @Test
    fun deletingTheAccountWithThePasswordEndsTheSession() = runTest(dispatcher) {
        loggedIn()
        withProfile()
        backend.on("DELETE /api/account", problem(400, "DELETE_CONFIRMATION_INVALID"), noContent())
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.openDeleteAccount()
        viewModel.onDeleteAccountChange("wrong")
        viewModel.deleteAccount()
        val failed = viewModel.state.first { it.deleteAccount?.error != null }
        assertEquals(UiText(R.string.api_error_delete_confirmation_invalid), failed.deleteAccount?.error)

        viewModel.onDeleteAccountChange("correct-horse-1")
        viewModel.deleteAccount()
        session.state.first { it is SessionState.LoggedOut }

        assertEquals("""{"password":"correct-horse-1"}""", body("DELETE /api/account"))
        assertEquals(SessionState.LoggedOut(accountDeleted = true), session.state.value)
    }

    // AC14
    @Test
    fun anAccountWithoutPasswordConfirmsWithTheEmail() = runTest(dispatcher) {
        loggedIn(account(hasPassword = false))
        withProfile()
        backend.on("DELETE /api/account", noContent())
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.openDeleteAccount()
        viewModel.onDeleteAccountChange(" anna@example.com ")
        viewModel.deleteAccount()
        session.state.first { it is SessionState.LoggedOut }

        assertEquals("""{"email":"anna@example.com"}""", body("DELETE /api/account"))
    }
}
