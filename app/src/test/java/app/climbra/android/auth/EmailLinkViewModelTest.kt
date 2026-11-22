package app.climbra.android.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.R
import app.climbra.android.data.api.EmailRequest
import app.climbra.android.data.api.PasswordResetConfirmRequest
import app.climbra.android.data.api.VerifyEmailRequest
import app.climbra.android.data.net.Network
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.common.UiText
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
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 021 AC4, AC5: the verify email and set a new password screens' requests and answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailLinkViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val session: SessionRepository = backend.session(scope)
    private val viewModels = mutableListOf<ViewModel>()

    private class FakeCaptcha(var result: CaptchaResult = CaptchaResult.Token("captcha-1")) : CaptchaSource {
        override suspend fun token(): CaptchaResult = result
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend.on("GET /api/auth/providers", json("""{"google":false,"turnstileSiteKey":"site-key"}"""))
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        Dispatchers.resetMain()
        backend.close()
    }

    private fun verify(token: String = "link-token", saved: SavedStateHandle = SavedStateHandle()) =
        VerifyEmailViewModel(backend.api, session, saved).also { viewModels += it; it.open(token) }

    private fun reset(token: String = "link-token") =
        ResetPasswordViewModel(backend.api, SavedStateHandle()).also { viewModels += it; it.open(token) }

    /** Waits until the request sent through the real HTTP stack has its answer. */
    private suspend fun VerifyEmailViewModel.settle() = state.first { !it.sending }

    private suspend fun ResetPasswordViewModel.settle() = state.first { !it.sending }

    private fun verified(): VerifyEmailRequest = Network.json.decodeFromString(
        VerifyEmailRequest.serializer(),
        backend.requests("POST /api/auth/verify-email").last().body!!.utf8(),
    )

    // AC4: the token and the password go along; 200 logs in
    @Test
    fun verifyingLogsIn() = runTest(dispatcher) {
        backend.on("POST /api/auth/verify-email", json(account(), 200, SESSION_COOKIE))
        val viewModel = verify()

        viewModel.confirm()
        viewModel.settle()
        assertTrue(backend.requests("POST /api/auth/verify-email").isEmpty())
        assertTrue(viewModel.state.value.showErrors)

        viewModel.setPassword("correct-horse-1")
        viewModel.confirm()
        viewModel.settle()

        assertEquals(VerifyEmailRequest("link-token", "correct-horse-1"), verified())
        assertTrue(viewModel.state.value.verified)
        assertTrue(session.state.value is SessionState.LoggedIn)
    }

    // AC4: terms not accepted: the terms screen
    @Test
    fun verifyingAnAccountWithoutTermsAsksForThem() = runTest(dispatcher) {
        backend.on("POST /api/auth/verify-email", json(account(termsAccepted = false, legalVersion = null), 200, SESSION_COOKIE))
        val viewModel = verify()
        viewModel.setPassword("correct-horse-1")
        viewModel.confirm()
        viewModel.settle()

        assertTrue(session.state.value is SessionState.NeedsTerms)
    }

    // AC4: a wrong password says so, and does not end a session of the app
    @Test
    fun aWrongPasswordSaysSoAndKeepsTheSession() = runTest(dispatcher) {
        backend.on("GET /api/auth/me", json(account(email = "other@example.com")))
        session.start()
        backend.on("POST /api/auth/verify-email", problem(401, "BAD_CREDENTIALS"))
        val viewModel = verify()
        viewModel.setPassword("wrong-password")
        viewModel.confirm()
        viewModel.settle()

        assertTrue(viewModel.state.value.wrongPassword)
        assertFalse(viewModel.state.value.verified)
        assertEquals("other@example.com", (session.state.value as SessionState.LoggedIn).account.email)

        viewModel.setPassword("wrong-password-2")
        assertFalse(viewModel.state.value.wrongPassword)
    }

    // AC4: an invalid link asks for a new one, sent with a captcha token
    @Test
    fun anInvalidLinkAsksForANewOne() = runTest(dispatcher) {
        backend.on("POST /api/auth/verify-email", problem(400, "TOKEN_INVALID"))
        backend.on("POST /api/auth/resend-verification", MockResponse.Builder().code(202).build())
        val viewModel = verify()
        viewModel.setPassword("correct-horse-1")
        viewModel.confirm()
        viewModel.settle()
        assertTrue(viewModel.state.value.invalid)

        viewModel.setEmail("not an email")
        val captcha = FakeCaptcha()
        viewModel.resend(captcha)
        viewModel.settle()
        assertTrue(backend.requests("POST /api/auth/resend-verification").isEmpty())

        viewModel.setEmail(" anna@example.com ")
        viewModel.resend(captcha)
        viewModel.settle()
        val sent = Network.json.decodeFromString(
            EmailRequest.serializer(),
            backend.requests("POST /api/auth/resend-verification").single().body!!.utf8(),
        )
        assertEquals(EmailRequest("anna@example.com", "captcha-1"), sent)
        assertTrue(viewModel.state.value.resent)
    }

    // AC4: without a captcha nothing is sent
    @Test
    fun noCaptchaNoResend() = runTest(dispatcher) {
        val viewModel = verify(token = "")
        viewModel.setEmail("anna@example.com")
        viewModel.resend(FakeCaptcha(CaptchaResult.Unavailable))
        viewModel.settle()

        assertTrue(backend.requests("POST /api/auth/resend-verification").isEmpty())
        assertEquals(UiText(R.string.captcha_unavailable), viewModel.state.value.error)
    }

    // AC2: a link without a token is the invalid state; the token is kept across a restart
    @Test
    fun aMissingTokenIsInvalidAndTheTokenIsKept() {
        assertTrue(verify(token = "").state.value.invalid)
        assertTrue(reset(token = "").state.value.invalid)

        val saved = SavedStateHandle()
        verify(token = "first", saved = saved)
        val again = verify(token = "", saved = saved)
        assertFalse(again.state.value.invalid)
    }

    // AC5: the password rules are checked before sending; 204 is done
    @Test
    fun resettingChecksTheRulesAndSends() = runTest(dispatcher) {
        backend.on("POST /api/auth/password-reset/confirm", noContent())
        val viewModel = reset()

        viewModel.setPassword("short")
        viewModel.submit()
        viewModel.settle()
        assertTrue(backend.requests("POST /api/auth/password-reset/confirm").isEmpty())

        viewModel.setPassword("correct-horse-1")
        viewModel.setRepeat("correct-horse-2")
        viewModel.submit()
        viewModel.settle()
        assertTrue(backend.requests("POST /api/auth/password-reset/confirm").isEmpty())

        viewModel.setRepeat("correct-horse-1")
        viewModel.submit()
        viewModel.settle()
        val sent = Network.json.decodeFromString(
            PasswordResetConfirmRequest.serializer(),
            backend.requests("POST /api/auth/password-reset/confirm").single().body!!.utf8(),
        )
        assertEquals(PasswordResetConfirmRequest("link-token", "correct-horse-1"), sent)
        assertTrue(viewModel.state.value.done)
    }

    // AC5: an invalid link; other errors are shown and the values kept
    @Test
    fun resetErrors() = runTest(dispatcher) {
        backend.on(
            "POST /api/auth/password-reset/confirm",
            problem(400, "PASSWORD_EQUALS_EMAIL"),
            problem(400, "TOKEN_INVALID"),
        )
        val viewModel = reset()
        viewModel.setPassword("correct-horse-1")
        viewModel.setRepeat("correct-horse-1")

        viewModel.submit()
        viewModel.settle()
        assertTrue(viewModel.state.value.error != null)
        assertEquals("correct-horse-1", viewModel.state.value.password)
        assertFalse(viewModel.state.value.invalid)

        viewModel.submit()
        viewModel.settle()
        assertTrue(viewModel.state.value.invalid)
        assertNull(viewModel.state.value.error)
    }
}
