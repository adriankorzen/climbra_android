package app.climbra.android.session

import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 001: start, login, terms, session end and language through [SessionRepository]. */
class SessionRepositoryTest {

    private val backend = TestBackend()

    @After
    fun tearDown() = backend.close()

    private fun test(body: suspend TestScope.(SessionRepository) -> Unit) = runTest(UnconfinedTestDispatcher()) {
        body(backend.session(backgroundScope))
    }

    private fun sessionCookie() = backend.cookies.value(backend.server.url("/"), "CYCLINGAPP_SESSION")

    // AC1
    @Test
    fun aValidStoredSessionOpensTheApp() = test { session ->
        backend.on("GET /api/auth/me", json(account()))

        session.start()

        val state = session.state.value as SessionState.LoggedIn
        assertEquals("anna@example.com", state.account.email)
    }

    // AC1
    @Test
    fun noOrAnExpiredSessionShowsTheLogin() = test { session ->
        backend.on("GET /api/auth/me", problem(401, "UNAUTHENTICATED"))

        session.start()

        assertEquals(SessionState.LoggedOut(expired = false), session.state.value)
    }

    // AC1
    @Test
    fun aNetworkErrorAtStartKeepsTheStoredSession() = test { session ->
        backend.on("GET /api/auth/me", json(account(), 200, SESSION_COOKIE))
        backend.api.me()
        // No saved account: with one the shell opens offline (spec 018, OfflineCacheTest)
        backend.offlineCache.clear()
        backend.server.close()

        session.start()

        assertEquals(SessionState.Unavailable(ApiError.Network), session.state.value)
        assertEquals("session-1", sessionCookie())
    }

    // AC1
    @Test
    fun aServerErrorAtStartIsUnavailableToo() = test { session ->
        backend.on("GET /api/auth/me", problem(503, null))

        session.start()

        assertEquals(SessionState.Unavailable(ApiError.Http(503, null)), session.state.value)
    }

    // AC4, AC12
    @Test
    fun loginRemembersTheSessionAndUsesTheAccountLanguage() = test { session ->
        backend.on("POST /api/auth/login", json(account(language = "pl"), 200, SESSION_COOKIE))

        val error = session.login(" anna@example.com ", "correct-horse-1")

        assertNull(error)
        assertTrue(session.state.value is SessionState.LoggedIn)
        val body = backend.requests("POST /api/auth/login").single().body!!.utf8()
        assertEquals("""{"email":"anna@example.com","password":"correct-horse-1","rememberMe":true}""", body)
        assertEquals(listOf("account:pl"), backend.locales.calls)
        assertEquals("session-1", sessionCookie())
    }

    // AC4, AC7
    @Test
    fun anAccountWithoutAcceptedTermsGoesToTheTerms() = test { session ->
        backend.on("POST /api/auth/login", json(account(termsAccepted = false, legalVersion = null)))

        session.login("anna@example.com", "correct-horse-1")

        val state = session.state.value as SessionState.NeedsTerms
        assertFalse(state.account.legalDocumentsChanged)
    }

    // AC5 (the codes reach the screen)
    @Test
    fun aRefusedLoginReturnsTheErrorCodeAndStaysLoggedOut() = test { session ->
        backend.on("GET /api/auth/me", problem(401, "UNAUTHENTICATED"))
        backend.on("POST /api/auth/login", problem(401, "BAD_CREDENTIALS"))
        session.start()

        val error = session.login("anna@example.com", "wrong")

        assertEquals(ApiError.Http(401, "BAD_CREDENTIALS"), error)
        assertEquals(SessionState.LoggedOut(expired = false), session.state.value)
    }

    // AC6
    @Test
    fun googleLoginSendsTheIdTokenAfterTheNonce() = test { session ->
        backend.on("GET /api/auth/google/nonce", json("""{"nonce":"nonce-123"}"""))
        backend.on("POST /api/auth/google/id-token", json(account(termsAccepted = false, legalVersion = null)))

        val nonce = session.googleNonce()
        val error = session.loginWithGoogle("id-token-abc")

        assertEquals(ApiResult.Success("nonce-123"), nonce)
        assertNull(error)
        assertEquals(
            """{"idToken":"id-token-abc"}""",
            backend.requests("POST /api/auth/google/id-token").single().body!!.utf8(),
        )
        assertTrue(session.state.value is SessionState.NeedsTerms)
    }

    // AC7
    @Test
    fun acceptingTheTermsPostsBothConsentsAndOpensTheApp() = test { session ->
        backend.on("POST /api/auth/login", json(account(termsAccepted = false)))
        backend.on("POST /api/account/accept-terms", noContent())
        backend.on("GET /api/auth/me", json(account(termsAccepted = true)))
        session.login("anna@example.com", "correct-horse-1")
        assertTrue((session.state.value as SessionState.NeedsTerms).account.legalVersion != null)

        val error = session.acceptTerms()

        assertNull(error)
        assertEquals(
            """{"acceptTerms":true,"acceptHealthData":true}""",
            backend.requests("POST /api/account/accept-terms").single().body!!.utf8(),
        )
        assertTrue(session.state.value is SessionState.LoggedIn)
    }

