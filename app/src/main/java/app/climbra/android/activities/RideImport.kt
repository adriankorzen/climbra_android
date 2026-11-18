package app.climbra.android.activities

import app.climbra.android.R
import app.climbra.android.coach.PickedFile
import app.climbra.android.coach.RideFileSource
import app.climbra.android.coach.part
import app.climbra.android.data.api.ImportApi
import app.climbra.android.data.api.ImportJob
import app.climbra.android.data.api.ImportJobStatus
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.ui.common.UiText
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
    private val pollMillis: Long = POLL_MILLIS,
) {
    private val mutableJob = MutableStateFlow<ImportJob?>(null)

    /** The running or last finished job; `null` before one and after [clear]. */
    val job: StateFlow<ImportJob?> = mutableJob.asStateFlow()

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

    /** Sends [chosen] as one job of [riderId] and follows it; a failure leaves nothing started. */
    suspend fun start(riderId: Long, chosen: List<PickedFile>, timeZone: String): ApiResult<ImportJob> {
        val parts = chosen.map { files.part("files", it) }
        val result = apiCall(Network.json) { api.startImport(riderId, parts, timeZone) }
        if (result is ApiResult.Success) follow(result.value)
        return result
    }

    /** Forgets a finished job, e.g. to import more. */
    fun clear() {
        if (mutableJob.value?.running != true) mutableJob.value = null
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

    private fun ended(job: ImportJob) {
        if (job.imported > 0) changes.changed()
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
