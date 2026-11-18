package app.climbra.android.auth

import androidx.lifecycle.viewModelScope
import app.climbra.android.R
import app.climbra.android.data.api.EmailRequest
import app.climbra.android.data.api.RegisterRequest
import app.climbra.android.data.net.Network
import app.climbra.android.session.SessionRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.common.UiText
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
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 013 AC2 - AC6: the registration and the password reset, their bodies, captcha and answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val session: SessionRepository = backend.session(scope)
    private val clock = MovableClock()
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    private class MovableClock(var now: Instant = Instant.parse("2026-11-18T10:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    /** Hands out tokens and counts how often one was asked for. */
    private class FakeCaptcha(var result: CaptchaResult = CaptchaResult.Token("token-1")) : CaptchaSource {
        var asked = 0
        override suspend fun token(): CaptchaResult {
            asked++
            return result
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        Dispatchers.resetMain()
        backend.close()
    }

    private val accepted = MockResponse.Builder().code(202).build()

    private fun register(siteKey: String? = "site-key"): RegisterViewModel {
        backend.on("GET /api/auth/providers", json("""{"google":true,"turnstileSiteKey":${siteKey?.let { "\"$it\"" } ?: "null"}}"""))
        return RegisterViewModel(backend.api, session, clock).also { viewModels += it }
    }

    private fun RegisterViewModel.fill() = update {
        it.copy(email = "  anna@example.com ", password = "correct-horse-1", repeat = "correct-horse-1", acceptTerms = true, acceptHealthData = true)
    }

    private fun registered(): RegisterRequest =
        Network.json.decodeFromString(RegisterRequest.serializer(), backend.requests("POST /api/auth/register").last().body!!.utf8())

    // AC2: nothing is sent while something is missing or wrong
    @Test
    fun theFormIsCheckedBeforeAnythingIsSent() = runTest(dispatcher) {
        val viewModel = register()
        val captcha = FakeCaptcha()

        viewModel.register(captcha)
        var state = viewModel.state.value
        assertTrue(state.showErrors)
        assertTrue(state.emailInvalid)

        viewModel.update { it.copy(email = "anna@example", password = "short", repeat = "other") }
        state = viewModel.state.value
        assertTrue(state.emailInvalid)
        assertEquals(app.climbra.android.profile.PasswordProblem.TOO_SHORT, state.passwordProblem)
        assertEquals(app.climbra.android.profile.PasswordProblem.MISMATCH, state.repeatProblem)

        viewModel.fill()
        viewModel.update { it.copy(acceptHealthData = false) }
        viewModel.register(captcha)
        assertEquals(0, captcha.asked)
        assertEquals(0, backend.requests("POST /api/auth/register").size)
    }

    // AC2: the web's body with the language and the token; AC3: then "check your email"
    @Test
    fun theWebsBodyIsSentWithTheToken() = runTest(dispatcher) {
        backend.on("POST /api/auth/register", accepted)
        val viewModel = register()
        viewModel.state.first { it.siteKey != null }
        viewModel.fill()

        viewModel.register(FakeCaptcha())

        assertEquals("anna@example.com", viewModel.state.first { it.sent != null }.sent)
        val request = registered()
        assertEquals("anna@example.com", request.email)
        assertEquals("correct-horse-1", request.password)
        assertTrue(request.language in setOf("pl", "en"))
        assertTrue(request.acceptTerms && request.acceptHealthData)
        assertEquals("token-1", request.captchaToken)
    }

    // AC7: no site key, no token; an unavailable check sends nothing
    @Test
    fun withoutACheckNoTokenAndAFailedCheckSendsNothing() = runTest(dispatcher) {
        backend.on("POST /api/auth/register", accepted)
        val viewModel = register(siteKey = null)
        viewModel.fill()

        viewModel.register(FakeCaptcha(CaptchaResult.Unavailable))
        assertEquals(UiText(R.string.captcha_unavailable), viewModel.state.first { it.error != null }.error)
        assertEquals(0, backend.requests("POST /api/auth/register").size)

        viewModel.register(FakeCaptcha(CaptchaResult.Off))
        viewModel.state.first { it.sent != null }
        assertNull(registered().captchaToken)
        assertFalse(backend.requests("POST /api/auth/register").last().body!!.utf8().contains("captchaToken"))
    }

    // AC4: the backend's codes keep the values; a new token for the next try
    @Test
    fun refusalsKeepTheValues() = runTest(dispatcher) {
        backend.on("POST /api/auth/register", problem(422, "EMAIL_DOMAIN_NOT_ALLOWED"), problem(400, "CAPTCHA_FAILED"), accepted)
        val viewModel = register()
        viewModel.fill()
        val captcha = FakeCaptcha()

        viewModel.register(captcha)
        assertEquals(UiText(R.string.api_error_email_domain_not_allowed), viewModel.state.first { it.error != null && !it.sending }.error)
        viewModel.register(captcha)
        assertEquals(UiText(R.string.api_error_captcha_failed), viewModel.state.first { it.error != null && !it.sending }.error)
        assertEquals("correct-horse-1", viewModel.state.value.password)
        assertTrue(viewModel.state.value.acceptTerms)
        viewModel.register(captcha)
        viewModel.state.first { it.sent != null }
        assertEquals(3, captcha.asked)
    }

    // AC3: the link again, at most once per 60 s
    @Test
    fun theLinkIsSentAgainAtMostOncePerMinute() = runTest(dispatcher) {
        backend.on("POST /api/auth/register", accepted)
        backend.on("POST /api/auth/resend-verification", accepted)
        val viewModel = register()
        viewModel.fill()
        viewModel.register(FakeCaptcha())
        viewModel.state.first { it.sent != null }
        val captcha = FakeCaptcha(CaptchaResult.Token("token-2"))

        viewModel.resend(captcha)
        assertTrue(viewModel.state.first { it.resent }.resent)
        clock.now = clock.now.plusSeconds(30)
        viewModel.resend(captcha)
        clock.now = clock.now.plusSeconds(31)
        viewModel.resend(captcha)
        viewModel.state.first { it.resent && !it.sending }

        val requests = backend.requests("POST /api/auth/resend-verification")
        assertEquals(2, requests.size)
        assertEquals(
            EmailRequest("anna@example.com", "token-2"),
            Network.json.decodeFromString(EmailRequest.serializer(), requests.first().body!!.utf8()),
        )
    }

    private fun forgot(): ForgotPasswordViewModel {
        backend.on("GET /api/auth/providers", json("""{"google":false,"turnstileSiteKey":"site-key"}"""))
        return ForgotPasswordViewModel(backend.api, session).also { viewModels += it }
    }

    // AC5, AC6: the typed address comes along; the same answer whether the account exists
    @Test
    fun aPasswordResetSendsTheEmailAndTheToken() = runTest(dispatcher) {
        backend.on("POST /api/auth/password-reset", problem(429, "TOO_MANY_REQUESTS"), accepted)
        val viewModel = forgot()
        viewModel.prefill("anna@example.com")
        viewModel.prefill("other@example.com")
        assertEquals("anna@example.com", viewModel.state.value.email)

        viewModel.setEmail("not an email")
        viewModel.send(FakeCaptcha())
        assertTrue(viewModel.state.value.showErrors && viewModel.state.value.emailInvalid)
        assertEquals(0, backend.requests("POST /api/auth/password-reset").size)

        viewModel.setEmail(" anna@example.com ")
        viewModel.send(FakeCaptcha())
        assertEquals(R.string.api_error_too_many_requests, viewModel.state.first { it.error != null && !it.sending }.error!!.res)
        viewModel.send(FakeCaptcha())
        assertEquals("anna@example.com", viewModel.state.first { it.sent != null }.sent)
        assertEquals(
            EmailRequest("anna@example.com", "token-1"),
            Network.json.decodeFromString(EmailRequest.serializer(), backend.requests("POST /api/auth/password-reset").last().body!!.utf8()),
        )
    }
}
