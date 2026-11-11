package app.climbra.android.auth

import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.auth.google.GoogleCredentialResult
import app.climbra.android.auth.google.GoogleCredentialSource
import app.climbra.android.session.SessionState
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.common.UiText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 001 AC4 - AC6: the login screen's logic against the backend. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private fun config(google: Boolean) = AppConfig("http://unused", "https://climbra.app", "web-client-id", google)

    private fun test(google: Boolean = true, body: suspend TestScope.(LoginViewModel, () -> SessionState) -> Unit) =
        runTest(dispatcher) {
            val session = backend.session(backgroundScope)
            val viewModel = LoginViewModel(session, config(google))
            body(viewModel) { session.state.value }
        }

    private suspend fun LoginViewModel.settled() = state.first { !it.submitting }

    // AC4
    @Test
    fun emptyFieldsAreMarkedAndNothingIsSent() = test { viewModel, _ ->
        viewModel.login()

        val state = viewModel.state.value
        assertTrue(state.emailMissing)
        assertTrue(state.passwordMissing)
        assertTrue(backend.requests("POST /api/auth/login").isEmpty())
    }

    // AC4
    @Test
    fun aSuccessfulLoginOpensTheApp() = test { viewModel, session ->
        backend.on("POST /api/auth/login", json(account()))
        viewModel.onEmailChange("anna@example.com")
        viewModel.onPasswordChange("correct-horse-1")

        viewModel.login()
        val state = viewModel.settled()

        assertNull(state.error)
        assertTrue(session() is SessionState.LoggedIn)
    }

    // AC5
    @Test
    fun backendErrorCodesShowTheirMessages() {
        val expected = mapOf(
            "BAD_CREDENTIALS" to R.string.api_error_bad_credentials,
            "EMAIL_NOT_VERIFIED" to R.string.api_error_email_not_verified,
            "LOGIN_LOCKED" to R.string.api_error_login_locked,
            "TOO_MANY_REQUESTS" to R.string.api_error_too_many_requests,
        )
        for ((code, message) in expected) {
            val status = when (code) {
                "EMAIL_NOT_VERIFIED" -> 403
                "BAD_CREDENTIALS" -> 401
                else -> 429
            }
            assertEquals(code, UiText(message), loginError(problem(status, code)))
        }
        assertEquals(UiText(R.string.error_request_failed, listOf(500)), loginError(problem(500, "SOMETHING_NEW")))
    }

    private fun loginError(response: mockwebserver3.MockResponse): UiText? {
        var error: UiText? = null
        TestBackend().use { other ->
            other.on("POST /api/auth/login", response)
            runTest(dispatcher) {
                val viewModel = LoginViewModel(other.session(backgroundScope), config(google = false))
                viewModel.onEmailChange("anna@example.com")
                viewModel.onPasswordChange("password")
                viewModel.login()
                error = viewModel.settled().error
            }
        }
        return error
    }

    // AC6
    @Test
    fun theGoogleButtonNeedsTheProviderAndTheFeatureFlag() {
        backend.on("GET /api/auth/providers", json("""{"google":true,"turnstileSiteKey":null}"""))
        test(google = true) { viewModel, _ ->
            assertTrue(viewModel.state.first { it.googleAvailable }.googleAvailable)
        }
        test(google = false) { viewModel, _ ->
            assertFalse(viewModel.state.first { it.googleChecked }.googleAvailable)
            // Only the view model with the flag on asked the backend
            assertEquals(1, backend.requests("GET /api/auth/providers").size)
        }
    }

    // AC6
    @Test
    fun noGoogleButtonWhenTheBackendHasNoGoogleLogin() {
        backend.on("GET /api/auth/providers", json("""{"google":false}"""))
        test(google = true) { viewModel, _ ->
            assertFalse(viewModel.state.first { it.googleChecked }.googleAvailable)
            assertEquals(1, backend.requests("GET /api/auth/providers").size)
        }
    }

    // AC6
    @Test
    fun aGoogleCredentialWithTheBackendsNonceLogsIn() = test { viewModel, session ->
        backend.on("GET /api/auth/google/nonce", json("""{"nonce":"nonce-123"}"""))
        backend.on("POST /api/auth/google/id-token", json(account()))
        var askedNonce: String? = null

        viewModel.loginWithGoogle { nonce ->
            askedNonce = nonce
            GoogleCredentialResult.Token("id-token-abc")
        }
        val state = viewModel.settled()

        assertEquals("nonce-123", askedNonce)
        assertNull(state.error)
        assertEquals(1, backend.requests("POST /api/auth/google/id-token").size)
        assertTrue(session() is SessionState.LoggedIn)
    }

    // AC6
    @Test
    fun cancellingTheAccountPickerShowsNoError() = test { viewModel, session ->
        backend.on("GET /api/auth/google/nonce", json("""{"nonce":"nonce-123"}"""))

        viewModel.loginWithGoogle(GoogleCredentialSource { GoogleCredentialResult.Cancelled })
        val state = viewModel.settled()

        assertNull(state.error)
        assertTrue(backend.requests("POST /api/auth/google/id-token").isEmpty())
        assertFalse(session() is SessionState.LoggedIn)
    }

    // AC6
    @Test
    fun aRefusedGoogleTokenOrAFailedCredentialShowTheGoogleMessage() = test { viewModel, _ ->
        backend.on("GET /api/auth/google/nonce", json("""{"nonce":"nonce-123"}"""))
        backend.on("POST /api/auth/google/id-token", problem(401, "GOOGLE_FAILED"))

        viewModel.loginWithGoogle { GoogleCredentialResult.Token("id-token-abc") }
        assertEquals(UiText(R.string.login_google_failed), viewModel.settled().error)

        viewModel.loginWithGoogle { GoogleCredentialResult.Failed }
        assertEquals(UiText(R.string.login_google_failed), viewModel.settled().error)
    }

    // AC6: the system's Google UI closed without ever answering
    @Test
    fun aCredentialRequestThatNeverAnswersIsGivenUpAfterTheScreenIsBack() = test { viewModel, session ->
        backend.on("GET /api/auth/google/nonce", json("""{"nonce":"nonce-123"}"""))

        val asked = CompletableDeferred<Unit>()
        viewModel.loginWithGoogle {
            asked.complete(Unit)
            awaitCancellation()
        }
        asked.await()
        viewModel.onScreenPaused()
        viewModel.onScreenResumed()
        assertTrue(viewModel.state.value.submitting)

        val state = viewModel.settled()
        assertTrue("given up only after the grace", currentTime >= 3.seconds.inWholeMilliseconds)
        assertNull(state.error)
        assertFalse(session() is SessionState.LoggedIn)
    }

    // AC6
    @Test
    fun anAnswerWithinTheGraceAfterTheScreenIsBackStillLogsIn() = test { viewModel, session ->
        backend.on("GET /api/auth/google/nonce", json("""{"nonce":"nonce-123"}"""))
        backend.on("POST /api/auth/google/id-token", json(account()))
        val answer = CompletableDeferred<GoogleCredentialResult>()

        val asked = CompletableDeferred<Unit>()
        viewModel.loginWithGoogle {
            asked.complete(Unit)
            answer.await()
        }
        asked.await()
        viewModel.onScreenPaused()
        viewModel.onScreenResumed()
        advanceTimeBy(2.seconds)
        answer.complete(GoogleCredentialResult.Token("id-token-abc"))
        viewModel.settled()
        advanceTimeBy(2.seconds)

        assertTrue(session() is SessionState.LoggedIn)
    }

    // AC6: resuming without having left (the first ON_RESUME) gives up nothing
    @Test
    fun aResumeWithoutPauseKeepsTheRequest() = test { viewModel, _ ->
        backend.on("GET /api/auth/google/nonce", json("""{"nonce":"nonce-123"}"""))

        val asked = CompletableDeferred<Unit>()
        viewModel.loginWithGoogle {
            asked.complete(Unit)
            awaitCancellation()
        }
        asked.await()
        viewModel.onScreenResumed()
        advanceTimeBy(10.seconds)

        assertTrue(viewModel.state.value.submitting)
    }
}
