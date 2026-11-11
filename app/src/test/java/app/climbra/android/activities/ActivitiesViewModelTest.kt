package app.climbra.android.activities

import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 003 AC1, AC2, AC4: what the training log asks the backend for; rows and totals are the backend's. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivitiesViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val changes = DataChanges()

    /** Noon in UTC: the same day in every zone the tests may run in. */
    private val clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private fun viewModel() =
        ActivitiesViewModel(RiderRepository(backend.riderApi) { "Europe/Warsaw" }, backend.activityApi, changes, clock)

    private suspend fun ActivitiesViewModel.settled() = state.first { !it.loading && !it.loadingMore && !it.refreshing }

    private val rider = """[{"id":5,"name":"Anna","timeZone":"Europe/Warsaw"}]"""

    private fun row(id: Int) = """
        {"id":$id,"start":"2026-11-${10 - id % 9}T08:00:00","date":"2026-11-0${id % 9 + 1}","name":"Ride $id",
         "source":"GARMIN","type":"road_biking","duration":3600,"distance":30000.0,"if":0.84,"tss":70.2,
         "tssFromHeartRate":${id == 2},"intervals":3,"kj":700.0,"somethingNew":1}
    """.trimIndent()

    private fun page(ids: IntRange, totalRows: Int, hasRides: Boolean = true) = json(
        """{"from":"2026-10-15","to":"2026-11-11","rows":[${ids.joinToString(",") { row(it) }}],"page":0,"size":50,
           "totalRows":$totalRows,"totals":{"rides":$totalRows,"seconds":18000,"meters":151000.0,"elevation":600.0,
           "kj":3500.0,"tss":351.0},"types":["road_biking"],"hasRides":$hasRides}""",
    )

    private fun ridesRequests() = backend.requests("GET /api/riders/5/rides").map { it.url }

    // AC1, AC2
    @Test
    fun theFirstPageIsTheLast28DaysWithTheBackendsTotals() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/rides", page(1..3, totalRows = 3))

        val state = viewModel().settled()

        val url = ridesRequests().single()
        assertEquals("2026-10-15", url.queryParameter("from"))
        assertEquals("2026-11-11", url.queryParameter("to"))
        assertEquals("0", url.queryParameter("page"))
        assertEquals("50", url.queryParameter("size"))
        assertNull(url.queryParameter("q"))
        assertEquals(listOf(1L, 2L, 3L), state.rows.map { it.id })
        assertEquals(0.84, state.rows.first().intensityFactor!!, 0.0)
        assertTrue(state.rows[1].tssFromHeartRate)
        assertEquals(351.0, state.totals!!.tss, 0.0)
        assertEquals(3500.0, state.totals!!.kj, 0.0)
        assertFalse(state.hasMore)
        // Nothing of the whole history is loaded
        assertTrue(backend.requests("GET /api/riders/5/activities").isEmpty())
        assertTrue(backend.requests("GET /api/riders/5/analyses").isEmpty())
    }

    // AC1: the next page while scrolling, appended
    @Test
    fun theNextPageIsLoadedAtTheEndOfTheList() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/rides", page(1..50, totalRows = 60), page(51..60, totalRows = 60))
        val viewModel = viewModel()
        assertTrue(viewModel.settled().hasMore)

        viewModel.loadMore()
        val state = viewModel.settled()

        assertEquals(listOf("0", "1"), ridesRequests().map { it.queryParameter("page") })
        assertEquals(60, state.rows.size)
        assertFalse(state.hasMore)
        // Everything is loaded: no further request
        viewModel.loadMore()
        assertEquals(2, ridesRequests().size)
    }

    // AC2: presets and a custom range start again from page 0
    @Test
    fun aNewPeriodStartsFromTheFirstPage() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/rides", page(1..3, totalRows = 3))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.selectPreset(RangePreset.WEEK)
        viewModel.settled()
        viewModel.selectPreset(RangePreset.YEAR)
        viewModel.settled()
        viewModel.selectPreset(RangePreset.QUARTER)
        viewModel.settled()
        viewModel.selectCustom(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
        val state = viewModel.settled()
        // A reversed range is ignored
        viewModel.selectCustom(LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 1))

        val periods = ridesRequests().drop(1).map { "${it.queryParameter("from")}..${it.queryParameter("to")}" }
        assertEquals(
            listOf("2026-11-05..2026-11-11", "2026-01-01..2026-11-11", "2026-08-14..2026-11-11", "2026-03-01..2026-03-31"),
            periods,
        )
        assertTrue(ridesRequests().all { it.queryParameter("page") == "0" })
        assertEquals(RangePreset.CUSTOM, state.period.preset)
        assertEquals(5, ridesRequests().size)
    }

    // AC1: the search waits for a pause, then starts from page 0
    @Test
    fun theSearchIsSentAfterThePause() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/rides", page(1..3, totalRows = 3))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.onTextChange("vo")
        viewModel.onTextChange("vo2 ")
        assertEquals(1, ridesRequests().size)
        // Nothing else is pending, so runTest moves virtual time past the pause
        val searched = viewModel.state.first { ridesRequests().size == 2 && !it.loading }

        assertEquals("vo2", ridesRequests().last().queryParameter("q"))
        assertEquals("0", ridesRequests().last().queryParameter("page"))
        assertEquals("vo2 ", searched.text)
    }

    // AC4
    @Test
    fun aRiderWithoutAnyRideGetsTheImportHint() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/rides", page(IntRange.EMPTY, totalRows = 0, hasRides = false))

        val state = viewModel().settled()

        assertFalse(state.hasRides)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun anAccountWithoutRiderAndAFailedRequestAreShown() = runTest(dispatcher) {
        backend.on("GET /api/riders", json("[]"))
        assertTrue(viewModel().settled().noRider)

        TestBackend().use { other ->
            other.on("GET /api/riders", json(rider))
            other.on("GET /api/riders/5/rides", problem(503, null))
            val failing = ActivitiesViewModel(RiderRepository(other.riderApi) { "Europe/Warsaw" }, other.activityApi, changes, clock)
            assertEquals(app.climbra.android.data.net.ApiError.Http(503, null), failing.settled().error)
        }
    }

    // Spec 003 AC8, AC10: rides changed elsewhere load again when the log is shown
    @Test
    fun theLogLoadsAgainWhenRidesChanged() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(rider))
        backend.on("GET /api/riders/5/rides", page(1..3, totalRows = 3))
        val viewModel = viewModel()
        viewModel.settled()

        viewModel.onShown()
        assertEquals(1, ridesRequests().size)

        changes.changed()
        viewModel.onShown()
        viewModel.settled()
        assertEquals(2, ridesRequests().size)
    }
}
