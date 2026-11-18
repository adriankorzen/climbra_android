package app.climbra.android.activities

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.R
import app.climbra.android.coach.FakeFileSource
import app.climbra.android.coach.PickedFile
import app.climbra.android.data.api.ImportItemStatus
import app.climbra.android.data.api.ImportJobStatus
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.common.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A job as the backend writes it (`ActivityImportJobResponse`), with fields the app does not read. */
object ImportFixtures {
    fun job(
        status: String,
        processed: Int = 0,
        selected: Int? = null,
        imported: Int = 0,
        items: String = "[]",
        message: String? = null,
    ) = """
        {"id":31,"riderId":5,"kind":"UPLOAD","status":"$status","createdAt":"2026-11-18T10:00:00Z","finishedAt":null,
         "totalFiles":4,"selectedRides":${selected ?: "null"},"processedFiles":$processed,"imported":$imported,
         "duplicates":1,"skipped":1,"overLimit":0,"failed":1,"unavailable":0,"ignoredEntries":2,
         "message":${message?.let { "\"$it\"" } ?: "null"},"firstRideDate":"2026-10-02","lastRideDate":"2026-11-15",
         "rangeFrom":null,"rangeTo":null,"items":$items,"somethingNew":true}
    """.trimIndent()

    const val ITEMS = """[
        {"fileName":"a.fit","externalId":null,"status":"IMPORTED","activityId":70,"startTime":"2026-11-15T08:00:00Z","message":null},
        {"fileName":"b.fit","externalId":null,"status":"DUPLICATE","activityId":12,"startTime":null,"message":"Already stored as activity 12"},
        {"fileName":"run.fit","externalId":null,"status":"NOT_CYCLING","activityId":null,"startTime":null,"message":null},
        {"fileName":"broken.fit","externalId":null,"status":"SOMETHING_NEW","activityId":null,"startTime":null,"message":"Bad header"}]"""
}

/** Spec 009 AC6, AC7, AC9: the app's import of several rides. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideImportTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val changes = DataChanges()
    private val session: SessionRepository = backend.session(scope)
    private val files = FakeFileSource(mapOf("content://a.fit" to byteArrayOf(1, 2), "content://rides.zip" to byteArrayOf(3)))
    private val rideImport = backend.rideImport(session, scope, files, changes, pollMillis = 10)
    private val notices = mutableListOf<UiText>()

    init {
        scope.launch { rideImport.notices.toList(notices) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        backend.close()
    }

    private val chosen = listOf(PickedFile("content://a.fit", "a.fit"), PickedFile("content://rides.zip", "rides.zip"))
    private val startRoute = "POST /api/riders/5/imports"
    private val pollRoute = "GET /api/imports/31"

    // AC6: every file and the time zone; AC7: polled until it ends; AC9: data changed, a notice when not shown
    @Test
    fun sendsTheFilesAndFollowsTheJobToItsEnd() = runTest(dispatcher) {
        backend.on(startRoute, json(ImportFixtures.job("QUEUED"), 202))
        backend.on(
            pollRoute,
            json(ImportFixtures.job("RUNNING")),
            problem(503, null),
            json(ImportFixtures.job("RUNNING", processed = 1, selected = 3)),
            json(ImportFixtures.job("COMPLETED", processed = 3, selected = 3, imported = 2, items = ImportFixtures.ITEMS)),
        )
        val version = changes.version.value

        val result = rideImport.start(5, chosen, "Europe/Warsaw")
        assertEquals(StartResult.Started, result)
        rideImport.join()

        val request = backend.requests(startRoute).single()
        assertEquals("Europe/Warsaw", request.url.queryParameter("timeZone"))
        val body = request.body!!.utf8()
        assertTrue(body.contains("""name="files"; filename="a.fit""""))
        assertTrue(body.contains("""name="files"; filename="rides.zip""""))
        // A failed poll is asked again
        assertEquals(4, backend.requests(pollRoute).size)
        assertEquals("true", backend.requests(pollRoute).first().url.queryParameter("items"))
        val job = rideImport.job.value!!
        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertEquals(listOf(ImportItemStatus.IMPORTED, ImportItemStatus.DUPLICATE, ImportItemStatus.NOT_CYCLING, ImportItemStatus.FAILED), job.items.map { it.status })
        assertEquals(version + 1, changes.version.value)
        assertEquals(listOf(UiText(R.plurals.import_done, listOf(2), quantity = 2)), notices)
    }

    // AC9: shown, the screen tells the end itself; nothing imported, no data change
    @Test
    fun aShownScreenGetsNoNoticeAndNothingImportedChangesNothing() = runTest(dispatcher) {
        backend.on(startRoute, json(ImportFixtures.job("RUNNING"), 202))
        backend.on(pollRoute, json(ImportFixtures.job("FAILED", message = "Storage down")))
        rideImport.shown = true
        val version = changes.version.value

        rideImport.start(5, chosen, "UTC")
        rideImport.join()

        assertEquals(ImportJobStatus.FAILED, rideImport.job.value!!.status)
        assertEquals(version, changes.version.value)
        assertTrue(notices.isEmpty())
    }

    // AC9: the failed end's notice
    @Test
    fun aFailedImportIsTold() = runTest(dispatcher) {
        backend.on(startRoute, json(ImportFixtures.job("FAILED", message = "Storage down"), 202))

        rideImport.start(5, chosen, "UTC")
        rideImport.join()

        assertEquals(listOf(UiText(R.string.import_failed, listOf("Storage down"))), notices)
    }

    // AC6: a refused start leaves nothing started
    @Test
    fun aRefusedStartLeavesNoJob() = runTest(dispatcher) {
        backend.on(startRoute, problem(409, "IMPORT_RUNNING"))

        val result = rideImport.start(5, chosen, "UTC")

        assertEquals(ApiError.Http(409, "IMPORT_RUNNING"), (result as StartResult.Failed).error)
        assertNull(rideImport.job.value)
    }

    // AC8: "Import more" forgets a finished job only
    @Test
    fun clearForgetsOnlyAFinishedJob() = runTest(dispatcher) {
        backend.on(startRoute, json(ImportFixtures.job("COMPLETED"), 202))
        rideImport.start(5, chosen, "UTC")
        rideImport.join()

        rideImport.clear()

        assertNull(rideImport.job.value)
    }

    // AC9: logout forgets the import
    @Test
    fun logoutForgetsTheImport() = runTest(dispatcher) {
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        backend.on("POST /api/auth/logout", noContent())
        session.login("anna@example.com", "correct-horse-1")
        backend.on(startRoute, json(ImportFixtures.job("RUNNING"), 202))
        rideImport.start(5, chosen, "UTC")
        assertTrue(rideImport.job.value!!.running)

        session.logout()

        rideImport.job.first { it == null }
        rideImport.join()
    }
}

/** Spec 009 AC2, AC10: what is a ride file, and the files other apps share. */
@RunWith(AndroidJUnit4::class)
class SharedRideFilesTest {

