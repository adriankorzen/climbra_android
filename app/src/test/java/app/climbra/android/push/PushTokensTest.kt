package app.climbra.android.push

import app.climbra.android.data.net.Network
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 020 AC1, AC2: the device's token on the backend after a login, a new token, and before a logout. */
class PushTokensTest {

    private val backend = TestBackend()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class FakeTokens(var token: String? = "token-1", var hang: Boolean = false) : PushTokenSource {
        var deleted = 0
        override suspend fun token(): String? {
            if (hang) awaitCancellation()
            return token
        }

        override suspend fun delete() {
            deleted++
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        backend.close()
    }

    /** The session and the tokens wired as in the app: the logout asks the tokens first. */
    private fun wire(source: PushTokenSource, timeoutMillis: Long = 3_000): Pair<SessionRepository, PushTokens> {
        lateinit var tokens: PushTokens
        val session = backend.session(scope, beforeLogout = { tokens.beforeLogout() })
        tokens = PushTokens(backend.pushApi, source, session.state, scope, logoutTimeoutMillis = timeoutMillis)
        return session to tokens
    }

    private fun await(route: String, count: Int = 1): List<RecordedRequest> {
        val until = System.currentTimeMillis() + 5_000
        while (backend.requests(route).size < count && System.currentTimeMillis() < until) Thread.sleep(20)
        return backend.requests(route)
    }

    private fun bodyOf(request: RecordedRequest) = request.body!!.utf8()

    private fun login(session: SessionRepository) = runBlocking {
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        session.login("anna@example.com", "correct-horse-1")
    }

    // AC1: after a login, and a new token while logged in
    @Test
    fun theTokenIsSentAfterALoginAndWhenItChanges() {
        backend.on("PUT /api/push-devices", noContent(), noContent())
        val (session, tokens) = wire(FakeTokens())

        tokens.newToken("ignored-while-logged-out")
        login(session)

        val sent = await("PUT /api/push-devices")
        // The JSON as sent: the backend requires the platform (a default value would be left out)
        assertEquals("""{"token":"token-1","platform":"ANDROID"}""", bodyOf(sent.single()))

        tokens.newToken("token-2")
        val again = await("PUT /api/push-devices", count = 2)
        assertTrue(bodyOf(again.last()).contains("token-2"))
        assertTrue(again.none { bodyOf(it).contains("ignored-while-logged-out") })
    }

    // AC1: without Firebase nothing is sent
    @Test
    fun withoutFirebaseNothingIsSent() {
        val (session, _) = wire(NoPushTokens)
        login(session)
        Thread.sleep(300)
        assertTrue(backend.requests("PUT /api/push-devices").isEmpty())
    }

    // AC2: DELETE before the logout, with the session's cookie
    @Test
    fun theLogoutRemovesTheTokenFirst() {
        backend.on("PUT /api/push-devices", noContent())
        backend.on("DELETE /api/push-devices", noContent())
        backend.on("POST /api/auth/logout", noContent())
        val source = FakeTokens()
        val (session, _) = wire(source)
        login(session)
        await("PUT /api/push-devices")

        runBlocking { session.logout() }

        val removal = backend.requests("DELETE /api/push-devices").single()
        assertEquals(PushDeviceRemoval("token-1"), Network.json.decodeFromString(PushDeviceRemoval.serializer(), bodyOf(removal)))
        assertTrue(removal.headers["Cookie"].orEmpty().contains("CYCLINGAPP_SESSION=session-1"))
        val order = backend.requests.map { "${it.method} ${it.url.encodedPath}" }
        assertTrue(order.indexOf("DELETE /api/push-devices") < order.indexOf("POST /api/auth/logout"))
        assertTrue(session.state.value is SessionState.LoggedOut)
        val until = System.currentTimeMillis() + 2_000
        while (source.deleted == 0 && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(1, source.deleted)
    }

    // AC2: the logout goes on when the removal fails or takes too long
    @Test
    fun theLogoutGoesOnWhenTheRemovalFailsOrHangs() {
        backend.on("PUT /api/push-devices", noContent(), noContent())
        backend.on("DELETE /api/push-devices", problem(500, null))
        backend.on("POST /api/auth/logout", noContent(), noContent())
        val (session, _) = wire(FakeTokens())
        login(session)
        runBlocking { session.logout() }
        assertTrue(session.state.value is SessionState.LoggedOut)

        val hanging = FakeTokens(hang = true)
        val (other, _) = wire(hanging, timeoutMillis = 200)
        login(other)
        val started = System.currentTimeMillis()
        runBlocking { other.logout() }
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertTrue(other.state.value is SessionState.LoggedOut)
    }
}
