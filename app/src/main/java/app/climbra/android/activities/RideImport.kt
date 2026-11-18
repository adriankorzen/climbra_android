package app.climbra.android.activities

import app.climbra.android.R
import app.climbra.android.coach.PickedFile
import app.climbra.android.coach.RideFileSource
import app.climbra.android.coach.part
import app.climbra.android.data.api.ImportApi
import app.climbra.android.data.api.ImportJob
import app.climbra.android.data.api.ImportJobStatus
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.ui.common.UiText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The file names the backend reads as rides (`RideFile.isSupported`), case ignored (spec 009). */
fun isImportFile(name: String): Boolean = name.lowercase().let { lower -> IMPORT_EXTENSIONS.any { lower.endsWith(it) } }

private val IMPORT_EXTENSIONS = listOf(".fit", ".tcx", ".gz", ".zip")

/** A large import started: the app keeps it going in a foreground service (spec 014). */
fun interface ForegroundImports {
    fun started()
}

/** All files of one import together (the backend's `upload.max-request-size`). */
const val MAX_IMPORT_BYTES = 50L * 1024 * 1024

/**
 * The import of several rides (spec 009): one job at a time for the whole app, followed every [pollMillis] in the app
 * scope until it ends, so it goes on when the import screen is left. When it ends while the screen is not [shown], a
 * notice goes to the shell's snackbar; logout forgets it.
 */
class RideImport(
    private val api: ImportApi,
    private val files: RideFileSource,
    private val changes: DataChanges,
    session: SessionRepository,
    private val scope: CoroutineScope,
    /** Where a large import packs its batch (spec 014). */
    private val cacheDir: File,
    /** Keeps a large import going while the app is not shown (spec 014). */
    private val foreground: ForegroundImports = ForegroundImports {},
    private val pollMillis: Long = POLL_MILLIS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutableJob = MutableStateFlow<ImportJob?>(null)

    /** The running or last finished job; `null` before one and after [clear]. */
    val job: StateFlow<ImportJob?> = mutableJob.asStateFlow()

    private val mutableSession = MutableStateFlow<ImportSession?>(null)

    /** The large import (spec 014), running or done; `null` for an import of up to 50 MB. */
    val session: StateFlow<ImportSession?> = mutableSession.asStateFlow()

    private val channel = Channel<UiText>(Channel.BUFFERED)

    /** The end of a job while the import screen is not shown. */
    val notices: Flow<UiText> = channel.receiveAsFlow()

    /** The import screen is shown: it tells the result itself. */
    @Volatile
    var shown: Boolean = false

    private var polling: Job? = null

    init {
        scope.launch {
            session.state.collect { if (it is SessionState.LoggedOut) forget() }
        }
    }

    /**
     * Imports [chosen] for [riderId]: up to 50 MB of known size as one job (spec 009), more in batches (spec 014). A
     * failure leaves nothing started.
     */
    suspend fun start(riderId: Long, chosen: List<PickedFile>, timeZone: String): StartResult {
        if (polling?.isActive == true) return StartResult.Failed(ApiError.Http(409, "IMPORT_RUNNING"))
        val sizes = chosen.map { files.size(it.uri) }
        if (sizes.all { it != null } && sizes.sumOf { it!! } <= MAX_IMPORT_BYTES) return startJob(riderId, chosen, timeZone)
        return startSession(riderId, chosen, timeZone)
    }

    private suspend fun startJob(riderId: Long, chosen: List<PickedFile>, timeZone: String): StartResult {
        val parts = chosen.map { files.part("files", it) }
        return when (val result = apiCall(Network.json) { api.startImport(riderId, parts, timeZone) }) {
            is ApiResult.Success -> {
                mutableSession.value = null
                follow(result.value)
                StartResult.Started
            }
            is ApiResult.Failure -> StartResult.Failed(result.error)
        }
    }

    private suspend fun startSession(riderId: Long, chosen: List<PickedFile>, timeZone: String): StartResult {
        val run = ImportSession(
            api, files, changes, cacheDir, riderId, chosen, timeZone,
            publish = { mutableJob.value = it }, pollMillis = pollMillis, sleep = sleep,
        )
        mutableSession.value = run
        foreground.started()
        val refused = try {
            run.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            StartResult.Unreadable(chosen.firstOrNull()?.name.orEmpty())
        }
        if (refused != null) {
            mutableSession.value = null
            mutableJob.value = null
            return refused
        }
        polling = scope.launch {
            val job = run.send()
            ended(job, changed = false)
        }
        return StartResult.Started
    }

    /** A large import sends no batch after the current one. */
    fun stop() = session.value?.stop()

    /** A paused large import sends the batch again ([Decision.RESUME]) or ends ([Decision.FINISH]). */
    fun decide(decision: Decision) = session.value?.decide(decision)

    /** Forgets a finished job, e.g. to import more. */
    fun clear() {
        if (mutableJob.value?.running != true) {
            mutableJob.value = null
            mutableSession.value = null
        }
    }

    private fun follow(started: ImportJob) {
        mutableJob.value = started
        polling?.cancel()
        polling = scope.launch {
            var current = started
            while (current.running) {
                delay(pollMillis)
                // A failed poll is asked again: the server may be restarting
                val polled = apiCall(Network.json) { api.importJob(current.id) }
                if (polled is ApiResult.Success) {
                    current = polled.value
                    mutableJob.value = current
                }
            }
            ended(current)
        }
    }

    /** [changed]: the batches of a large import marked the data changed already. */
    private fun ended(job: ImportJob, changed: Boolean = true) {
        if (changed && job.imported > 0) changes.changed()
        if (!shown) {
            channel.trySend(
                if (job.status == ImportJobStatus.FAILED) {
                    UiText(R.string.import_failed, listOf(job.message.orEmpty()))
                } else {
                    UiText(R.plurals.import_done, listOf(job.imported), quantity = job.imported)
                },
            )
        }
    }

    private fun forget() {
        polling?.cancel()
        polling = null
        mutableJob.value = null
        mutableSession.value = null
        cacheDir.listFiles { file -> file.name.startsWith("rides-") }?.forEach { it.delete() }
    }

    /** Waits until the followed job ended (for tests). */
    internal suspend fun join() {
        polling?.join()
    }

    companion object {
        const val POLL_MILLIS = 2_000L
    }
}

