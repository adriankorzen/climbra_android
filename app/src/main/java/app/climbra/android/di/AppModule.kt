package app.climbra.android.di

import android.content.Context
import app.climbra.android.AppConfig
import app.climbra.android.data.api.AuthApi
import app.climbra.android.data.net.EncryptedFileCookieStore
import app.climbra.android.data.net.KeystoreCipher
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.PersistentCookieJar
import app.climbra.android.data.net.SessionEvents
import app.climbra.android.session.AppCompatLocales
import app.climbra.android.session.AppLocales
import app.climbra.android.session.SessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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
}
