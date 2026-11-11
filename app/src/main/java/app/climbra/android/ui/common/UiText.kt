package app.climbra.android.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.climbra.android.R
import app.climbra.android.data.net.ApiError

/** A translated message with its arguments, resolved in the UI in the current language. */
data class UiText(@StringRes val res: Int, val args: List<Any> = emptyList()) {
    @Composable
    fun resolve(): String = stringResource(res, *args.toTypedArray())
}

/** Backend error codes with a translated message, like `TRANSLATED_ERROR_CODES` of the web app. */
val TRANSLATED_ERROR_CODES: Map<String, Int> = mapOf(
    "BAD_CREDENTIALS" to R.string.api_error_bad_credentials,
    "EMAIL_NOT_VERIFIED" to R.string.api_error_email_not_verified,
    "LOGIN_LOCKED" to R.string.api_error_login_locked,
    "TOO_MANY_REQUESTS" to R.string.api_error_too_many_requests,
    "TERMS_NOT_ACCEPTED" to R.string.api_error_terms_not_accepted,
    "HEALTH_CONSENT_REQUIRED" to R.string.api_error_health_consent_required,
    "UNAUTHENTICATED" to R.string.api_error_unauthenticated,
    "GOOGLE_FAILED" to R.string.login_google_failed,
    "ACTIVITY_BUSY" to R.string.api_error_activity_busy,
    "FILE_STORAGE_UNAVAILABLE" to R.string.api_error_file_storage_unavailable,
    "FILE_PROCESSING_BUSY" to R.string.api_error_file_processing_busy,
    "WORKOUT_COMPLETED" to R.string.api_error_workout_completed,
)

fun ApiError.toUiText(): UiText = when (this) {
    ApiError.Network -> UiText(R.string.error_unreachable)
    ApiError.Unexpected -> UiText(R.string.error_unexpected)
    is ApiError.Http -> TRANSLATED_ERROR_CODES[code]?.let { UiText(it) }
        ?: UiText(R.string.error_request_failed, listOf(status))
}
