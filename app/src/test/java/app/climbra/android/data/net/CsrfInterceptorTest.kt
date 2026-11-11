package app.climbra.android.data.net

import app.climbra.android.data.api.LoginRequest
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 001 AC3: CSRF token of mutating requests. */
class CsrfInterceptorTest {

    private val backend = TestBackend()

    @After
    fun tearDown() = backend.close()

    @Test
    fun withoutTheCookieTheTokenIsFetchedFirstAndSentAsHeader() = runTest {
        backend.on("POST /api/auth/login", json(account()))

        backend.api.login(LoginRequest("anna@example.com", "secret", true))

        assertEquals(listOf("GET /api/auth/csrf", "POST /api/auth/login"), backend.requests.map { "${it.method} ${it.url.encodedPath}" })
        val login = backend.requests("POST /api/auth/login").single()
        assertEquals("token-1", login.headers["X-XSRF-TOKEN"])
        assertEquals("XSRF-TOKEN=token-1", login.headers["Cookie"])
    }

    @Test
    fun anExistingCookieIsReusedAndReadRequestsGetNoHeader() = runTest {
        backend.on("POST /api/auth/logout", TestBackend.noContent())
        backend.on("GET /api/auth/me", json(account()))

        backend.api.logout()
        backend.api.logout()
        backend.api.me()

        assertEquals(1, backend.requests("GET /api/auth/csrf").size)
        assertEquals(listOf("token-1", "token-1"), backend.requests("POST /api/auth/logout").map { it.headers["X-XSRF-TOKEN"] })
        assertNull(backend.requests("GET /api/auth/me").single().headers["X-XSRF-TOKEN"])
    }

    @Test
    fun anInvalidTokenIsRefreshedAndTheRequestRepeatedOnce() = runTest {
        backend.on(
            "POST /api/account/accept-terms",
            problem(403, "ACCESS_DENIED", "Invalid or missing CSRF token"),
            TestBackend.noContent(),
        )

        backend.api.acceptTerms(app.climbra.android.data.api.AcceptTermsRequest(true, true))

        assertEquals(
            listOf("token-1", "token-2"),
            backend.requests("POST /api/account/accept-terms").map { it.headers["X-XSRF-TOKEN"] },
        )
    }

    @Test
    fun anotherForbiddenAnswerIsNotRetried() = runTest {
        backend.on("POST /api/account/accept-terms", problem(403, "ACCESS_DENIED", "Access denied"))

        val result = apiCall(Network.json) {
            backend.api.acceptTerms(app.climbra.android.data.api.AcceptTermsRequest(true, true))
        }

        assertEquals(ApiResult.Failure(ApiError.Http(403, "ACCESS_DENIED")), result)
        assertEquals(1, backend.requests("POST /api/account/accept-terms").size)
    }
}
