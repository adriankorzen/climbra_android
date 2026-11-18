package app.climbra.android.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.rider.RiderRepository
import app.climbra.android.session.SessionRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before

/** The backend, a logged-out session, the credits and the background requests for the coach's view model tests. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class CoachTestBase {

    protected val backend = TestBackend()
    protected val dispatcher = UnconfinedTestDispatcher()
    protected val scope = CoroutineScope(SupervisorJob() + dispatcher)
    protected val session: SessionRepository = backend.session(scope)
    protected val foreground = MutableStateFlow(true)
    protected val quotas = backend.quotas(session, scope)
    protected val activity = backend.coachActivity(session, scope, quotas, foreground)
    protected val riders = RiderRepository(backend.riderApi) { "Europe/Warsaw" }

    @Before
    fun setUpMain() = Dispatchers.setMain(dispatcher)

    private val viewModels = mutableListOf<ViewModel>()

    /** Stopped after the test, so its polling does not outlive it. */
    protected fun <T : ViewModel> T.tracked(): T = also { viewModels += it }

    @After
    fun tearDownCoach() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        // A call still on OkHttp's threads resumes on Main when it answers: let it, before Main is reset
        val end = System.currentTimeMillis() + 2_000
        while (backend.client.dispatcher.runningCallsCount() > 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        Dispatchers.resetMain()
        backend.close()
    }

    protected suspend fun loggedIn(language: String = "pl") {
        backend.on("POST /api/auth/login", json(account(language = language), 200, SESSION_COOKIE))
        session.login("anna@example.com", "correct-horse-1")
    }

    protected fun withRider() = backend.on("GET /api/riders", json(CoachFixtures.RIDERS))

    protected fun count(route: String) = backend.requests(route).size

    protected fun body(route: String) = backend.requests(route).last().body!!.utf8()

    /** Requests reach the server on OkHttp's threads: waits in real time while the dispatcher runs. */
    protected suspend fun eventually(check: () -> Unit) {
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
}
