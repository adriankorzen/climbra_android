package app.climbra.android.activities

import app.climbra.android.R
import app.climbra.android.coach.FakeFileSource
import app.climbra.android.coach.PickedFile
import app.climbra.android.data.api.ImportItemStatus
import app.climbra.android.data.api.ImportJobStatus
import app.climbra.android.data.net.ApiError
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.noContent
import app.climbra.android.testing.TestBackend.Companion.problem
import app.climbra.android.ui.common.UiText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds archives in memory: entries are names with bytes, or nested archives. */
private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

private fun ride(n: Int) = "FIT ride $n ".repeat(20).toByteArray()

/** The entry names of a ZIP. */
private fun names(bytes: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    generateSequence { zip.nextEntry }.map { it.name }.toList()
}

/** Spec 014 AC1, AC2: what is read from the chosen files and how it is divided. */
class ExportReaderTest {

    // AC1: loose, gzipped and nested up to 3 deep; the rest ignored
    @Test
    fun rideFilesAreFoundLooseGzippedAndNested() = runTest {
        val deepest = zip("e.fit" to ride(5))
        val deeper = zip("d.fit" to ride(4), "deepest.zip" to deepest)
        val inner = zip("c.tcx" to ride(3), "deeper.zip" to deeper)
        val export = zip("a.fit" to ride(1), "b.fit.gz" to gzip(ride(2)), "notes.txt" to "hi".toByteArray(), "inner.zip" to inner)
        val source = FakeFileSource(mapOf("content://x/export.zip" to export, "content://x/loose.FIT" to ride(6)))

        val scan = ExportReader(source).scan(listOf(PickedFile("content://x/export.zip", "export.zip"), PickedFile("content://x/loose.FIT", "loose.FIT")))

        assertEquals(
            listOf("export.zip/a.fit", "export.zip/b.fit.gz", "export.zip/inner.zip/c.tcx", "export.zip/inner.zip/deeper.zip/d.fit", "loose.FIT"),
            scan.entries.map { it.path },
        )
        // notes.txt, and the archive 4 deep
        assertEquals(2, scan.ignored)
        assertEquals(ride(2).size.toLong(), scan.entries[1].rawBytes)
        assertTrue(scan.rejected.isEmpty())
    }

    // AC1: over 50 MB uncompressed is too large; a broken nested archive rejects only itself
    @Test
    fun tooLargeFilesAndBrokenArchivesAreRejected() = runTest {
        val huge = ByteArray((ExportReader.MAX_FIT_BYTES + 1).toInt())
        val broken = zip("x.fit" to ride(1)).copyOf(30)
        val export = zip("huge.fit" to huge, "broken.zip" to broken, "ok.fit" to ride(2))

        val scan = ExportReader(FakeFileSource(mapOf("content://x/e.zip" to export))).scan(listOf(PickedFile("content://x/e.zip", "e.zip")))

        assertEquals(listOf("e.zip/ok.fit"), scan.entries.map { it.path })
        assertEquals(ImportItemStatus.TOO_LARGE, scan.rejected.single { it.fileName == "e.zip/huge.fit" }.status)
        assertEquals(ImportItemStatus.INVALID, scan.rejected.single { it.fileName == "e.zip/broken.zip" }.status)
    }

    // AC1: a chosen file that cannot be read stops everything
    @Test
    fun anUnreadableChosenFileIsTold() = runTest {
        val truncated = zip("a.fit" to ride(1), "b.fit" to ride(2)).copyOf(40)
        val error = runCatching {
            ExportReader(FakeFileSource(mapOf("content://x/t.zip" to truncated))).scan(listOf(PickedFile("content://x/t.zip", "t.zip")))
        }.exceptionOrNull()

        assertEquals("t.zip", (error as UnreadableFileException).fileName)
    }

    // AC2: the web's limits
    @Test
    fun batchesStayWithinTheLimits() {
        val small = (1..250).map { RideEntry("r$it.fit", rawBytes = 100_000, packedBytes = 25_000) }
        assertEquals(listOf(100, 100, 50), divideIntoBatches(small).map { it.size })

        val packed = (1..5).map { RideEntry("p$it.fit", rawBytes = 1, packedBytes = 15L * 1024 * 1024) }
        assertEquals(listOf(2, 2, 1), divideIntoBatches(packed).map { it.size })

        val raw = (1..5).map { RideEntry("w$it.fit", rawBytes = 90L * 1024 * 1024, packedBytes = 1) }
        assertEquals(listOf(2, 2, 1), divideIntoBatches(raw).map { it.size })
    }

    // AC5: an imported ride stays imported when a batch is sent again
    @Test
    fun anImportedRideStaysImported() {
        val outcomes = LinkedHashMap<String, app.climbra.android.data.api.ImportItem>()
        outcomes.mergeOutcomes(listOf(rejectedItem("a.fit", ImportItemStatus.IMPORTED, ""), rejectedItem("b.fit", ImportItemStatus.FAILED, "")))
        outcomes.mergeOutcomes(listOf(rejectedItem("a.fit", ImportItemStatus.DUPLICATE, ""), rejectedItem("b.fit", ImportItemStatus.IMPORTED, "")))

        assertEquals(listOf(ImportItemStatus.IMPORTED, ImportItemStatus.IMPORTED), outcomes.values.map { it.status })
    }

