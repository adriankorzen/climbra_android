package app.climbra.android.di

import android.content.Context
import app.climbra.android.AppConfig
import app.climbra.android.activities.RideImport
import app.climbra.android.activities.SessionPhase
import app.climbra.android.app.AppForeground
import app.climbra.android.coach.CoachActivity
import app.climbra.android.coach.CoachQuotaRepository
import app.climbra.android.data.net.Connectivity
import app.climbra.android.push.AndroidPushNotifier
import app.climbra.android.push.DataStorePushPermissionAsked
import app.climbra.android.push.FirebasePushTokens
import app.climbra.android.push.NoPushTokens
import app.climbra.android.push.PushApi
import app.climbra.android.push.PushMessages
import app.climbra.android.push.PushNotifier
import app.climbra.android.push.PushPermissionAsked
import app.climbra.android.push.PushTargets
import app.climbra.android.push.PushTokenSource
import app.climbra.android.push.PushTokens
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.SessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import retrofit2.Retrofit

/** Push notifications (spec 020). */
@Module
@InstallIn(SingletonComponent::class)
object PushModule {

    @Provides
    @Singleton
    fun pushApi(retrofit: Retrofit): PushApi = retrofit.create(PushApi::class.java)

    @Provides
    @Singleton
    fun pushTokenSource(config: AppConfig): PushTokenSource =
        if (config.pushEnabled) FirebasePushTokens() else NoPushTokens

    @Provides
    @Singleton
    fun pushTokens(
        api: PushApi,
        source: PushTokenSource,
        session: SessionRepository,
        scope: CoroutineScope,
        connectivity: Connectivity,
    ): PushTokens = PushTokens(api, source, session.state, scope, offline = { connectivity.isOffline })

    @Provides
    @Singleton
    fun pushNotifier(@ApplicationContext context: Context): PushNotifier = AndroidPushNotifier(context)

    @Provides
    @Singleton
    fun pushMessages(
        session: SessionRepository,
        foreground: AppForeground,
        rideImport: RideImport,
        notifier: PushNotifier,
        coachActivity: CoachActivity,
        quotas: CoachQuotaRepository,
        changes: DataChanges,
        scope: CoroutineScope,
    ): PushMessages = PushMessages(
        session = session.state,
        foreground = foreground.visible,
        largeImport = { rideImport.session.value?.progress?.value?.phase.let { it != null && it != SessionPhase.DONE } },
        notifier = notifier,
        coachChanged = {
            coachActivity.changed()
            scope.launch { quotas.reload() }
        },
        ridesChanged = changes::changed,
    )

    @Provides
    @Singleton
    fun pushTargets(): PushTargets = PushTargets()

    @Provides
    @Singleton
    fun pushPermissionAsked(@ApplicationContext context: Context): PushPermissionAsked = DataStorePushPermissionAsked(context)
}
