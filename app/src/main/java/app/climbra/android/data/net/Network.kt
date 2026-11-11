package app.climbra.android.data.net

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** The HTTP stack against the backend; one place, so tests use exactly what the app uses. */
object Network {

    /** DTOs mirror backend records; fields the app does not know yet are ignored. */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun okHttpClient(cookies: PersistentCookieJar, events: SessionEvents): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        // Outermost: sees the final answer, after a CSRF retry
        .addInterceptor(SessionEventsInterceptor(events))
        .addInterceptor(CsrfInterceptor(cookies))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