    // AC8
    @Test
    fun a401OfAnyRequestEndsTheSession() = test { session ->
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        backend.on("GET /api/auth/me", problem(401, "UNAUTHENTICATED"))
        session.login("anna@example.com", "correct-horse-1")
        backend.locales.calls.clear()

        apiCall(Network.json) { backend.api.me() }

        assertEquals(SessionState.LoggedOut(expired = true), session.state.first { it is SessionState.LoggedOut })
        assertNull(sessionCookie())
        assertEquals(listOf("device"), backend.locales.calls)
    }

    // AC8
    @Test
    fun aNewLegalVersionWhileLoggedInShowsTheTerms() = test { session ->
        backend.on("POST /api/auth/login", json(account()))
        backend.on("POST /api/account/accept-terms", problem(403, "TERMS_NOT_ACCEPTED"))
        backend.on("GET /api/auth/providers", problem(403, "TERMS_NOT_ACCEPTED"))
        session.login("anna@example.com", "correct-horse-1")

        session.providers()

        assertNotNull(session.state.first { it is SessionState.NeedsTerms })
    }

    // AC9
    @Test
    fun logoutEndsTheSessionOnTheServerAndClearsTheCookies() = test { session ->
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        backend.on("POST /api/auth/logout", noContent())
        session.login("anna@example.com", "correct-horse-1")

        session.logout()

        assertEquals("session-1", backend.requests("POST /api/auth/logout").single().headers["Cookie"]?.let {
            Regex("CYCLINGAPP_SESSION=([^;]+)").find(it)?.groupValues?.get(1)
        })
        assertEquals(SessionState.LoggedOut(expired = false), session.state.value)
        assertNull(sessionCookie())
        assertEquals("device", backend.locales.calls.last())
    }

    // AC9
    @Test
    fun aFailedLogoutStillClearsTheCookies() = test { session ->
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        session.login("anna@example.com", "correct-horse-1")
        backend.server.close()

        session.logout()

        assertEquals(SessionState.LoggedOut(expired = false), session.state.value)
        assertNull(sessionCookie())
    }

    // Spec 004 AC10
    @Test
    fun changingTheLanguageStoresItOnTheAccountAndSwitchesTheApp() = test { session ->
        backend.on("POST /api/auth/login", json(account(language = "pl"), 200, SESSION_COOKIE))
        backend.on("PUT /api/account/language", noContent())
        session.login("anna@example.com", "correct-horse-1")

        assertNull(session.changeLanguage("en"))

        assertEquals("""{"language":"en"}""", backend.requests("PUT /api/account/language").single().body?.utf8())
        assertEquals("en", (session.state.value as SessionState.LoggedIn).account.language)
        assertEquals("account:en", backend.locales.calls.last())
    }

    // Spec 004 AC10
    @Test
    fun aFailedLanguageChangeKeepsTheLanguage() = test { session ->
        backend.on("POST /api/auth/login", json(account(language = "pl"), 200, SESSION_COOKIE))
        backend.on("PUT /api/account/language", problem(500, null))
        session.login("anna@example.com", "correct-horse-1")

        assertEquals(ApiError.Http(500, null), session.changeLanguage("en"))

        assertEquals("pl", (session.state.value as SessionState.LoggedIn).account.language)
        assertEquals("account:pl", backend.locales.calls.last())
    }

    // Spec 004 AC13
    @Test
    fun changingThePasswordSendsBothPasswords() = test { session ->
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        backend.on("PUT /api/account/password", noContent(), problem(400, "WRONG_PASSWORD"))
        session.login("anna@example.com", "correct-horse-1")

        assertNull(session.changePassword("correct-horse-1", "battery-staple-2"))
        assertEquals(ApiError.Http(400, "WRONG_PASSWORD"), session.changePassword("wrong", "battery-staple-2"))

        assertEquals(
            """{"currentPassword":"correct-horse-1","newPassword":"battery-staple-2"}""",
            backend.requests("PUT /api/account/password").first().body?.utf8(),
        )
        assertTrue(session.state.value is SessionState.LoggedIn)
    }

    // Spec 004 AC14
    @Test
    fun deletingTheAccountEndsTheSession() = test { session ->
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        backend.on("DELETE /api/account", problem(400, "DELETE_CONFIRMATION_INVALID"), noContent())
        session.login("anna@example.com", "correct-horse-1")

        assertEquals(ApiError.Http(400, "DELETE_CONFIRMATION_INVALID"), session.deleteAccount("wrong", null))
        assertTrue(session.state.value is SessionState.LoggedIn)

        assertNull(session.deleteAccount("correct-horse-1", null))

        assertEquals("""{"password":"correct-horse-1"}""", backend.requests("DELETE /api/account").last().body?.utf8())
        assertEquals(SessionState.LoggedOut(accountDeleted = true), session.state.value)
        assertNull(sessionCookie())
        assertEquals("device", backend.locales.calls.last())
    }
}
