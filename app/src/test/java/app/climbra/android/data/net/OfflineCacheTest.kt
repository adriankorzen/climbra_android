package app.climbra.android.data.net

import app.climbra.android.session.SessionState
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.testing.XorCipher
import java.io.IOException
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Spec 018: the backend's answers saved on the device and served when it cannot be reached. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineCacheTest {

    private val backend = TestBackend()

    @After
    fun tearDown() = backend.close()

    private val load = """{"days":[],"weeks":[{"tss":412}]}"""

    private fun get(path: String): Response =
        backend.client.newCall(Request.Builder().url(backend.server.url(path)).build()).execute()

    private fun saved(path: String) = backend.offlineCache.read(backend.server.url(path))

    // AC1
    @Test
    fun aSuccessfulListedGetIsSavedEncryptedAndNothingElseIs() {
        backend.on("GET /api/riders/3/training-load", json(load))
        backend.on("GET /api/riders/3/thresholds", json("[]"))
        backend.on("GET /api/riders/3/rides", problem(500, null))
        backend.on("POST /api/activities/5/reanalyze", json("{}"))

        get("/api/riders/3/training-load?days=365&weeks=12").use { assertEquals(load, it.body.string()) }
        get("/api/riders/3/thresholds").close()
        get("/api/riders/3/rides?page=0").close()
        backend.client.newCall(
            Request.Builder().url(backend.server.url("/api/activities/5/reanalyze")).post("{}".toRequestBody("application/json".toMediaType())).build(),
        ).execute().close()

        assertEquals(load, saved("/api/riders/3/training-load?days=365&weeks=12")?.body?.decodeToString())
        // Each query is a request of its own
        assertNull(saved("/api/riders/3/training-load?days=90&weeks=12"))
        assertNull(saved("/api/riders/3/thresholds"))
        assertNull(saved("/api/riders/3/rides?page=0"))
        assertNull(saved("/api/activities/5/reanalyze"))
        val files = backend.offlineDir.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertFalse(files.single().readBytes().decodeToString().contains("tss"))
    }

    // AC2
    @Test
    fun withoutTheBackendASavedRequestReturnsItsSavedAnswerAndTheAppIsOffline() {
        backend.on("GET /api/activities/5/analysis", json("""{"id":5}"""))
        get("/api/activities/5/analysis").close()
        val savedAt = saved("/api/activities/5/analysis")!!.savedAt

        backend.goOffline()

        get("/api/activities/5/analysis").use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"id":5}""", response.body.string())
            assertEquals(savedAt.toString(), response.header(OfflineInterceptor.SAVED_AT))
        }
        assertEquals(OfflineState(savedAt), backend.connectivity.offline.value)
        // A request never saved fails as before
        try {
            get("/api/activities/6/analysis")
            fail("A request never saved has no answer")
        } catch (expected: IOException) {
        }
    }

    // AC2
    @Test
    fun anHttpErrorOfTheBackendIsNotReplacedBySavedData() {
        backend.on("GET /api/riders/3/calendar", json("""{"weeks":[]}"""), problem(503, null))
        get("/api/riders/3/calendar?from=a&to=b").close()

        get("/api/riders/3/calendar?from=a&to=b").use { assertEquals(503, it.code) }
        assertNull(backend.connectivity.offline.value)
    }

    // AC2, AC8: a 401 still ends the session, and what was saved goes with it
    @Test
    fun a401EndsTheSessionAndDeletesTheSavedAnswers() = runTest(UnconfinedTestDispatcher()) {
        val session = backend.session(backgroundScope)
        backend.on("GET /api/auth/me", json(account()))
        backend.on("GET /api/riders", json("[]"), problem(401, "UNAUTHENTICATED"))
        session.start()
        get("/api/riders").close()
        assertNotNull(saved("/api/riders"))

        get("/api/riders").close()

        assertEquals(SessionState.LoggedOut(expired = true), session.state.first { it is SessionState.LoggedOut })
        assertTrue(backend.offlineDir.listFiles().orEmpty().isEmpty())
    }

    // AC3
    @Test
    fun startingWithoutTheBackendOpensTheShellWithTheSavedAccount() = runTest(UnconfinedTestDispatcher()) {
        val session = backend.session(backgroundScope)
        backend.on("GET /api/auth/me", json(account()))
        session.start()

        backend.goOffline()
        session.start()

        assertEquals("anna@example.com", (session.state.value as SessionState.LoggedIn).account.email)
        assertNotNull(backend.connectivity.offline.value)
    }

    // AC3
    @Test
    fun startingWithoutTheBackendAndNoSavedAccountIsUnavailable() = runTest(UnconfinedTestDispatcher()) {
        val session = backend.session(backgroundScope)
        backend.goOffline()

        session.start()

        assertTrue(session.state.value is SessionState.Unavailable)
    }

    // AC8
    @Test
    fun logoutAccountDeletionAndALoginDeleteTheSavedAnswers() = runTest(UnconfinedTestDispatcher()) {
        val session = backend.session(backgroundScope)
        val url = backend.server.url("/api/riders")
        backend.on("POST /api/auth/logout", noContent())
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        backend.on("DELETE /api/account", noContent())

        backend.offlineCache.save(url, "[]".encodeToByteArray(), "application/json")
        session.logout()
        assertNull(backend.offlineCache.read(url))

        backend.offlineCache.save(url, "[]".encodeToByteArray(), "application/json")
        assertNull(session.login("anna@example.com", "secret"))
        assertNull(backend.offlineCache.read(url))

        backend.offlineCache.save(url, "[]".encodeToByteArray(), "application/json")
        session.deleteAccount("secret", null)
        assertNull(backend.offlineCache.read(url))
    }

    // AC8
    @Test
    fun aFileThatCannotBeReadCountsAsNotSaved() {
        val url = backend.server.url("/api/riders")
        backend.offlineCache.save(url, "[]".encodeToByteArray(), "application/json")
        val file = backend.offlineDir.listFiles()!!.single()
        file.writeBytes(byteArrayOf(1, 2, 3))

        assertNull(backend.offlineCache.read(url))
        assertFalse(file.exists())
    }

    // AC7
    @Test
    fun pastTheLimitTheAnswersReadLeastRecentlyGo() {
        var clock = Instant.parse("2026-11-20T10:00:00Z")
        val cache = OfflineCache(createTempDirectory("limit").toFile(), XorCipher, maxBytes = 2_500, now = { clock.also { clock = it.plusSeconds(1) } })
        val body = ByteArray(1_000) { 'x'.code.toByte() }
        val first = "https://climbra.app/api/activities/1/recording".toHttpUrl()
        val second = "https://climbra.app/api/activities/2/recording".toHttpUrl()
        val third = "https://climbra.app/api/activities/3/recording".toHttpUrl()

        cache.save(first, body, null)
        cache.save(second, body, null)
        assertNotNull(cache.read(first))
        cache.save(third, body, null)

        assertNotNull(cache.read(first))
        assertNull(cache.read(second))
        assertNotNull(cache.read(third))
    }

    // AC5
    @Test
    fun theOldestServedTimeIsShownAndAnAnswerOfTheBackendEndsOfflineAndReloads() = runTest(UnconfinedTestDispatcher()) {
        val connectivity = Connectivity()
        val reloads = mutableListOf<Unit>()
        backgroundScope.launch { connectivity.reloads.collect { reloads += it } }

        connectivity.retry()
        assertTrue("Online, a retry reloads nothing", reloads.isEmpty())

        connectivity.servedSaved(Instant.parse("2026-11-20T09:00:00Z"))
        connectivity.servedSaved(Instant.parse("2026-11-19T18:00:00Z"))
        connectivity.servedSaved(Instant.parse("2026-11-20T08:00:00Z"))
        assertEquals(OfflineState(Instant.parse("2026-11-19T18:00:00Z")), connectivity.offline.value)

        connectivity.retry()
        assertEquals(1, reloads.size)

        connectivity.reached()
        assertNull(connectivity.offline.value)
        assertEquals(2, reloads.size)
        connectivity.reached()
        assertEquals(2, reloads.size)
    }

    // AC5: through the HTTP stack, the first answer of the backend ends offline mode
    @Test
    fun theFirstAnswerOfTheBackendEndsOfflineMode() = runTest(UnconfinedTestDispatcher()) {
        backend.connectivity.servedSaved(Instant.parse("2026-11-20T09:00:00Z"))
        val reload = backgroundScope.launch { backend.connectivity.reloads.first() }
        backend.on("GET /api/riders/3/thresholds", json("[]"))

        get("/api/riders/3/thresholds").close()

        assertNull(backend.connectivity.offline.value)
        assertTrue(reload.isCompleted)
    }

}
