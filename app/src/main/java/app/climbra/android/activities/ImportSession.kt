package app.climbra.android.activities

import app.climbra.android.R
import app.climbra.android.coach.PickedFile
import app.climbra.android.coach.RideFileSource
import app.climbra.android.data.api.ImportApi
import app.climbra.android.data.api.ImportItem
import app.climbra.android.data.api.ImportItemStatus
import app.climbra.android.data.api.ImportJob
import app.climbra.android.data.api.ImportJobStatus
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.DataChanges
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

enum class SessionPhase { READING, SENDING, WAITING, PAUSED, DONE }

/** The import of a large selection in batches (spec 014), as far as the screen and the notification show it. */
data class SessionProgress(
    val phase: SessionPhase = SessionPhase.READING,
    /** Ride files found so far while reading. */
    val found: Int = 0,
    /** The batch being sent, from 1, of [batches]. */
    val batch: Int = 0,
    val batches: Int = 0,
    /** The import ends after the current batch. */
    val stopping: Boolean = false,
    /** Why the import is paused. */
    val pausedBy: UiText? = null,
    /** Ride files of a finished import that were not sent. */
    val notSent: Int = 0,
)

/** How a large import starts: it runs, or the chosen files could not be used. */
sealed interface StartResult {
    data object Started : StartResult

    data class Failed(val error: ApiError) : StartResult

    data class Unreadable(val fileName: String) : StartResult

    data object NoRideFiles : StartResult
}

/** What the rider decides on a paused import. */
enum class Decision { RESUME, FINISH }

/** The walk ends: the rider stopped or finished the import. */
private class EndWalk : RuntimeException()

/**
 * One large import (spec 014, the web's `import-session`): the chosen files are read twice, a first time to find the
 * ride files and divide them into batches, a second time to pack each batch as a ZIP in [cacheDir] and send it as an
 * ordinary import job, the next when the job of the previous one ended. Waits for a free slot when the server is busy,
 * retries network errors, and pauses on what retries did not fix; the outcomes of all batches are merged into one job,
 * published through [publish].
 */
