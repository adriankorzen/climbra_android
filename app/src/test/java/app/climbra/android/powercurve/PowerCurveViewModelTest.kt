package app.climbra.android.powercurve

import androidx.lifecycle.SavedStateHandle
import app.climbra.android.rider.DataChanges
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Spec 026: what the power curve requests and keeps; the figures are the backend's (cyclingapp spec 061). */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerCurveViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val changes = DataChanges()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        PowerCurveViewModel(saved, RiderRepository(backend.riderApi) { "Europe/Warsaw" }, backend.riderApi, changes)

    private suspend fun PowerCurveViewModel.settled() = state.first { !it.loading && !it.refreshing }

    private val rider = """[{"id":5,"name":"Anna","weightKg":60.0,"timeZone":"Europe/Warsaw"}]"""

    private fun curve(days: String, watts: Int) = """
        {"days":$days,"current":{"from":"2026-08-26","to":"2026-11-23",
          "points":[{"durationSeconds":5,"watts":$watts,"wattsPerKg":16.87,"activityId":7,"date":"2026-10-04","changeWatts":35}]},
         "previous":${if (days == "null") "null" else """{"from":"2026-05-28","to":"2026-08-25","points":[]}"""},
         "ridesWithoutCurve":2,"addedLater":true}
    """.trimIndent()

    private fun days() = backend.requests("GET /api/riders/5/power-curve").map { it.url.queryParameter("days") }

    // AC1: 90 days first, another period on a switch, all with one curve
    @Test
    fun loadsNinetyDaysFirstAndThePeriodChosen() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on(
            "GET /api/riders/5/power-curve",
            json(curve("90", 1012)), json(curve("28", 950)), json(curve("null", 1100)),
        )
        val viewModel = viewModel()

        val first = viewModel.settled()
        assertEquals(CurvePeriod.DAYS_90, first.period)
        assertEquals(1012, first.curve!!.current.points.single().watts)
        assertEquals(35, first.curve!!.current.points.single().changeWatts)
        assertEquals(2, first.curve!!.ridesWithoutCurve)

        viewModel.select(CurvePeriod.DAYS_28)
        assertEquals(950, viewModel.settled().curve!!.current.points.single().watts)
        viewModel.select(CurvePeriod.ALL)
        val all = viewModel.settled()
        assertNull(all.curve!!.previous)
        assertNull(all.curve!!.days)
        assertEquals(listOf("90", "28", "all"), days())
    }

    // AC5: an error with Retry, then the curve
    @Test
    fun aFailureShowsAnErrorUntilRetried() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/power-curve", problem(500, null), json(curve("90", 1012)))
        val viewModel = viewModel()

        val failed = viewModel.settled()
        assertNotNull(failed.error)
        assertNull(failed.curve)

        viewModel.refresh()
        val loaded = viewModel.settled()
        assertNull(loaded.error)
        assertEquals(1012, loaded.curve!!.current.points.single().watts)
    }

    // AC5: offline, the saved answer of the period (spec 018)
    @Test
    fun offlineTheSavedAnswerIsShown() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/power-curve", json(curve("90", 1012)))
        viewModel().settled()

        backend.goOffline()
        val offline = viewModel().settled()

        assertNull(offline.error)
        assertEquals(1012, offline.curve!!.current.points.single().watts)
        // A period never loaded has nothing saved
        val other = viewModel(SavedStateHandle(mapOf("period" to CurvePeriod.YEAR.name))).settled()
        assertNotNull(other.error)
    }

    // AC8: the period survives a configuration change (the SavedStateHandle)
    @Test
    fun thePeriodIsKeptInTheSavedState() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/power-curve", json(curve("365", 1012)))
        val saved = SavedStateHandle()
        val viewModel = viewModel(saved)
        viewModel.settled()

        viewModel.select(CurvePeriod.YEAR)
        viewModel.settled()
        assertEquals(CurvePeriod.YEAR.name, saved.get<String>("period"))

        val recreated = viewModel(saved).settled()
        assertEquals(CurvePeriod.YEAR, recreated.period)
        assertEquals("365", days().last())
    }
}
