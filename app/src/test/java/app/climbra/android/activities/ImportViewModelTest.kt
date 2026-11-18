package app.climbra.android.activities

import app.climbra.android.coach.CoachFixtures.RIDERS
import app.climbra.android.coach.FakeFileSource
import app.climbra.android.coach.PickedFile
import app.climbra.android.data.net.ApiError
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.session.SessionRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf

/** Spec 009 AC3 - AC6, AC10, AC11: the import screen's single ride, selection and shared files. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val changes = DataChanges()
    private val session: SessionRepository = backend.session(scope)
    private val files = FakeFileSource(
        files = mapOf("content://x/ride.fit" to byteArrayOf(1, 2, 3), "content://x/b.tcx" to byteArrayOf(4)),
        sizes = mapOf("content://x/huge.zip" to 51L * 1024 * 1024),
    )
    private val rideImport = backend.rideImport(session, scope, files, changes, pollMillis = 10)
    private val shared = SharedRideFiles()
    private val viewModels = mutableListOf<ImportViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        Dispatchers.resetMain()
        backend.close()
    }

    private fun viewModel() = ImportViewModel(
        RiderRepository(backend.riderApi) { "Europe/Warsaw" }, backend.importApi, rideImport, files, shared, changes,
    ).also {
        it.zone = ZoneId.of("Europe/Warsaw")
        viewModels += it
    }

    private suspend fun ImportViewModel.loaded() = state.first { !it.loading }

    private val uploadRoute = "POST /api/riders/5/activities/upload"
    private val importRoute = "POST /api/riders/5/imports"

    // AC3: the file, the trimmed name and the time zone; stored, data changed and the ride opens
    @Test
    fun aSingleRideIsUploadedAndOpened() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on(uploadRoute, json("""{"id":88,"name":"Morning","somethingElse":1}""", 201))
        val viewModel = viewModel()
        viewModel.loaded()
        assertFalse(viewModel.state.value.canAdd)
        val version = changes.version.value

        viewModel.chooseSingle("content://x/ride.fit")
        viewModel.setName("  Morning loop  ")
        viewModel.addRide()

        assertEquals(88L, viewModel.state.first { it.added != null }.added)
        val request = backend.requests(uploadRoute).single()
        assertEquals("Morning loop", request.url.queryParameter("name"))
        assertEquals("Europe/Warsaw", request.url.queryParameter("timeZone"))
        assertTrue(request.body!!.utf8().contains("""name="file"; filename="ride.fit""""))
        assertEquals(version + 1, changes.version.value)
        viewModel.addedShown()
        assertNull(viewModel.state.value.added)
    }

    // AC3: a blank name is not sent; AC2: another file is named as skipped
    @Test
    fun aBlankNameIsNotSentAndOtherFilesAreSkipped() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on(uploadRoute, json("""{"id":88}""", 201))
        val viewModel = viewModel()
        viewModel.loaded()

        viewModel.chooseSingle("content://x/notes.txt")
        assertNull(viewModel.state.value.single)
        assertEquals(listOf("notes.txt"), viewModel.state.value.rejected)

        viewModel.chooseSingle("content://x/ride.fit")
        viewModel.setName("   ")
        viewModel.addRide()

        viewModel.state.first { it.added != null }
        assertNull(backend.requests(uploadRoute).single().url.queryParameter("name"))
    }

    // AC4: a duplicate names its ride; the errors keep the file and name
    @Test
    fun refusedUploadsKeepTheFileAndName() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on(
            uploadRoute,
            MockResponse.Builder().code(409).headers(headersOf("Content-Type", "application/problem+json"))
                .body("""{"type":"about:blank","title":"Conflict","status":409,"detail":"Stored","activityId":12}""").build(),
            problem(413, null),
            problem(422, null),
            problem(503, "FILE_PROCESSING_BUSY"),
        )
        val viewModel = viewModel()
        viewModel.loaded()
        viewModel.chooseSingle("content://x/ride.fit")
        viewModel.setName("Loop")

        val errors = (1..4).map {
            viewModel.addRide()
            viewModel.state.first { it.error != null && !it.sending }.error as ApiError.Http
        }

        assertEquals(12L, errors[0].activityId)
        assertEquals(listOf(409, 413, 422, 503), errors.map { it.status })
        assertEquals("FILE_PROCESSING_BUSY", errors[3].code)
        assertEquals("ride.fit", viewModel.state.value.single!!.name)
        assertEquals("Loop", viewModel.state.value.name)
        assertNull(viewModel.state.value.added)
    }

    // AC5: no doubles, unsupported named, sizes summed; over 50 MB no Import
    @Test
    fun theSelectionListsFilesOnceAndTellsOver50Mb() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        val viewModel = viewModel()
        viewModel.loaded()
        viewModel.setMode(ImportMode.SEVERAL)

        viewModel.addFiles(listOf("content://x/ride.fit", "content://x/notes.txt", "content://x/ride.fit"))
        viewModel.addFiles(listOf("content://x/b.tcx", "content://y/ride.fit"))

        var state = viewModel.state.value
        assertEquals(listOf("ride.fit", "b.tcx"), state.files.map { it.file.name })
        assertEquals(4L, state.totalBytes)
        assertEquals(emptyList<String>(), state.rejected)
        assertTrue(state.canImport)

        viewModel.addFiles(listOf("content://x/huge.zip"))
        state = viewModel.state.value
        assertTrue(state.overLimit)
        // Spec 014 AC8: more than 50 MB goes in batches
        assertTrue(state.canImport)

        viewModel.removeFile(state.files.last())
        assertTrue(viewModel.state.value.canImport)
    }

    // AC6: the import starts the app's job; refusals keep the selection
    @Test
    fun importStartsTheJobAndRefusalsKeepTheSelection() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on(importRoute, problem(409, "IMPORT_RUNNING"), problem(413, null), problem(503, "IMPORT_QUEUE_FULL"), json(ImportFixtures.job("COMPLETED"), 202))
        val viewModel = viewModel()
        viewModel.loaded()
        viewModel.setMode(ImportMode.SEVERAL)
        viewModel.addFiles(listOf("content://x/ride.fit", "content://x/b.tcx"))

        val errors = (1..3).map {
            viewModel.startImport()
            viewModel.state.first { it.error != null && !it.sending }.error as ApiError.Http
        }
        assertEquals(listOf("IMPORT_RUNNING", null, "IMPORT_QUEUE_FULL"), errors.map { it.code })
        assertEquals(2, viewModel.state.value.files.size)

        viewModel.startImport()
        val state = viewModel.state.first { it.job != null && !it.sending }
        assertTrue(state.files.isEmpty())
        assertNull(state.error)
        assertEquals("Europe/Warsaw", backend.requests(importRoute).last().url.queryParameter("timeZone"))
        rideImport.join()

        // AC8: "Import more" starts over
        viewModel.importMore()
        yield()
        assertNull(viewModel.state.value.job)
    }

    // AC9: coming back shows the running import
    @Test
    fun aNewScreenShowsTheRunningImport() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on(importRoute, json(ImportFixtures.job("RUNNING", processed = 1, selected = 4), 202))
        backend.on("GET /api/imports/31", json(ImportFixtures.job("RUNNING", processed = 1, selected = 4)))
        rideImport.start(5, listOf(PickedFile("content://x/ride.fit", "ride.fit")), "UTC")

        val state = viewModel().loaded()

        assertEquals(ImportMode.SEVERAL, state.mode)
        assertTrue(state.running)
        assertFalse(state.canImport)
    }

    // AC10: one shared file is a single ride, several the selection
    @Test
    fun sharedFilesAreChosen() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        shared.offer(listOf(PickedFile("content://x/ride.fit", "ride.fit")))
        val viewModel = viewModel()

        var state = viewModel.loaded()
        assertEquals(ImportMode.SINGLE, state.mode)
        assertEquals("ride.fit", state.single!!.name)
        assertNull(shared.pending.value)

        shared.offer(listOf(PickedFile("content://x/ride.fit", "ride.fit"), PickedFile("content://x/b.tcx", "b.tcx"), PickedFile("content://x/a.txt", "a.txt")))
        yield()
        state = viewModel.state.value
        assertEquals(ImportMode.SEVERAL, state.mode)
        assertEquals(listOf("ride.fit", "b.tcx"), state.files.map { it.file.name })
        assertEquals(listOf("a.txt"), state.rejected)
    }

    // AC10: while an import runs, shared files for several rides are told so
    @Test
    fun sharedFilesWaitForARunningImport() = runTest(dispatcher) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on(importRoute, json(ImportFixtures.job("RUNNING"), 202))
        backend.on("GET /api/imports/31", json(ImportFixtures.job("RUNNING")))
        val viewModel = viewModel()
        viewModel.loaded()
        rideImport.start(5, listOf(PickedFile("content://x/ride.fit", "ride.fit")), "UTC")

        shared.offer(listOf(PickedFile("content://x/ride.fit", "ride.fit"), PickedFile("content://x/b.tcx", "b.tcx")))
        yield()

        val state = viewModel.state.value
        assertTrue(state.sharedWhileRunning)
        assertTrue(state.files.isEmpty())
    }

    // AC11: no rider, no form
    @Test
    fun anAccountWithoutARiderCannotImport() = runTest(dispatcher) {
        backend.on("GET /api/riders", json("[]"))
        val viewModel = viewModel()

        val state = viewModel.loaded()
        viewModel.chooseSingle("content://x/ride.fit")

        assertTrue(state.noRider)
        assertFalse(viewModel.state.value.canAdd)
    }
}
