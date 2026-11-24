package app.climbra.android.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The widget's background refreshes (spec 027): every hour with a network, and shortly after the next local midnight
 * for the new day's workout; only while a widget is placed.
 */
class TodayWidgetSchedule(
    private val workManager: WorkManager,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    /** The first widget was placed. */
    fun start() {
        workManager.enqueueUniquePeriodicWork(
            HOURLY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TodayRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
        scheduleMidnight()
    }

    /** The next refresh after midnight, replacing an earlier one. */
    fun scheduleMidnight() {
        workManager.enqueueUniqueWork(
            MIDNIGHT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TodayRefreshWorker>()
                .setInitialDelay(untilMidnight().toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MIDNIGHT to true))
                .build(),
        )
    }

    /** The last widget was removed. */
    fun stop() {
        workManager.cancelUniqueWork(HOURLY)
        workManager.cancelUniqueWork(MIDNIGHT)
    }

    internal fun untilMidnight(): Duration {
        val now = ZonedDateTime.now(clock.withZone(zone()))
        val next = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plus(AFTER_MIDNIGHT)
        return Duration.between(now, next)
    }

    companion object {
        const val HOURLY = "today-widget-hourly"
        const val MIDNIGHT = "today-widget-midnight"
        internal const val KEY_MIDNIGHT = "midnight"
        private val AFTER_MIDNIGHT: Duration = Duration.ofMinutes(2)
    }
}

/** Refreshes the widgets; the midnight run schedules the next one. */
class TodayRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, TodayWidgetEntryPoint::class.java)
        entry.todayWidgetUpdates().refresh()
        if (inputData.getBoolean(TodayWidgetSchedule.KEY_MIDNIGHT, false)) entry.todayWidgetSchedule().scheduleMidnight()
        return Result.success()
    }
}

/** The widget, its receiver and worker are not created by Hilt. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TodayWidgetEntryPoint {
    fun todayRepository(): TodayRepository

    fun todayWidgetUpdates(): TodayWidgetUpdates

    fun todayWidgetSchedule(): TodayWidgetSchedule
}
