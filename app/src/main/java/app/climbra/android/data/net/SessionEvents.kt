package app.climbra.android.data.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.Response

/** What any response can say about the session, whichever screen made the request (spec 001). */
enum class SessionEvent {
    /** `401`: the session has ended. */
    UNAUTHORIZED,

    /** `403 TERMS_NOT_ACCEPTED`: a new version of the legal documents must be accepted. */
    TERMS_REQUIRED,
}

class SessionEvents {
    private val flow = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SessionEvent> = flow.asSharedFlow()

    fun emit(event: SessionEvent) {
        flow.tryEmit(event)
    }
}

/** Reports [SessionEvent]s; the login requests themselves answer `401` for wrong credentials, not for a session. */
class SessionEventsInterceptor(private val events: SessionEvents) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        when {
            response.code == 401 && request.url.encodedPath !in LOGIN_PATHS -> events.emit(SessionEvent.UNAUTHORIZED)
            response.code == 403 && response.peekBody(4096).string().contains(TERMS_NOT_ACCEPTED) ->
                events.emit(SessionEvent.TERMS_REQUIRED)
        }
        return response
    }

    companion object {
        private const val TERMS_NOT_ACCEPTED = "\"TERMS_NOT_ACCEPTED\""
        private val LOGIN_PATHS = setOf("/api/auth/login", "/api/auth/google/id-token", "/api/auth/logout")
    }
}
