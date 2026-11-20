package app.climbra.android.testing

import app.climbra.android.activities.RideImport
import app.climbra.android.coach.CoachActivity
import app.climbra.android.coach.CoachQuotaRepository
import app.climbra.android.coach.RideFileSource
import app.climbra.android.data.api.ActivityApi
import app.climbra.android.data.api.AuthApi
import app.climbra.android.data.api.CalendarApi
import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.api.FeedbackApi
import app.climbra.android.data.api.GarminApi
import app.climbra.android.data.api.ImportApi
import app.climbra.android.data.api.ProfileApi
import app.climbra.android.data.api.RiderApi
import app.climbra.android.data.net.ByteCipher
import app.climbra.android.data.net.Connectivity
import app.climbra.android.data.net.CookieStore
import app.climbra.android.data.net.EncryptedFileCookieStore
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.OfflineCache
import app.climbra.android.data.net.OfflineInterceptor
import app.climbra.android.data.net.PersistentCookieJar
import app.climbra.android.data.net.SessionEvents
import app.climbra.android.garmin.GarminSync
import app.climbra.android.intervals.IntervalsSync
import app.climbra.android.rider.DataChanges
import app.climbra.android.session.AppLocales
import app.climbra.android.session.SessionRepository
import java.io.Closeable
import java.io.File
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf

/**
 * The backend as a [MockWebServer] behind exactly the app's HTTP stack ([Network]): real cookie jar, CSRF and session
 * event interceptors. Answers are queued per `"METHOD /path"`; `GET /api/auth/csrf` sets a token cookie by default.
 */