/**
 * Ride files shared into the app (spec 009), held until the import screen takes them; also while the rider logs in.
 * Their URIs can be read as long as the app's process lives.
 */
class SharedRideFiles {
    private val mutablePending = MutableStateFlow<List<PickedFile>?>(null)

    /** Files waiting for the import screen. */
    val pending: StateFlow<List<PickedFile>?> = mutablePending.asStateFlow()

    private val channel = Channel<UiText>(Channel.BUFFERED)

    /** "Climbra imports FIT, TCX, GZ and ZIP files" when nothing shared was a ride file. */
    val notices: Flow<UiText> = channel.receiveAsFlow()

    private val openings = Channel<Unit>(Channel.CONFLATED)

    /** The import's notification was tapped (spec 014): the import screen opens. */
    val opens: Flow<Unit> = openings.receiveAsFlow()

    fun openScreen() {
        openings.trySend(Unit)
    }

    /** Files another app shared; files that are not rides are kept for the screen to name, unless none is a ride. */
    fun offer(shared: List<PickedFile>) {
        if (shared.isEmpty()) return
        if (shared.none { isImportFile(it.name) }) {
            channel.trySend(UiText(R.string.import_share_unsupported))
        } else {
            mutablePending.value = shared
        }
    }

    /** The pending files, once. */
    fun take(): List<PickedFile>? {
        val files = mutablePending.value
        mutablePending.value = null
        return files
    }
}
