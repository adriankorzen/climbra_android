package app.climbra.android.testing

import app.climbra.android.data.api.ActivityApi
import app.climbra.android.data.api.AuthApi
import app.climbra.android.data.api.RiderApi
import app.climbra.android.data.net.ByteCipher
import app.climbra.android.data.net.CookieStore
import app.climbra.android.data.net.EncryptedFileCookieStore
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.PersistentCookieJar
import app.climbra.android.data.net.SessionEvents
import app.climbra.android.session.AppLocales
import app.climbra.android.session.SessionRepository
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
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
    val store: CookieStore = EncryptedFileCookieStore(cookieFile, XorCipher)
    val cookies = PersistentCookieJar(store)
    val client = Network.okHttpClient(cookies, events)
    private val retrofit = Network.retrofit(server.url("/").toString(), client)
    val api: AuthApi = retrofit.create(AuthApi::class.java)
    val riderApi: RiderApi = retrofit.create(RiderApi::class.java)
    val activityApi: ActivityApi = retrofit.create(ActivityApi::class.java)
    val locales = FakeAppLocales()

    /** Requests in the order they arrived. */
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = mutableMapOf<String, ArrayDeque<MockResponse>>()
    private var csrfTokens = 0

    fun session(scope: CoroutineScope) = SessionRepository(api, cookies, events, locales, scope)

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

    override fun close() {
        server.close()
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
        ) = """
            {"id":7,"email":"$email","role":"USER","language":"$language","riderId":3,"hasPassword":true,
             "googleLinked":false,"termsAccepted":$termsAccepted,
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