class ImportSession(
    private val api: ImportApi,
    source: RideFileSource,
    private val changes: DataChanges,
    private val cacheDir: File,
    private val riderId: Long,
    private val files: List<PickedFile>,
    private val timeZone: String,
    private val publish: (ImportJob) -> Unit,
    private val pollMillis: Long,
    private val sleep: suspend (Long) -> Unit,
) {
    private val reader = ExportReader(source)

    private val mutableProgress = MutableStateFlow(SessionProgress())
    val progress: StateFlow<SessionProgress> = mutableProgress.asStateFlow()

    private val outcomes = LinkedHashMap<String, ImportItem>()
    private var batchOf: Map<String, Int> = emptyMap()
    private var packedOf: Map<String, Long> = emptyMap()
    private var entries: List<RideEntry> = emptyList()
    private var sentFiles = 0
    private var firstRideDate: String? = null
    private var lastRideDate: String? = null
    private var current: ImportJob? = null

    @Volatile
    private var stopRequested = false
    private var decision: CompletableDeferred<Decision>? = null

    /** The first walk: finds the ride files and divides them; `null` when the import may run. */
    suspend fun read(): StartResult? {
        publish(job(ImportJobStatus.RUNNING, selected = null))
        val scan = try {
            reader.scan(files) { found -> mutableProgress.update { it.copy(found = found) } }
        } catch (e: UnreadableFileException) {
            return StartResult.Unreadable(e.fileName)
        }
        if (scan.entries.isEmpty() && scan.rejected.isEmpty()) return StartResult.NoRideFiles
        outcomes.mergeOutcomes(scan.rejected)
        entries = scan.entries
        packedOf = scan.entries.associate { it.path to it.packedBytes }
        val batches = divideIntoBatches(scan.entries)
        batchOf = batches.flatMapIndexed { index, batch -> batch.map { it.path to index } }.toMap()
        mutableProgress.update { it.copy(phase = SessionPhase.SENDING, batches = batches.size) }
        publish(job(ImportJobStatus.RUNNING))
        return null
    }

    /** The second walk: batch after batch until all are sent, the rider stops, or finishes a paused import. */
    suspend fun send(): ImportJob {
        var writer: BatchWriter? = null
        var batchIndex = -1
        var number = 0
        try {
            reader.walk(files, onRide = { path, stream ->
                val planned = batchOf[path] ?: return@walk
                val size = packedOf.getValue(path)
                val open = writer
                if (open != null && (planned != batchIndex || open.bytes + size > MAX_BATCH_REQUEST_BYTES)) {
                    if (planned == batchIndex) {
                        // The files packed worse than expected: the rest of the batch goes as one more
                        mutableProgress.update { it.copy(batches = it.batches + 1) }
                    }
                    writer = null
                    flush(open, number)
                }
                if (writer == null) {
                    if (stopRequested) throw EndWalk()
                    number++
                    batchIndex = planned
                    mutableProgress.update { it.copy(batch = number) }
                    writer = BatchWriter(File(cacheDir, "rides-$number.zip"))
                }
                writer!!.add(path, stream)
            })
            writer?.let {
                writer = null
                flush(it, number)
            }
        } catch (e: EndWalk) {
            writer?.close()
        } catch (e: UnreadableFileException) {
            // The files changed since the first walk: what was sent stays
            writer?.close()
        } finally {
            cacheDir.listFiles { file -> file.name.startsWith("rides-") }?.forEach { it.delete() }
        }
        mutableProgress.update { it.copy(phase = SessionPhase.DONE, notSent = entries.size - sentFiles, stopping = false, pausedBy = null) }
        return job(ImportJobStatus.COMPLETED).also(publish)
    }

    /** No batch after the current one. */
    fun stop() {
        stopRequested = true
        mutableProgress.update { it.copy(stopping = true) }
    }

    /** A paused import sends the batch again, or ends with what was imported. */
    fun decide(choice: Decision) {
        decision?.complete(choice)
    }

    private suspend fun flush(writer: BatchWriter, number: Int) {
        val file = writer.finish()
        val names = writer.entries
        try {
            if (file.length() > MAX_REQUEST_BYTES) {
                outcomes.mergeOutcomes(names.map { rejectedItem(it, ImportItemStatus.TOO_LARGE, "The file does not fit in one upload") })
            } else if (sendBatch(file, number) == Decision.FINISH) {
                throw EndWalk()
            }
            sentFiles += names.size
            publish(job(ImportJobStatus.RUNNING))
        } finally {
            file.delete()
        }
    }

    /** Sends a batch and follows its job; a job that failed is sent once more before the import pauses. */
    private suspend fun sendBatch(file: File, number: Int): Decision {
        var sentAgain = false
        while (true) {
            val started = upload(file, number) ?: return Decision.FINISH
            val finished = follow(started)
            if (finished.imported > 0) changes.changed()
            if (finished.status == ImportJobStatus.COMPLETED) return Decision.RESUME
            if (sentAgain) {
                if (pause(UiText(R.string.import_batch_failed, listOf(finished.message.orEmpty()))) == Decision.FINISH) return Decision.FINISH
                sentAgain = false
            } else {
                // E.g. a restart of the server: the rides stored so far come back as duplicates
                sentAgain = true
            }
        }
    }

    /** Uploads a batch, waiting while the server cannot take it; `null` when the import ends instead. */
    private suspend fun upload(file: File, number: Int): ImportJob? {
        var failures = 0
        var waited = 0L
        while (true) {
            mutableProgress.update { it.copy(phase = SessionPhase.SENDING) }
            val part = MultipartBody.Part.createFormData("files", "rides-$number.zip", file.asRequestBody(ZIP))
            when (val result = apiCall(Network.json) { api.startImport(riderId, listOf(part), timeZone) }) {
                is ApiResult.Success -> return result.value
                is ApiResult.Failure -> {
                    val error = result.error
                    val busy = busyMillis(error)
                    when {
                        busy != null && waited + busy <= MAX_BUSY_WAIT_MILLIS -> {
                            waited += busy
                            mutableProgress.update { it.copy(phase = SessionPhase.WAITING) }
                            sleep(busy)
                        }
                        busy == null && error == ApiError.Network && failures < RETRY_DELAYS_MILLIS.size ->
                            sleep(RETRY_DELAYS_MILLIS[failures++])
                        else -> {
                            if (pause(error.toUiText()) == Decision.FINISH) return null
                            failures = 0
                            waited = 0
                            continue
                        }
                    }
                    if (stopRequested) return null
                }
            }
        }
    }

    /** Polls the job of a batch until it ends; a failed poll is asked again (the server may be restarting). */
    private suspend fun follow(started: ImportJob): ImportJob {
        var job = started
        current = job
        while (job.running) {
            sleep(pollMillis)
            val polled = apiCall(Network.json) { api.importJob(job.id) }
            if (polled is ApiResult.Success) {
                job = polled.value
                current = job
                outcomes.mergeOutcomes(job.items)
                publish(job(ImportJobStatus.RUNNING))
            }
        }
        outcomes.mergeOutcomes(job.items)
        firstRideDate = listOfNotNull(firstRideDate, job.firstRideDate).minOrNull()
        lastRideDate = listOfNotNull(lastRideDate, job.lastRideDate).maxOrNull()
        current = null
        return job
    }

    private suspend fun pause(reason: UiText): Decision {
        val wait = CompletableDeferred<Decision>()
        decision = wait
        mutableProgress.update { it.copy(phase = SessionPhase.PAUSED, pausedBy = reason) }
        publish(job(ImportJobStatus.RUNNING))
        val choice = wait.await()
        decision = null
        mutableProgress.update { it.copy(phase = SessionPhase.SENDING, pausedBy = null) }
        return choice
    }

    /** The import as one job: the counters of all files' outcomes, the progress over all batches. */
    private fun job(status: ImportJobStatus, selected: Int? = entries.size) = ImportJob(
        id = SESSION_JOB_ID,
        status = status,
        kind = "UPLOAD",
        processedFiles = sentFiles + (current?.processedFiles ?: 0),
        selectedRides = selected,
        firstRideDate = firstRideDate,
        lastRideDate = lastRideDate,
    ).countedFrom(outcomes.values)

    companion object {
        /** The job the screen shows for a large import: not one of the backend's. */
        const val SESSION_JOB_ID = -1L

        /** Below the request limit, with room for the ZIP's own bytes. */
        private const val MAX_BATCH_REQUEST_BYTES = MAX_REQUEST_BYTES - 1024 * 1024

        /** Waits before the attempts after a network error. */
        val RETRY_DELAYS_MILLIS = listOf(5_000L, 15_000L, 30_000L)

        /** Wait for a free slot when the answer does not say how long. */
        const val BUSY_DELAY_MILLIS = 30_000L

        /** Longest wait for a free slot before the import pauses. */
        const val MAX_BUSY_WAIT_MILLIS = 30 * 60_000L

        private val ZIP = "application/zip".toMediaType()

        /** How long to wait when the server is busy (`503` with its codes), `null` for other errors. */
        fun busyMillis(error: ApiError): Long? {
            val http = error as? ApiError.Http ?: return null
            if (http.status != 503 || (http.code != "IMPORT_QUEUE_FULL" && http.code != "FILE_PROCESSING_BUSY")) return null
            return http.retryAfterSeconds?.times(1000) ?: BUSY_DELAY_MILLIS
        }
    }
}
