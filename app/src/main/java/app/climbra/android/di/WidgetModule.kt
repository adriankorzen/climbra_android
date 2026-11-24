package app.climbra.android.di

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.WorkManager
import app.climbra.android.app.AppForeground
import app.climbra.android.data.api.CalendarApi
import app.climbra.android.data.api.RiderApi
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import app.climbra.android.widget.TodayRepository
import app.climbra.android.widget.TodayWidget
import app.climbra.android.widget.TodayWidgetSchedule
import app.climbra.android.widget.TodayWidgetUpdates
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/** The home screen widget (spec 027). */
@Module
@InstallIn(SingletonComponent::class)
object WidgetModule {

    @Provides
    @Singleton
    fun todayRepository(riders: RiderApi, calendar: CalendarApi, session: SessionRepository): TodayRepository =
        TodayRepository(riders, calendar, session = { session.state.value })

    @Provides
    @Singleton
    fun todayWidgetUpdates(
        @ApplicationContext context: Context,
        session: SessionRepository,
        foreground: AppForeground,
        changes: DataChanges,
        scope: CoroutineScope,
    ): TodayWidgetUpdates = TodayWidgetUpdates(
        session = session.state,
        foreground = foreground.visible,
        ridesChanged = changes.version,
        scope = scope,
        placed = { GlanceAppWidgetManager(context).getGlanceIds(TodayWidget::class.java).isNotEmpty() },
        update = { TodayWidget().updateAll(context) },
    )

    @Provides
    @Singleton
    fun todayWidgetSchedule(@ApplicationContext context: Context): TodayWidgetSchedule =
        TodayWidgetSchedule(WorkManager.getInstance(context))
}
