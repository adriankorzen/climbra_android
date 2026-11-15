package app.climbra.android.coach

import app.climbra.android.coach.CoachFixtures.conversation
import app.climbra.android.coach.CoachFixtures.list
import app.climbra.android.coach.CoachFixtures.parse
import app.climbra.android.coach.CoachFixtures.summary
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 006 AC2, AC3: the conversation list, its filter, reloads and deletion. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachListViewModelTest : CoachTestBase() {

    private fun viewModel() = CoachListViewModel(riders, backend.coachApi, activity, quotas).tracked()

    private suspend fun CoachListViewModel.settled() = state.first { !it.loading && !it.refreshing && !it.busy }

    // AC2
    @Test
    fun loadsTheConversationsNewestFirstAndFiltersThemByType() = runTest(dispatcher) {
        withRider()
        backend.on(
            "GET /api/riders/5/coach/conversations",
            json(list(summary(3, "TRAINING_BLOCK"), summary(2, "PLAN_ADJUSTMENT", planId = 9), summary(1, activityId = 85))),
        )
        val viewModel = viewModel()

        val state = viewModel.settled()
        assertEquals(listOf(3L, 2L, 1L), state.shown.map { it.id })

        viewModel.selectFilter(ConversationFilter.ACTIVITY_REVIEW)
        assertEquals(listOf(1L), viewModel.state.value.shown.map { it.id })
        viewModel.selectFilter(ConversationFilter.TRAINING_BLOCK)
        assertEquals(listOf(3L), viewModel.state.value.shown.map { it.id })
        // "All" shows the plan adjustments too
        viewModel.selectFilter(ConversationFilter.ALL)
        assertEquals(3, viewModel.state.value.shown.size)
    }

    // AC2
    @Test
    fun anEmptyListShowsTheWelcomeAndAFailureTheError() = runTest(dispatcher) {
        withRider()
        backend.on("GET /api/riders/5/coach/conversations", problem(500, null), json("[]"))
        val viewModel = viewModel()

        assertTrue(viewModel.settled().error != null)

        viewModel.refresh()
        val state = viewModel.settled()
        assertTrue(state.empty)
        assertEquals(2, count("GET /api/riders/5/coach/conversations"))
    }

    // AC2, AC16: an accepted request or its end reloads the list; busy rows are followed
    @Test
    fun aChangeOfTheConversationsReloadsTheListAndBusyOnesAreFollowed() = runTest(dispatcher) {
        withRider()
        backend.on("GET /api/riders/5/coach/conversations", json(list(summary(1, status = "BUSY", title = "Build"))), json("[]"))
        backend.on("GET /api/coach/conversations/1", json(conversation(id = 1, busy = true)))
        val viewModel = viewModel()
        viewModel.settled()
        assertEquals(mapOf(1L to "Build"), activity.running.value)

        activity.changed()

        eventually { assertEquals(2, count("GET /api/riders/5/coach/conversations")) }
        assertTrue(viewModel.state.first { it.conversations.isEmpty() && !it.refreshing }.empty)
    }

    // AC3
    @Test
    fun deletingAsksFirstThenDeletesAndReloads() = runTest(dispatcher) {
        withRider()
        backend.on("GET /api/riders/5/coach/conversations", json(list(summary(1, title = "Tempo"))), json("[]"))
        backend.on("DELETE /api/coach/conversations/1", noContent())
        val viewModel = viewModel()
        val conversation = viewModel.settled().conversations.single()

        viewModel.askDelete(conversation)
        assertEquals(conversation, viewModel.state.value.deleting)
        viewModel.cancelDelete()
        assertEquals(0, count("DELETE /api/coach/conversations/1"))

        viewModel.askDelete(conversation)
        viewModel.confirmDelete()

        assertEquals(CoachListMessage.Deleted, viewModel.state.first { it.message != null }.message)
        eventually { assertEquals(2, count("GET /api/riders/5/coach/conversations")) }
        assertEquals(1, count("DELETE /api/coach/conversations/1"))
    }

    // AC3
    @Test
    fun aFailedDeletionIsToldAndTheListStays() = runTest(dispatcher) {
        withRider()
        backend.on("GET /api/riders/5/coach/conversations", json(list(summary(1))))
        backend.on("DELETE /api/coach/conversations/1", problem(409, "ACTIVITY_BUSY"))
        val viewModel = viewModel()
        viewModel.askDelete(viewModel.settled().conversations.single())

        viewModel.confirmDelete()

        assertTrue(viewModel.state.first { it.message != null }.message is CoachListMessage.Failed)
        assertEquals(1, viewModel.state.value.conversations.size)
    }

    // AC2
    @Test
    fun anAccountWithoutARiderSeesTheHint() = runTest(dispatcher) {
        backend.on("GET /api/riders", json("[]"))

        assertTrue(viewModel().settled().noRider)
    }

    // Accepting a request is a change too
    @Test
    fun trackingAnAcceptedRequestBumpsTheChanges() {
        val before = activity.changes.value
        activity.track(parse(conversation(busy = true)))
        assertTrue(activity.changes.value > before)
    }
}
