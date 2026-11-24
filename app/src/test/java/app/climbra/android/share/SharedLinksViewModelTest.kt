package app.climbra.android.share

import app.climbra.android.rider.RiderRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 028 AC7: the active links are listed and revoked after a confirmation; read offline from the saved answer. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedLinksViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private val rider = """[{"id":5,"name":"Anna","weightKg":60.0,"timeZone":"Europe/Warsaw"}]"""
    private val links = """[
        {"id":4,"kind":"WEEK","weekStart":"2026-11-16","hidePower":false,"hideHeartRate":true,"createdAt":"2026-11-24T10:00:00Z","expiresAt":"2026-12-09T10:00:00Z"},
        {"id":3,"kind":"RIDE","activityId":85,"activityName":"Threshold","activityDate":"2026-11-08","hidePower":true,"hideHeartRate":false,"createdAt":"2026-11-23T10:00:00Z","expiresAt":"2026-12-08T10:00:00Z"}
    ]"""
    private val one = """[{"id":4,"kind":"WEEK","weekStart":"2026-11-16","expiresAt":"2026-12-09T10:00:00Z"}]"""

    private fun viewModel() = SharedLinksViewModel(RiderRepository(backend.riderApi) { "Europe/Warsaw" }, backend.shareApi)

    private suspend fun SharedLinksViewModel.settled() = state.first { !it.loading && !it.revoking }

    @Test
    fun listsTheLinksAndRevokesOneAfterConfirmation() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/shares", json(links), json(one))
        backend.on("DELETE /api/riders/5/shares/3", noContent())
        val viewModel = viewModel()

        viewModel.load()
        val listed = viewModel.settled()
        assertEquals(listOf(4L, 3L), listed.links.map { it.id })
        assertEquals("Threshold", listed.links[1].activityName)

        viewModel.askRevoke(listed.links[1])
        assertEquals(3L, viewModel.state.value.confirming!!.id)
        assertTrue(backend.requests("DELETE /api/riders/5/shares/3").isEmpty())
        viewModel.confirmRevoke()
        val after = viewModel.settled()

        assertEquals(1, backend.requests("DELETE /api/riders/5/shares/3").size)
        assertNull(after.confirming)
        assertTrue(after.revoked)
        assertEquals(listOf(4L), after.links.map { it.id })
    }

    @Test
    fun readsTheSavedLinksOffline() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/shares", json(links))
        viewModel().apply { load() }.settled()

        backend.goOffline()
        val offline = viewModel().apply { load() }.settled()

        assertNull(offline.error)
        assertEquals(listOf(4L, 3L), offline.links.map { it.id })
    }
}
