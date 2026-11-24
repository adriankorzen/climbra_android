package app.climbra.android.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 027: the widget's background refreshes while a widget is placed. */
@RunWith(AndroidJUnit4::class)
class TodayWidgetScheduleTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager
    private val warsaw = ZoneId.of("Europe/Warsaw")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun schedule(now: String = "2026-11-24T21:30:00Z") =
        TodayWidgetSchedule(workManager, Clock.fixed(Instant.parse(now), warsaw), zone = { warsaw })

    private fun states(name: String) = workManager.getWorkInfosForUniqueWork(name).get().map { it.state }

    @Test
    fun `the first widget enqueues the hourly and the midnight refresh, the last one cancels them`() {
        val schedule = schedule()

        schedule.start()

        assertEquals(listOf(WorkInfo.State.ENQUEUED), states(TodayWidgetSchedule.HOURLY))
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states(TodayWidgetSchedule.MIDNIGHT))
        val hourly = workManager.getWorkInfosForUniqueWork(TodayWidgetSchedule.HOURLY).get().single()
        assertEquals(Duration.ofHours(1).toMillis(), hourly.periodicityInfo?.repeatIntervalMillis)
        assertTrue(hourly.constraints.requiredNetworkType == androidx.work.NetworkType.CONNECTED)

        schedule.stop()

        assertEquals(listOf(WorkInfo.State.CANCELLED), states(TodayWidgetSchedule.HOURLY))
        assertEquals(listOf(WorkInfo.State.CANCELLED), states(TodayWidgetSchedule.MIDNIGHT))
    }

    @Test
    fun `a second widget keeps the hourly refresh, the next midnight replaces the earlier one`() {
        val schedule = schedule()
        schedule.start()
        val hourly = workManager.getWorkInfosForUniqueWork(TodayWidgetSchedule.HOURLY).get().single().id

        schedule.start()
        schedule.scheduleMidnight()

        assertEquals(hourly, workManager.getWorkInfosForUniqueWork(TodayWidgetSchedule.HOURLY).get().single().id)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(TodayWidgetSchedule.MIDNIGHT).get().count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `the midnight refresh runs shortly after the next local midnight`() {
        // 22:30 in Warsaw: 1 h 30 min to midnight, and 2 minutes after it
        assertEquals(Duration.ofMinutes(92), schedule("2026-11-24T21:30:00Z").untilMidnight())
        // 00:01 in Warsaw: the next day's midnight
        assertEquals(Duration.ofHours(24).plusMinutes(1), schedule("2026-11-24T23:01:00Z").untilMidnight())
    }
}
