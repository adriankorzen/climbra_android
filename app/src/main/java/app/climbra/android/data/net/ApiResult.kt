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
    /** With `COACH_REVIEW_EXISTS`: the ride's review conversation to continue (spec 006). */
    val conversationId: Long? = null,
    /** With `PLAN_OVERLAP`: the plan a saved training block overlaps. */
    val overlappingPlan: OverlappingPlan? = null,
)

@Serializable
data class OverlappingPlan(val id: Long? = null, val name: String = "", val startDate: String? = null, val endDate: String? = null)

sealed interface ApiError {
    /**
     * The backend answered with an error status; [conversationId] and [overlappingPlanName] come with
     * `COACH_REVIEW_EXISTS` and `PLAN_OVERLAP` (spec 006).
     */
    data class Http(
        val status: Int,
        val code: String?,
        val conversationId: Long? = null,
        val overlappingPlanName: String? = null,
    ) : ApiError

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

/**
 * The error of an answer with [status], with the backend's code from its problem details [body]; also for calls that
 * return the raw `Response` (a file download), which Retrofit does not throw for.
 */
fun httpError(json: Json, status: Int, body: String?): ApiError.Http {
    val problem = body?.let { runCatching { json.decodeFromString(ProblemDetail.serializer(), it) }.getOrNull() }
    return ApiError.Http(status, problem?.code, problem?.conversationId, problem?.overlappingPlan?.name)
}

/** Runs a Retrofit call and maps every failure to an [ApiError]. */
suspend fun <T> apiCall(json: Json, call: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(call())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    ApiResult.Failure(httpError(json, e.code(), e.response()?.errorBody()?.string()))
} catch (e: IOException) {
    Log.w(LOG_TAG, "Request failed: ${e.javaClass.simpleName}: ${e.message}")
    ApiResult.Failure(ApiError.Network)
} catch (e: SerializationException) {
    ApiResult.Failure(ApiError.Unexpected)
} catch (e: IllegalArgumentException) {
    ApiResult.Failure(ApiError.Unexpected)
}

/**
 * Runs a Retrofit call that returns the raw `Response` and maps an error status like [apiCall] does; the success keeps
 * the response for its headers.
 */
suspend fun <T> apiResponseCall(json: Json, call: suspend () -> retrofit2.Response<T>): ApiResult<retrofit2.Response<T>> =
    when (val result = apiCall(json, call)) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> {
            val response = result.value
            when {
                !response.isSuccessful -> ApiResult.Failure(httpError(json, response.code(), response.errorBody()?.string()))
                response.body() == null -> ApiResult.Failure(ApiError.Unexpected)
                else -> result
            }
        }
    }