    // AC4: how long a busy server is waited for
    @Test
    fun aBusyServerIsWaitedFor() {
        assertEquals(60_000L, ImportSession.busyMillis(ApiError.Http(503, "IMPORT_QUEUE_FULL", retryAfterSeconds = 60)))
        assertEquals(ImportSession.BUSY_DELAY_MILLIS, ImportSession.busyMillis(ApiError.Http(503, "FILE_PROCESSING_BUSY")))
        assertNull(ImportSession.busyMillis(ApiError.Http(503, null)))
        assertNull(ImportSession.busyMillis(ApiError.Http(400, "IMPORT_QUEUE_FULL")))
    }
}

/** Spec 014 AC3 - AC8: a large import in batches, through the app's [RideImport]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportSessionTest {

    private val backend = TestBackend()
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val changes = DataChanges()
    private val session: SessionRepository = backend.session(scope)
    private val slept = mutableListOf<Long>()
    private val cache = createTempDirectory("import-test").toFile()

    /** 150 small rides in one archive the system says is 60 MB: two batches of 100 and 50. */
    private val export = zip(*(1..150).map { "ride-%03d.fit".format(it) to ride(it) }.toTypedArray())
    private val files = FakeFileSource(mapOf("content://x/export.zip" to export), sizes = mapOf("content://x/export.zip" to 60L * 1024 * 1024))
    private val rideImport = backend.rideImport(session, scope, files, changes, pollMillis = 1, cacheDir = cache, sleep = { slept += it })
    private val chosen = listOf(PickedFile("content://x/export.zip", "export.zip"))

    @After
    fun tearDown() {
        scope.cancel()
        backend.close()
        cache.deleteRecursively()
    }

    private val startRoute = "POST /api/riders/5/imports"

    private fun job(id: Int, status: String, imported: Int = 0, items: String = "[]", message: String? = null) = json(
        """{"id":$id,"riderId":5,"kind":"UPLOAD","status":"$status","processedFiles":$imported,"selectedRides":$imported,
           "imported":$imported,"message":${message?.let { "\"$it\"" } ?: "null"},"firstRideDate":"2026-0$id-01",
           "lastRideDate":"2026-0$id-28","items":$items}""",
        202,
    )

    private fun items(range: IntRange, status: String = "IMPORTED") =
        range.joinToString(",", "[", "]") { """{"fileName":"export.zip/ride-%03d.fit","status":"$status"}""".format(it) }

    private suspend fun ended() = rideImport.job.first { it?.running == false }!!

    /** The batch ZIPs as they reached the server. */
    private fun sentBatches(): List<List<String>> = backend.requests(startRoute).map { request ->
        val body = request.body!!.toByteArray()
        val start = body.indexOfSequence(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        names(body.copyOfRange(start, body.size))
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int =
        (0..size - sequence.size).first { i -> sequence.indices.all { this[i + it] == sequence[it] } }

    // AC3, AC5, AC8: batch after batch, each followed; the outcomes of all merged
    @Test
    fun aLargeSelectionGoesInBatches() = runTest(dispatcher) {
        backend.on(startRoute, job(1, "QUEUED"), job(2, "QUEUED"))
        backend.on("GET /api/imports/1", job(1, "RUNNING"), job(1, "COMPLETED", imported = 100, items = items(1..100)))
        backend.replace("GET /api/imports/2", job(2, "COMPLETED", imported = 49, items = items(101..149).dropLast(1) + """,{"fileName":"export.zip/ride-150.fit","status":"NOT_CYCLING"}]"""))
        val version = changes.version.value

        assertEquals(StartResult.Started, rideImport.start(5, chosen, "Europe/Warsaw"))
        val session = rideImport.session.value!!
        val job = ended()

        assertEquals(listOf(100, 50), sentBatches().map { it.size })
        assertEquals("export.zip/ride-001.fit", sentBatches()[0].first())
        assertEquals("export.zip/ride-150.fit", sentBatches()[1].last())
        assertEquals("Europe/Warsaw", backend.requests(startRoute).first().url.queryParameter("timeZone"))
        assertEquals(ImportJobStatus.COMPLETED, job.status)
        assertEquals(149, job.imported)
        assertEquals(1, job.skipped)
        assertEquals("2026-01-01", job.firstRideDate)
        assertEquals("2026-02-28", job.lastRideDate)
        assertEquals(SessionPhase.DONE, session.progress.value.phase)
        assertEquals(2, session.progress.value.batches)
        assertEquals(0, session.progress.value.notSent)
        // Data changed after each batch that imported rides
        assertEquals(version + 2, changes.version.value)
        // Nothing left in the cache
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    // AC8: up to 50 MB of known size is still one job of spec 009
    @Test
    fun aSmallSelectionIsStillOneJob() = runTest(dispatcher) {
        val small = FakeFileSource(mapOf("content://x/a.fit" to ride(1)))
        val other = backend.rideImport(session, scope, small, changes, pollMillis = 1, cacheDir = cache, sleep = { slept += it })
        backend.on(startRoute, job(1, "COMPLETED", imported = 1))

        other.start(5, listOf(PickedFile("content://x/a.fit", "a.fit")), "UTC")

        assertNull(other.session.value)
        assertTrue(backend.requests(startRoute).single().body!!.utf8().contains("""filename="a.fit""""))
    }

    // AC4: a busy server is waited for as it asks; a refusal pauses, Resume sends the batch again
    @Test
    fun aBusyServerIsWaitedForAndARefusalPauses() = runTest(dispatcher) {
        val busy = MockResponse.Builder().code(503).headers(headersOf("Content-Type", "application/problem+json", "Retry-After", "7"))
            .body("""{"status":503,"code":"IMPORT_QUEUE_FULL"}""").build()
        backend.on(startRoute, busy, problem(400, null), job(1, "COMPLETED", imported = 100, items = items(1..100)), job(2, "COMPLETED", imported = 50, items = items(101..150)))

        rideImport.start(5, chosen, "UTC")
        val session = rideImport.session.value!!
        val paused = session.progress.first { it.phase == SessionPhase.PAUSED }

        assertTrue(7_000L in slept)
        assertEquals(UiText(R.string.error_request_failed, listOf(400)), paused.pausedBy)
        rideImport.decide(Decision.RESUME)
        assertEquals(150, ended().imported)
        assertEquals(4, backend.requests(startRoute).size)
    }

    // AC4, AC5: a job that failed is sent once more, then the import pauses; Finish ends with the files not sent
    @Test
    fun aFailedBatchPausesAndFinishEnds() = runTest(dispatcher) {
        backend.on(startRoute, job(1, "FAILED", message = "Storage down"), job(1, "FAILED", message = "Storage down"))

        rideImport.start(5, chosen, "UTC")
        val session = rideImport.session.value!!
        val paused = session.progress.first { it.phase == SessionPhase.PAUSED }
        assertEquals(UiText(R.string.import_batch_failed, listOf("Storage down")), paused.pausedBy)

        rideImport.decide(Decision.FINISH)
        val job = ended()
        assertEquals(0, job.imported)
        assertEquals(150, session.progress.value.notSent)
        assertEquals(2, backend.requests(startRoute).size)
    }

    // AC6: Stop ends after the current batch
    @Test
    fun stopEndsAfterTheCurrentBatch() = runTest(dispatcher) {
        backend.on(startRoute, job(1, "QUEUED"))
        backend.on("GET /api/imports/1", job(1, "RUNNING"), job(1, "RUNNING"), job(1, "COMPLETED", imported = 100, items = items(1..100)))

        rideImport.start(5, chosen, "UTC")
        val session = rideImport.session.value!!
        rideImport.stop()
        assertTrue(session.progress.value.stopping)
        val job = ended()

        assertEquals(1, backend.requests(startRoute).size)
        assertEquals(100, job.imported)
        assertEquals(50, session.progress.value.notSent)
    }

    // AC1: nothing to send, or an unreadable file, starts nothing
    @Test
    fun unusableFilesStartNothing() = runTest(dispatcher) {
        val none = FakeFileSource(mapOf("content://x/e.zip" to zip("notes.txt" to ride(1))), sizes = mapOf("content://x/e.zip" to 60L * 1024 * 1024))
        val other = backend.rideImport(session, scope, none, changes, cacheDir = cache)
        assertEquals(StartResult.NoRideFiles, other.start(5, listOf(PickedFile("content://x/e.zip", "e.zip")), "UTC"))
        assertNull(other.job.value)

        val truncated = FakeFileSource(mapOf("content://x/t.zip" to zip("a.fit" to ride(1), "b.fit" to ride(2)).copyOf(40)), sizes = mapOf("content://x/t.zip" to 60L * 1024 * 1024))
        val third = backend.rideImport(session, scope, truncated, changes, cacheDir = cache)
        assertEquals(StartResult.Unreadable("t.zip"), third.start(5, listOf(PickedFile("content://x/t.zip", "t.zip")), "UTC"))
        assertEquals(0, backend.requests(startRoute).size)
    }

    // AC6: logout cancels; nothing more is sent
    @Test
    fun logoutCancelsTheImport() = runTest(dispatcher) {
        backend.on("POST /api/auth/login", json(account(), 200, SESSION_COOKIE))
        backend.on("POST /api/auth/logout", noContent())
        session.login("anna@example.com", "correct-horse-1")
        backend.on(startRoute, job(1, "QUEUED"))
        backend.on("GET /api/imports/1", job(1, "RUNNING"))
        rideImport.start(5, chosen, "UTC")

        // The first batch reaches the server on OkHttp's threads
        val end = System.currentTimeMillis() + 5_000
        while (backend.requests(startRoute).isEmpty() && System.currentTimeMillis() < end) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thread.sleep(20) }

        session.logout()

        rideImport.job.first { it == null }
        assertNull(rideImport.session.value)
        assertEquals(1, backend.requests(startRoute).size)
    }
}
