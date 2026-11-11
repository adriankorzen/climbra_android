package app.climbra.android.data.net

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * CSRF protection of the backend (Spring Security SPA mode, spec 001): every mutating request sends the value of the
 * `XSRF-TOKEN` cookie as the `X-XSRF-TOKEN` header. Without the cookie the token is fetched first from
 * `GET /api/auth/csrf`; a `403` for an invalid token (rotated, e.g. after a login) is retried once with a fresh one.
 */
class CsrfInterceptor(private val cookies: PersistentCookieJar) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method in SAFE_METHODS) {
            return chain.proceed(request)
        }
        val token = cookies.value(request.url, COOKIE) ?: fetchToken(chain, request)
        val response = chain.proceed(request.withToken(token))
        if (response.code != 403 || !isCsrfFailure(response)) {
            return response
        }
        response.close()
        return chain.proceed(request.withToken(fetchToken(chain, request)))
    }

    private fun fetchToken(chain: Interceptor.Chain, request: Request): String? {
        val csrfRequest = Request.Builder().url(request.url.resolve(CSRF_PATH)!!).get().build()
        chain.proceed(csrfRequest).close()
        return cookies.value(request.url, COOKIE)
    }

    private fun Request.withToken(token: String?): Request =
        if (token == null) this else newBuilder().header(HEADER, token).build()

    /** The backend answers `403 ACCESS_DENIED` with the detail "Invalid or missing CSRF token". */
    private fun isCsrfFailure(response: Response): Boolean =
        response.peekBody(PEEK_BYTES).string().contains("CSRF", ignoreCase = true)

    companion object {
        const val COOKIE = "XSRF-TOKEN"
        const val HEADER = "X-XSRF-TOKEN"
        const val CSRF_PATH = "/api/auth/csrf"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
        private const val PEEK_BYTES = 4096L
    }
}
