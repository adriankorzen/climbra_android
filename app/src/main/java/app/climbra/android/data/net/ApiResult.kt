package app.climbra.android.data.net

import android.util.Log
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** Problem details (RFC 9457) of the backend; `code` is a backend error code such as `BAD_CREDENTIALS`. */
@Serializable
data class ProblemDetail(
    val status: Int? = null,
    val title: String? = null,
    val detail: String? = null,
    val code: String? = null,
)

sealed interface ApiError {
    /** The backend answered with an error status. */
    data class Http(val status: Int, val code: String?) : ApiError

    /** No answer: offline, DNS, timeout, TLS. */
    data object Network : ApiError

    /** An answer the app does not understand. */
    data object Unexpected : ApiError
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

private const val LOG_TAG = "ClimbraApi"

/** Runs a Retrofit call and maps every failure to an [ApiError]. */
suspend fun <T> apiCall(json: Json, call: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(call())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    val body = e.response()?.errorBody()?.string()
    val problem = body?.let { runCatching { json.decodeFromString(ProblemDetail.serializer(), it) }.getOrNull() }
    ApiResult.Failure(ApiError.Http(e.code(), problem?.code))
} catch (e: IOException) {
    Log.w(LOG_TAG, "Request failed: ${e.javaClass.simpleName}: ${e.message}")
    ApiResult.Failure(ApiError.Network)
} catch (e: SerializationException) {
    ApiResult.Failure(ApiError.Unexpected)
} catch (e: IllegalArgumentException) {
    ApiResult.Failure(ApiError.Unexpected)
}
