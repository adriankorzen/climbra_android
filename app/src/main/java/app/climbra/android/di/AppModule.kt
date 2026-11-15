package app.climbra.android.di

import android.content.Context
import app.climbra.android.AppConfig
import app.climbra.android.activities.FileSaver
import app.climbra.android.activities.MediaStoreFileSaver
import app.climbra.android.data.api.ActivityApi
import app.climbra.android.data.api.AuthApi
import app.climbra.android.data.api.CalendarApi
import app.climbra.android.data.api.CoachApi
import app.climbra.android.coach.CoachActivity
import app.climbra.android.coach.CoachQuotaRepository
import app.climbra.android.coach.ContentResolverFileSource
import app.climbra.android.coach.RideFileSource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import app.climbra.android.calendar.CalendarRequests
import app.climbra.android.data.api.ProfileApi
import app.climbra.android.data.api.RiderApi
import app.climbra.android.data.net.EncryptedFileCookieStore
import app.climbra.android.data.net.KeystoreCipher
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.PersistentCookieJar
import app.climbra.android.data.net.SessionEvents
import app.climbra.android.session.AppCompatLocales
import app.climbra.android.session.AppLocales
import app.climbra.android.session.SessionRepository
import app.climbra.android.rider.DataChanges
import app.climbra.android.intervals.IntervalsSync
import app.climbra.android.rider.RiderRepository
import app.climbra.android.ui.theme.DataStoreThemeSettings
import app.climbra.android.ui.theme.ThemeSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun appConfig(): AppConfig = AppConfig.fromBuildConfig()

    /** Lives as long as the process; for work that must not stop with a screen. */
    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun cookieJar(@ApplicationContext context: Context): PersistentCookieJar =
        PersistentCookieJar(EncryptedFileCookieStore(File(context.noBackupFilesDir, "cookies.bin"), KeystoreCipher(context)))

    @Provides
    @Singleton
    fun sessionEvents(): SessionEvents = SessionEvents()

    @Provides
    @Singleton
    fun okHttpClient(cookies: PersistentCookieJar, events: SessionEvents): OkHttpClient =
        Network.okHttpClient(cookies, events)

    @Provides
    @Singleton
    fun retrofit(config: AppConfig, client: OkHttpClient): Retrofit = Network.retrofit(config.apiBaseUrl, client)

    @Provides
    @Singleton
    fun authApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun appLocales(): AppLocales = AppCompatLocales()

    @Provides
    @Singleton
    fun sessionRepository(
        api: AuthApi,
        cookies: PersistentCookieJar,
        events: SessionEvents,
        locales: AppLocales,
        scope: CoroutineScope,
    ): SessionRepository = SessionRepository(api, cookies, events, locales, scope)

    @Provides
    @Singleton
    fun riderApi(retrofit: Retrofit): RiderApi = retrofit.create(RiderApi::class.java)

    @Provides
    @Singleton
    fun riderRepository(api: RiderApi): RiderRepository = RiderRepository(api)

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun activityApi(retrofit: Retrofit): ActivityApi = retrofit.create(ActivityApi::class.java)

    @Provides
    @Singleton
    fun dataChanges(): DataChanges = DataChanges()

    @Provides
    @Singleton
    fun fileSaver(@ApplicationContext context: Context): FileSaver = MediaStoreFileSaver(context)

    @Provides
    @Singleton
    fun profileApi(retrofit: Retrofit): ProfileApi = retrofit.create(ProfileApi::class.java)

    @Provides
    @Singleton
    fun themeSettings(@ApplicationContext context: Context): ThemeSettings = DataStoreThemeSettings(context)

    @Provides
    @Singleton
    fun calendarApi(retrofit: Retrofit): CalendarApi = retrofit.create(CalendarApi::class.java)

    @Provides
    @Singleton
    fun calendarRequests(): CalendarRequests = CalendarRequests()

    @Provides
    @Singleton
    fun coachApi(retrofit: Retrofit): CoachApi = retrofit.create(CoachApi::class.java)

    @Provides
    @Singleton
    fun coachQuotas(api: CoachApi, session: SessionRepository, scope: CoroutineScope): CoachQuotaRepository =
        CoachQuotaRepository(api, session, scope)

    /** Background coach requests are followed while the app is in the foreground (spec 006). */
    @Provides
    @Singleton
    fun coachActivity(
        api: CoachApi,
        quotas: CoachQuotaRepository,
        session: SessionRepository,
        scope: CoroutineScope,
    ): CoachActivity {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .stateIn(scope, SharingStarted.Eagerly, false)
        return CoachActivity(api, quotas, session, foreground, scope)
    }

    /** One intervals.icu sync for the whole app (spec 007). */
    @Provides
    @Singleton
    fun intervalsSync(
        api: ProfileApi,
        changes: DataChanges,
        clock: Clock,
        session: SessionRepository,
        scope: CoroutineScope,
    ): IntervalsSync = IntervalsSync(api, changes, clock, session, scope)

    @Provides
    @Singleton
    fun rideFileSource(@ApplicationContext context: Context): RideFileSource = ContentResolverFileSource(context)
}