class TestBackend(
    cookieFile: File = File.createTempFile("cookies", ".bin").apply { delete() },
) : Closeable {

    val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        start()
    }
    val events = SessionEvents()
    /** The answers read offline (spec 018), with the XOR stand-in for the Keystore. */
    val offlineDir: File = createTempDirectory("offline").toFile()
    val offlineCache = OfflineCache(offlineDir, XorCipher)
    val connectivity = Connectivity()
    val store: CookieStore = EncryptedFileCookieStore(cookieFile, XorCipher)
    val cookies = PersistentCookieJar(store)
    val client = Network.okHttpClient(cookies, events, OfflineInterceptor(offlineCache, connectivity))
    private val retrofit = Network.retrofit(server.url("/").toString(), client)
    val api: AuthApi = retrofit.create(AuthApi::class.java)
    val riderApi: RiderApi = retrofit.create(RiderApi::class.java)
    val activityApi: ActivityApi = retrofit.create(ActivityApi::class.java)
    val profileApi: ProfileApi = retrofit.create(ProfileApi::class.java)
    val calendarApi: CalendarApi = retrofit.create(CalendarApi::class.java)
    val coachApi: CoachApi = retrofit.create(CoachApi::class.java)
    val importApi: ImportApi = retrofit.create(ImportApi::class.java)
    val garminApi: GarminApi = retrofit.create(GarminApi::class.java)
    val feedbackApi: FeedbackApi = retrofit.create(FeedbackApi::class.java)
    val locales = FakeAppLocales()

    /** Requests in the order they arrived. */
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = mutableMapOf<String, ArrayDeque<MockResponse>>()
    private var csrfTokens = 0

    fun session(scope: CoroutineScope) = SessionRepository(api, cookies, events, locales, scope, offlineCache, connectivity)

    fun quotas(session: SessionRepository, scope: CoroutineScope) = CoachQuotaRepository(coachApi, session, scope)

    /** The coach's background requests, polled while [foreground] is true. */
    fun coachActivity(
        session: SessionRepository,
        scope: CoroutineScope,
        quotas: CoachQuotaRepository = quotas(session, scope),
        foreground: StateFlow<Boolean> = MutableStateFlow(true),
        pollMillis: Long = CoachActivity.POLL_MILLIS,
    ) = CoachActivity(coachApi, quotas, session, foreground, scope, pollMillis)

    /** The app's intervals.icu sync (spec 007). */
    fun intervalsSync(
        session: SessionRepository,
        scope: CoroutineScope,
        changes: DataChanges = DataChanges(),
        clock: Clock = Clock.systemUTC(),
        pollMillis: Long = IntervalsSync.POLL_MILLIS,
    ) = IntervalsSync(profileApi, changes, clock, session, scope, pollMillis)

    /** The admin's Garmin sync (spec 011). */
    fun garminSync(
        session: SessionRepository,
        scope: CoroutineScope,
        changes: DataChanges = DataChanges(),
        clock: Clock = Clock.systemUTC(),
    ) = GarminSync(garminApi, changes, clock, session, scope)

    /** Connecting Garmin Connect from the app (spec 016). */
    fun garminConnect(session: SessionRepository, scope: CoroutineScope) =
        app.climbra.android.garmin.GarminConnectFlow(profileApi, session, scope)

    /** The app's import of several rides (spec 009). */
    fun rideImport(
        session: SessionRepository,
        scope: CoroutineScope,
        files: RideFileSource,
        changes: DataChanges = DataChanges(),
        pollMillis: Long = RideImport.POLL_MILLIS,
        cacheDir: File = createTempDirectory("ride-import").toFile(),
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ) = RideImport(importApi, files, changes, session, scope, cacheDir, pollMillis = pollMillis, sleep = sleep)

    /** Queues answers for `"POST /api/auth/login"`; the last one repeats. */
    fun on(route: String, vararg responses: MockResponse) {
        synchronized(routes) { routes.getOrPut(route) { ArrayDeque() }.addAll(responses) }
    }

    /** Answers [route] with [responses] from now on, instead of what was queued before. */
    fun replace(route: String, vararg responses: MockResponse) {
        synchronized(routes) { routes[route] = ArrayDeque(responses.toList()) }
    }

    fun requests(route: String) = requests.filter { "${it.method} ${it.url.encodedPath}" == route }

    private fun answer(request: RecordedRequest): MockResponse {
        requests += request
        val route = "${request.method} ${request.url.encodedPath}"
        synchronized(routes) {
            routes[route]?.let { queue -> return if (queue.size > 1) queue.removeFirst() else queue.first() }
        }
        if (route == "GET /api/auth/csrf") {
            csrfTokens++
            return MockResponse.Builder().code(204).addHeader("Set-Cookie", "XSRF-TOKEN=token-$csrfTokens; Path=/").build()
        }
        return problem(404, null)
    }

    /** The backend can no longer be reached: requests fail as without a network (spec 018). */
    fun goOffline() {
        server.close()
    }

    override fun close() {
        server.close()
        offlineDir.deleteRecursively()
    }

    companion object {
        fun json(body: String, code: Int = 200, vararg cookies: String): MockResponse = MockResponse.Builder()
            .code(code)
            .headers(headersOf("Content-Type", "application/json"))
            .apply { cookies.forEach { addHeader("Set-Cookie", it) } }
            .body(body)
            .build()

        fun noContent(vararg cookies: String): MockResponse =
            MockResponse.Builder().code(204).apply { cookies.forEach { addHeader("Set-Cookie", it) } }.build()

        fun problem(status: Int, code: String?, detail: String = "Error"): MockResponse = MockResponse.Builder()
            .code(status)
            .headers(headersOf("Content-Type", "application/problem+json"))
            .body(
                """{"type":"about:blank","title":"Error","status":$status,"detail":"$detail"""" +
                    (code?.let { ""","code":"$it"""" } ?: "") + "}",
            )
            .build()

        /** `AccountResponse` as the backend writes it, with a field the app does not know. */
        fun account(
            email: String = "anna@example.com",
            language: String = "pl",
            termsAccepted: Boolean = true,
            legalVersion: String? = "2026-10-01",
            hasPassword: Boolean = true,
            role: String = "USER",
        ) = """
            {"id":7,"email":"$email","role":"$role","language":"$language","riderId":3,"hasPassword":$hasPassword,
             "googleLinked":${!hasPassword},"termsAccepted":$termsAccepted,
             "legalVersion":${legalVersion?.let { "\"$it\"" } ?: "null"},"currentLegalVersion":"2026-10-01",
             "somethingNew":42}
        """.trimIndent()

        const val SESSION_COOKIE = "CYCLINGAPP_SESSION=session-1; Path=/; Max-Age=2592000; HttpOnly"
    }
}

/** Reversible stand-in for the Keystore cipher (the Android Keystore does not exist on the JVM). */
object XorCipher : ByteCipher {
    override fun encrypt(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
    override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
}

class FakeAppLocales : AppLocales {
    val calls = CopyOnWriteArrayList<String>()

    override fun useAccountLanguage(language: String?) {
        calls += "account:$language"
    }

    override fun useDeviceLanguage() {
        calls += "device"
    }
}
