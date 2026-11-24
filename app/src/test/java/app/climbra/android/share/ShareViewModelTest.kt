package app.climbra.android.share

import app.climbra.android.R
import app.climbra.android.data.api.ShareKind
import app.climbra.android.data.api.ShareRequest
import app.climbra.android.data.net.Network
import app.climbra.android.rider.RiderRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Spec 028 AC6: the link the share sheet asks the backend for (cyclingapp spec 062). */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private fun viewModel() = ShareViewModel(RiderRepository(backend.riderApi) { "Europe/Warsaw" }, backend.shareApi)

    private val rider = """[{"id":5,"name":"Anna","weightKg":60.0,"timeZone":"Europe/Warsaw"}]"""
    private val created = """{"id":3,"kind":"RIDE","url":"https://climbra.app/s/abc","expiresAt":"2026-12-09T10:00:00Z","createdAt":"x"}"""

    private fun sent(): ShareRequest =
        Network.json.decodeFromString(ShareRequest.serializer(), backend.requests("POST /api/riders/5/shares").last().body!!.utf8())

    private suspend fun ShareViewModel.settled() = state.first { !it.creating }

    @Test
    fun createsARideLinkWithTheSwitchesAndHandsItOver() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("POST /api/riders/5/shares", json(created, code = 201))
        val viewModel = viewModel()
        viewModel.open(ShareTarget.ride(7, "Morning ride", hasPower = true, hasHeartRate = true))
        viewModel.setHidePower(true)

        viewModel.createLink()
        val state = viewModel.settled()

        assertEquals(ShareRequest(ShareKind.RIDE, activityId = 7, hidePower = true, hideHeartRate = false), sent())
        assertEquals("https://climbra.app/s/abc", state.pendingLink!!.url)
        assertEquals("2026-12-09T10:00:00Z", state.created!!.expiresAt)
        viewModel.linkHandedOver()
        assertNull(viewModel.state.value.pendingLink)
        assertEquals("https://climbra.app/s/abc", viewModel.state.value.created!!.url)
    }

    @Test
    fun createsAWeekLinkWithItsMondayAndHidesNothingTheRideDoesNotHave() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("POST /api/riders/5/shares", json(created, code = 201), json(created, code = 201))
        val viewModel = viewModel()

        viewModel.open(ShareTarget.week("2026-11-16", "Week 16 Nov – 22 Nov"))
        viewModel.createLink()
        viewModel.settled()
        assertEquals(ShareRequest(ShareKind.WEEK, weekStart = "2026-11-16"), sent())

        viewModel.open(ShareTarget.ride(8, "Indoor", hasPower = false, hasHeartRate = true))
        viewModel.setHidePower(true)
        viewModel.createLink()
        viewModel.settled()
        assertEquals(ShareRequest(ShareKind.RIDE, activityId = 8), sent())
    }

    @Test
    fun translatesTheLimitOfActiveLinks() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("POST /api/riders/5/shares", problem(409, "SHARE_LIMIT_REACHED"))
        val viewModel = viewModel()
        viewModel.open(ShareTarget.week("2026-11-16", "Week"))

        viewModel.createLink()
        val state = viewModel.settled()

        assertEquals(R.string.api_error_share_limit_reached, state.error!!.res)
        assertNull(state.pendingLink)
    }

    @Test
    fun opensWithTheSwitchesOffAndClosesEmpty() {
        val viewModel = viewModel()
        viewModel.open(ShareTarget.week("2026-11-16", "Week"))
        viewModel.setHidePower(true)
        viewModel.setHideHeartRate(true)

        viewModel.open(ShareTarget.week("2026-11-23", "Week"))
        assertFalse(viewModel.state.value.hidePower)
        assertFalse(viewModel.state.value.hideHeartRate)
        viewModel.close()
        assertNull(viewModel.state.value.target)
    }
}