    // AC2
    @Test
    fun onlyRideFilesAreImportedCaseIgnored() {
        listOf("ride.fit", "RIDE.FIT", "a.tcx", "strava.fit.gz", "export.ZIP").forEach { assertTrue(it, isImportFile(it)) }
        listOf("notes.txt", "ride.gpx", "fit", "photo.jpg").forEach { assertTrue(it, !isImportFile(it)) }
    }

    // AC10: one file, several files and "Open with"; only content URIs
    @Test
    fun sharedIntentsGiveTheirContentUris() {
        val one = Uri.parse("content://garmin/ride.fit")
        val two = Uri.parse("content://files/b.tcx")
        assertEquals(listOf(one), sharedUris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, one)))
        assertEquals(
            listOf(one, two),
            sharedUris(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two))),
        )
        assertEquals(listOf(one), sharedUris(Intent(Intent.ACTION_VIEW, one)))
        assertEquals(emptyList<Uri>(), sharedUris(Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/ride.fit"))))
        assertEquals(emptyList<Uri>(), sharedUris(Intent(Intent.ACTION_MAIN)))
        assertEquals(emptyList<Uri>(), sharedUris(null))

        val files = FakeFileSource().sharedFiles(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, one))
        assertEquals(listOf(PickedFile("content://garmin/ride.fit", "ride.fit")), files)
    }

    // AC10: kept until taken; nothing that is a ride only tells so
    @Test
    fun ridesWaitUntilTakenOtherFilesAreTold() = runTest {
        val shared = SharedRideFiles()
        val notices = mutableListOf<UiText>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { shared.notices.toList(notices) }

        shared.offer(listOf(PickedFile("content://x/photo.jpg", "photo.jpg")))
        assertNull(shared.pending.value)
        assertEquals(listOf(UiText(R.string.import_share_unsupported)), notices)

        val files = listOf(PickedFile("content://x/a.fit", "a.fit"), PickedFile("content://x/notes.txt", "notes.txt"))
        shared.offer(files)
        assertEquals(files, shared.pending.value)
        assertEquals(files, shared.take())
        assertNull(shared.pending.value)
        assertNull(shared.take())
        collecting.cancel()
    }
}
