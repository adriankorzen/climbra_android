package app.climbra.android.activities

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.app.Service
import android.os.IBinder
import app.climbra.android.MainActivity
import app.climbra.android.R
import app.climbra.android.data.api.ImportJob
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Starts [ImportService] for a large import (spec 014). */
class ServiceForegroundImports(private val context: Context) : ForegroundImports {
    override fun started() {
        ContextCompat.startForegroundService(context, Intent(context, ImportService::class.java))
    }
}

/** What the notification of a large import says (spec 014). */
data class ImportNotice(val title: String, val text: String, val progress: Int?, val max: Int?, val ongoing: Boolean)

/** The notification's texts from the import's state; `null` when nothing runs. */
fun importNotice(context: Context, job: ImportJob?, progress: SessionProgress?): ImportNotice? {
    progress ?: return null
    val title = context.getString(R.string.import_title)
    return when (progress.phase) {
        SessionPhase.READING -> ImportNotice(title, context.getString(R.string.import_scanning), null, null, true)
        SessionPhase.PAUSED -> ImportNotice(
            title,
            context.getString(R.string.import_paused, progress.pausedBy?.resolve(context.resources).orEmpty()),
            null, null, true,
        )
        SessionPhase.DONE -> ImportNotice(
            title,
            context.resources.getQuantityString(R.plurals.import_done, job?.imported ?: 0, job?.imported ?: 0),
            null, null, false,
        )
        SessionPhase.SENDING, SessionPhase.WAITING -> {
            val total = job?.selectedRides ?: 0
            val done = job?.processedFiles ?: 0
            val text = if (progress.phase == SessionPhase.WAITING) {
                context.getString(R.string.import_waiting)
            } else {
                context.getString(R.string.import_progress_batch, done, total, progress.batch, progress.batches)
            }
            ImportNotice(title, text, done, total, true)
        }
    }
}

/**
 * Keeps a large import going while the app is not shown (spec 014): a foreground service of type `dataSync` with a
 * progress notification, which ends itself with the import. The import itself runs in [RideImport].
 */
@AndroidEntryPoint
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Inject
    lateinit var rideImport: RideImport

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        channel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(ImportNotice(getString(R.string.import_title), getString(R.string.import_scanning), null, null, true)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        watching?.cancel()
        watching = scope.launch {
            rideImport.session
                .flatMapLatest { session -> session?.progress?.let { flow -> combine(rideImport.job, flow) { job, p -> job to p } } ?: flowOf(null to null) }
                .collectLatest { (job, progress) ->
                    val notice = importNotice(this@ImportService, job, progress)
                    val manager = getSystemService(NotificationManager::class.java)
                    if (notice == null || !notice.ongoing) {
                        ServiceCompat.stopForeground(this@ImportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        // The end stays as a notice the rider can tap
                        notice?.let { manager.notify(DONE_ID, notification(it)) }
                        stopSelf()
                    } else {
                        manager.notify(NOTIFICATION_ID, notification(notice))
                    }
                }
        }
        return START_NOT_STICKY
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.import_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(notice: ImportNotice): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_IMPORT).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload_file)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setContentIntent(open)
            .setOngoing(notice.ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!notice.ongoing)
            .apply {
                if (notice.ongoing) {
                    if (notice.max != null && notice.max > 0) setProgress(notice.max, notice.progress ?: 0, false)
                    else setProgress(0, 0, true)
                }
            }
            .build()
    }

    companion object {
        const val CHANNEL = "ride-imports"
        const val NOTIFICATION_ID = 9014
        const val DONE_ID = 9015

        /** The notification opens the import screen. */
        const val ACTION_OPEN_IMPORT = "app.climbra.android.OPEN_IMPORT"
    }
}
