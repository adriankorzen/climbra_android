package app.climbra.android.activities

import androidx.lifecycle.SavedStateHandle
import app.climbra.android.data.net.ApiError
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Spec 003 AC5 - AC12: the ride detail's requests and actions against the backend. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideDetailViewModelTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val changes = DataChanges()
    private val saved = mutableListOf<Pair<String, ByteArray>>()
    private var saveFails = false
    private val files = FileSaver { name, _, content ->
        if (saveFails) throw IOException("disk full")
        saved += name to content.readBytes()
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    private fun viewModel(id: Long = 85) = RideDetailViewModel(
        SavedStateHandle(mapOf("id" to id)),
        backend.activityApi,
        RiderRepository(backend.riderApi) { "Europe/Warsaw" },
        changes,
        files,
    )

    private suspend fun RideDetailViewModel.loaded() = state.first { !it.loading && !it.recordingLoading }

    private suspend fun RideDetailViewModel.idle() =
        state.first { !it.analysing && !it.deleting && !it.linking && !it.downloading }

    private fun activity(planned: Boolean = false) = json(
        """{"id":85,"source":"UPLOAD","externalId":"x","name":"Threshold","sportType":"road_biking",
           "startTime":"2026-11-08T07:00:00Z","startTimeLocal":"2026-11-08T08:00:00","distanceMeters":21600.0,
           "elevationGainMeters":null,"deviceName":"Edge 840","freeReviewAvailable":true,
           "plannedWorkout":${if (planned) PLANNED else "null"}}""",
    )

    private fun analysis(stored: Boolean = true, useLaps: Boolean = false, blocks: Int = 2) = json(
        """{"activityId":85,"stored":$stored,"analyzedAt":"2026-11-08T10:00:00Z","analyzerVersion":9,
           "thresholds":{"ftpWatts":260,"lthrBpm":165,"maxHeartRateBpm":185,"targetWatts":null,"effectiveFrom":"2026-01-01"},
           "analysis":{"sport":"cycling","durationSeconds":2700,"distanceMeters":21600.0,
             "power":{"averagePower":230.0,"maxPower":287.0,"normalizedPower":245.0,"variabilityIndex":1.07,
                      "intensityFactor":0.94,"trainingStressScore":67.0,"averagePowerPercentFtp":88.0,"workKilojoules":620.0},
             "heartRate":{"average":163.0,"max":165.0,"percentOfMax":88.0,"stressScore":72.0,"stressCoverage":1.0},
             "averageCadence":88.0,
             "powerZones":[{"number":2,"name":"Endurance","seconds":1500,"percent":56.0}],
             "heartRateZones":[],"heartRateZoneBasis":null,
             "intervals":{"detected":${blocks > 0},"mode":"AUTO","targetPower":280.0,"targetSource":"AUTO",
               "blocks":[${(1..blocks).joinToString(",") { block(it) }}],"unders":[],"recoveries":[],"sets":[],
               "structure":${if (blocks > 0) "\"INTERVALS\"" else "null"},"lapsAvailable":true,"useLaps":$useLaps},
             "trainingLoad":{"score":67.0,"source":"POWER"}}}""",
    )

    private fun block(number: Int) = """{"number":$number,"startSecond":${number * 600},"endSecond":${number * 600 + 300},
        "durationSeconds":300,"dropoutSeconds":0,"averagePower":285.0,"averagePowerExcludingZeros":null,"maxPower":300.0,
        "percentOfTarget":101.0,"heartRateStart":150.0,"heartRateEnd":165.0,"maxHeartRate":166.0,"efficiency":1.8,
        "vo2StimulusSeconds":null,"setNumber":null}"""

    private val recording = json(
        """{"resolutionSeconds":5,"startTime":"2026-11-08T07:00:00Z","samples":{"t":[0,5,10],
           "power":[200.0,null,300.0],"heartRate":[140.0,150.0,160.0],"cadence":[88.0,90.0,92.0],
           "speed":[8.0,9.0,10.0],"altitude":[null,null,null]}}""",
    )

    private fun standard(planned: Boolean = false) {
        backend.on("GET /api/activities/85", activity(planned))
        backend.on("GET /api/activities/85/analysis", analysis())
        backend.on("GET /api/activities/85/recording", recording)
        backend.on("GET /api/riders", json("""[{"id":5,"timeZone":"Europe/Warsaw"}]"""))
        backend.on("GET /api/riders/5/calendar", json(CALENDAR))
    }

    // AC5, AC6
    @Test
    fun loadsTheRideItsAnalysisAndThenTheRecordingAtFiveSeconds() = runTest(dispatcher) {
        standard()

        val state = viewModel().loaded()

        assertEquals("Threshold", state.activity!!.name)
        assertEquals("2026-01-01", state.analysis!!.thresholds.effectiveFrom)
        assertEquals(0.94, state.analysis!!.analysis.power!!.intensityFactor!!, 0.0)
        assertEquals(2, state.analysis!!.analysis.intervals!!.blocks.size)
        assertEquals("5", backend.requests("GET /api/activities/85/recording").single().url.queryParameter("resolution"))
        assertEquals(listOf(null, null, null), state.recording!!.samples.altitude)
        // The recording comes after the ride and the analysis
        val order = backend.requests.map { it.url.encodedPath }
        assertTrue(order.indexOf("/api/activities/85/recording") > order.indexOf("/api/activities/85/analysis"))
        // An ad hoc analysis is not asked for at first
        assertNull(backend.requests("GET /api/activities/85/analysis").single().url.query)
    }

    // AC12
    @Test
    fun aMissingRideIsNotFound() = runTest(dispatcher) {
        backend.on("GET /api/activities/85", problem(404, null))
        backend.on("GET /api/activities/85/analysis", problem(404, null))

        val state = viewModel().loaded()

        assertTrue(state.notFound)
        assertNull(state.error)
        assertTrue(backend.requests("GET /api/activities/85/recording").isEmpty())
    }

    // AC6
    @Test
    fun aRecordingThatCannotBeLoadedLeavesTheRestOfThePage() = runTest(dispatcher) {
        standard()
        backend.replace("GET /api/activities/85/recording", problem(503, "FILE_STORAGE_UNAVAILABLE"))

        val state = viewModel().loaded()

        assertNull(state.recording)
        assertNotNull(state.analysis)
    }

    // AC7
    @Test
    fun adHocDetectionSendsOnlyTheFilledFieldsAndResetNeedsNoRequest() = runTest(dispatcher) {
        standard()
        val viewModel = viewModel()
        viewModel.loaded()
        backend.replace("GET /api/activities/85/analysis", analysis(stored = false, blocks = 4))

        viewModel.onFormChange(DetectionForm(targetWatts = "280", thresholdPercent = "90"))
        viewModel.applyDetection()
        val adHoc = viewModel.idle()

        val url = backend.requests("GET /api/activities/85/analysis").last().url
        assertEquals(setOf("targetWatts", "relativeThreshold"), url.queryParameterNames)
        assertEquals("0.9", url.queryParameter("relativeThreshold"))
        assertFalse(adHoc.analysis!!.stored)
        assertEquals(4, adHoc.analysis!!.analysis.intervals!!.blocks.size)
        // Nothing is stored, so nothing else changed
        assertEquals(0, changes.version.value)

        val requests = backend.requests.size
        viewModel.resetDetection()
        val reset = viewModel.state.value
        assertTrue(reset.analysis!!.stored)
        assertEquals(2, reset.analysis!!.analysis.intervals!!.blocks.size)
        assertTrue(reset.form.isEmpty)
        assertEquals(requests, backend.requests.size)
    }

    // AC7
    @Test
    fun aFieldThatIsNotANumberIsMarkedAndNothingIsSent() = runTest(dispatcher) {
        standard()
        val viewModel = viewModel()
        viewModel.loaded()

        viewModel.onFormChange(DetectionForm(targetWatts = "a lot"))
        viewModel.applyDetection()

        assertTrue(viewModel.state.value.formInvalid)
        assertEquals(1, backend.requests("GET /api/activities/85/analysis").size)
    }

    // AC8
    @Test
    fun intervalSourceAndReanalyseStoreAndMarkTheDataChanged() = runTest(dispatcher) {
        standard()
        backend.on("PUT /api/activities/85/interval-source", analysis(useLaps = true, blocks = 5))
        backend.on("POST /api/activities/85/analysis", analysis(blocks = 3))
        val viewModel = viewModel()
        viewModel.loaded()

        viewModel.setIntervalSource(true)
        val switched = viewModel.idle()
        assertEquals("""{"useLaps":true}""", backend.requests("PUT /api/activities/85/interval-source").single().body!!.utf8())
        assertTrue(switched.analysis!!.analysis.intervals!!.useLaps)
        assertEquals(1, changes.version.value)

        viewModel.reanalyze()
        val reanalysed = viewModel.idle()
        assertEquals(1, backend.requests("POST /api/activities/85/analysis").size)
        assertEquals(3, reanalysed.analysis!!.analysis.intervals!!.blocks.size)
        assertEquals(2, changes.version.value)
    }

    // AC9
    @Test
    fun theFitFileGoesToDownloadsUnderItsName() = runTest(dispatcher) {
        standard()
        backend.on(
            "GET /api/activities/85/file",
            MockResponse.Builder()
                .headers(headersOf("Content-Disposition", "attachment; filename=\"activity-85.fit\"", "Content-Type", "application/octet-stream"))
                .body("FIT-BYTES")
                .build(),
        )
        val viewModel = viewModel()
        viewModel.loaded()

        viewModel.download()
        val state = viewModel.idle()

        assertEquals("activity-85.fit", saved.single().first)
        assertEquals("FIT-BYTES", saved.single().second.decodeToString())
        assertEquals(RideMessage.Saved("activity-85.fit"), state.message)
        viewModel.messageShown()
        assertNull(viewModel.state.value.message)
    }

    // AC9
    @Test
    fun aFailedDownloadShowsTheError() = runTest(dispatcher) {
        standard()
        backend.on("GET /api/activities/85/file", problem(503, "FILE_STORAGE_UNAVAILABLE"))
        val viewModel = viewModel()
        viewModel.loaded()

        viewModel.download()
        assertEquals(RideMessage.Failed(ApiError.Http(503, "FILE_STORAGE_UNAVAILABLE")), viewModel.idle().message)

        backend.replace("GET /api/activities/85/file", MockResponse.Builder().body("FIT").build())
        saveFails = true
        viewModel.download()
        assertEquals(RideMessage.SaveFailed, viewModel.idle().message)
    }

    // AC10
    @Test
    fun deletingGoesBackAndMarksTheDataChanged() = runTest(dispatcher) {
        standard()
        backend.on("DELETE /api/activities/85", noContent())
        val viewModel = viewModel()
        viewModel.loaded()

        viewModel.delete()
        val state = viewModel.idle()

        assertTrue(state.deleted)
        assertEquals(1, backend.requests("DELETE /api/activities/85").size)
        assertEquals(1, changes.version.value)
    }

    // AC10
    @Test
    fun aRideWithARunningCoachRequestStays() = runTest(dispatcher) {
        standard()
        backend.on("DELETE /api/activities/85", problem(409, "ACTIVITY_BUSY"))
        val viewModel = viewModel()
        viewModel.loaded()

        viewModel.delete()
        val state = viewModel.idle()

        assertFalse(state.deleted)
        assertEquals(RideMessage.Failed(ApiError.Http(409, "ACTIVITY_BUSY")), state.message)
        assertEquals(0, changes.version.value)
    }

    // AC11
    @Test
    fun anUnplannedRideOffersTheOpenWorkoutsOfThreeDaysAround() = runTest(dispatcher) {
        standard()

        val state = viewModel().state.first { it.candidates.isNotEmpty() }

        val calendar = backend.requests("GET /api/riders/5/calendar").single().url
        assertEquals("2026-11-05", calendar.queryParameter("from"))
        assertEquals("2026-11-11", calendar.queryParameter("to"))
        // Neither rest days nor completed workouts
        assertEquals(listOf(31L, 34L), state.candidates.map { it.id })
        assertNull(state.activity!!.plannedWorkout)
    }

    // AC11
    @Test
    fun linkingReloadsTheRideWithItsWorkout() = runTest(dispatcher) {
        standard()
        backend.on("PUT /api/riders/5/planned-workouts/31/activity", json("{}"))
        val viewModel = viewModel()
        viewModel.state.first { it.candidates.isNotEmpty() }
        backend.replace("GET /api/activities/85", activity(planned = true))

        viewModel.link(31)
        val state = viewModel.idle()

        assertEquals("""{"activityId":85}""", backend.requests("PUT /api/riders/5/planned-workouts/31/activity").single().body!!.utf8())
        assertEquals("FTP 2x20", state.activity!!.plannedWorkout!!.title)
        assertEquals(app.climbra.android.data.api.ComplianceLevel.MODERATE, state.activity!!.plannedWorkout!!.comparison!!.level)
        assertTrue(state.candidates.isEmpty())
        assertEquals(1, changes.version.value)
    }

    // AC11
    @Test
    fun aRefusedLinkShowsItsError() = runTest(dispatcher) {
        standard()
        backend.on("PUT /api/riders/5/planned-workouts/31/activity", problem(409, "WORKOUT_COMPLETED"))
        val viewModel = viewModel()
        viewModel.state.first { it.candidates.isNotEmpty() }

        viewModel.link(31)

        assertEquals(ApiError.Http(409, "WORKOUT_COMPLETED"), viewModel.idle().linkError)
    }

    private companion object {
        const val PLANNED = """{"id":31,"planId":7,"date":"2026-11-08","title":"FTP 2x20","type":"THRESHOLD",
            "plannedDurationMinutes":75,"plannedTss":85,"plannedIf":0.88,"matchSource":"MANUAL",
            "comparison":{"level":"MODERATE","duration":{"planned":4500.0,"actual":2700.0,"ratio":0.6,"level":"LARGE"},
              "tss":{"planned":85.0,"actual":67.0,"ratio":0.79,"level":"MODERATE"},
              "intensityFactor":{"planned":0.88,"actual":0.94,"delta":0.06,"level":"OK"},
              "intervals":{"plannedRepetitions":2,"detectedBlocks":2,"ratio":1.0,"level":"OK"},"tssSource":"POWER"}}"""

        const val CALENDAR = """{"from":"2026-11-05","to":"2026-11-11","plans":[],"activities":[],"plannedWorkouts":[
            {"id":30,"date":"2026-11-06","title":"Rest","type":"REST","status":"REST"},
            {"id":31,"date":"2026-11-08","title":"FTP 2x20","type":"THRESHOLD","status":"MISSED"},
            {"id":32,"date":"2026-11-09","title":"Done","type":"ENDURANCE","status":"COMPLETED"},
            {"id":34,"date":"2026-11-10","title":"Tempo","type":"TEMPO","status":"PLANNED"}]}"""
    }

    // Spec 018 AC4: a ride opened before shows offline with its analysis and recording
    @Test
    fun withoutTheBackendARideOpenedBeforeShowsItsSavedData() = runTest(dispatcher) {
        standard()
        val online = viewModel().loaded()

        backend.goOffline()
        val offline = viewModel().loaded()

        assertEquals(online.activity, offline.activity)
        assertEquals(online.analysis, offline.analysis)
        assertEquals(online.recording, offline.recording)
        assertNull(offline.error)
        // A ride never opened has nothing saved
        assertNotNull(viewModel(id = 86).state.first { !it.loading }.error)
    }
}
