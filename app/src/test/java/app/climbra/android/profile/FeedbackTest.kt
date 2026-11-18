package app.climbra.android.profile

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.coach.CoachFixtures.RIDERS
import app.climbra.android.data.api.FeedbackRequest
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.Network
import app.climbra.android.rider.RiderRepository
import app.climbra.android.shell.Shell
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.theme.ClimbraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import app.climbra.android.testing.TestBackend.Companion.json

/** Spec 010 AC1 - AC4: the feedback form's values and request. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val viewModels = mutableListOf<FeedbackViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        backend.close()
    }

    private fun viewModel() = FeedbackViewModel(RiderRepository(backend.riderApi) { "Europe/Warsaw" }, backend.feedbackApi)
        .also {
            it.version = "0.1.0"
            viewModels += it
        }

    private val route = "POST /api/feedback"

    private fun sent(): FeedbackRequest = Network.json.decodeFromString(FeedbackRequest.serializer(), backend.requests(route).last().body!!.utf8())

    // AC1: the rider's name, no type, no message
    @Test
    fun theFormStartsWithTheRidersName() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))

        val state = viewModel().state.first { it.name.isNotEmpty() }

        assertEquals("Anna", state.name)
        assertNull(state.type)
        assertEquals("", state.message)
    }

    // AC2: missing fields send nothing; the limits cut
    @Test
    fun missingFieldsSendNothingAndLimitsCut() = runTest(dispatcher) {
        backend.on("GET /api/riders", json("[]"))
        val viewModel = viewModel()

        viewModel.setName("   ")
        viewModel.setMessage(" ")
        viewModel.send()

        val state = viewModel.state.value
        assertTrue(state.submitted)
        assertTrue(state.nameMissing && state.typeMissing && state.messageMissing)
        assertEquals(0, backend.requests(route).size)
        viewModel.setName("n".repeat(150))
        viewModel.setMessage("m".repeat(6000))
        assertEquals(100, viewModel.state.value.name.length)
        assertEquals(5000, viewModel.state.value.message.length)
    }

    // AC3: trimmed values, the type and where it comes from; sent
    @Test
    fun sendPostsTheTrimmedValuesAndTheAppsVersion() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on(route, MockResponse.Builder().code(201).build())
        val viewModel = viewModel()
        viewModel.state.first { it.name.isNotEmpty() }

        viewModel.setName("  Anna K  ")
        viewModel.setType("BUG")
        viewModel.setMessage("  The chart is empty.  ")
        viewModel.send()

        assertTrue(viewModel.state.first { it.sent }.sent)
        assertEquals(FeedbackRequest("Anna K", "BUG", "The chart is empty.", "android/0.1.0"), sent())
    }

    // AC4: too much feedback lately; the values stay
    @Test
    fun aRefusalKeepsTheValues() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on(route, problem(429, null))
        val viewModel = viewModel()
        viewModel.state.first { it.name.isNotEmpty() }
        viewModel.setType("SUGGESTION")
        viewModel.setMessage("More charts")

        viewModel.send()

        val state = viewModel.state.first { it.error != null }
        assertEquals(ApiError.Http(429, null), state.error)
        assertFalse(state.sent)
        assertEquals("More charts", state.message)
        assertEquals("SUGGESTION", state.type)
        assertEquals(app.climbra.android.R.string.feedback_too_many, feedbackErrorText(state.error!!).res)
    }

}

/** Spec 010 AC1 - AC4: the feedback form on the screen, and its place in the profile. */
@RunWith(AndroidJUnit4::class)
class FeedbackScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun inside(tag: String, text: String) = compose.onNode(
        hasText(text, substring = true) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
        useUnmergedTree = true,
    )

    // AC1, AC2: the fields, the counter, the missing fields' errors
    @Test
    fun theFormShowsItsFieldsAndWhatIsMissing() {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(FeedbackUiState(name = "Anna"))
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                FeedbackScreen(state, FeedbackActions(onType = { calls += "type $it" }, onSend = { calls += "send" }))
            }
        }

        compose.onNodeWithText("Send feedback").assertIsDisplayed()
        compose.onNodeWithText("Every message goes to the author of the app", substring = true).assertIsDisplayed()
        inside("feedback-name", "Anna").assertExists()
        inside("feedback-chars", "0 / 5000").assertExists()
        compose.onNodeWithTag("feedback-type-improvement").performClick()
        compose.onNodeWithTag("feedback-send").performScrollTo().performClick()
        assertEquals(listOf("type IMPROVEMENT", "send"), calls)

        state = FeedbackUiState(name = "", type = "BUG", message = "Hi", submitted = true)
        compose.onNodeWithTag("feedback-name-error").assertExists()
        compose.onNodeWithTag("feedback-type-error").assertDoesNotExist()
        compose.onNodeWithTag("feedback-message-error").assertDoesNotExist()
        compose.onNodeWithTag("feedback-type-bug").assertIsSelected()
        inside("feedback-chars", "2 / 5000").assertExists()

        // AC4
        state = state.copy(error = ApiError.Http(429, null))
        inside("feedback-error", "You have sent a lot of feedback lately.").assertExists()
    }

    // AC1: the profile's account card opens the form on the profile's stack
    @Test
    fun theProfileOpensTheForm() {
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                Shell(
                    dashboard = { Text("dashboard") },
                    activities = { Text("activities") },
                    profile = { nav -> Button(onClick = nav::openFeedback, modifier = Modifier.testTag("profile-feedback")) { Text("profile") } },
                    feedback = { nav -> Button(onClick = nav::back, modifier = Modifier.testTag("feedback-screen")) { Text("form") } },
                )
            }
        }

        compose.onNodeWithTag("nav-profile").performClick()
        compose.onNodeWithTag("profile-feedback").performClick()
        compose.onNodeWithTag("feedback-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-profile").assertIsSelected()
        compose.onNodeWithTag("feedback-screen").performClick()
        compose.onNodeWithTag("profile-feedback").assertIsDisplayed()
    }
}
