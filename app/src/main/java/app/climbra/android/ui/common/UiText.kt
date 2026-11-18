package app.climbra.android.ui.common

import android.annotation.SuppressLint
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.climbra.android.R
import app.climbra.android.data.net.ApiError

/**
 * A translated message with its arguments, resolved in the UI in the current language; with [quantity], [res] is a
 * plurals resource.
 */
data class UiText(val res: Int, val args: List<Any> = emptyList(), val quantity: Int? = null) {
    // A string, or with a quantity a plurals resource
    @SuppressLint("ResourceType")
    @Composable
    fun resolve(): String =
        if (quantity != null) pluralStringResource(res, quantity, *args.toTypedArray())
        else stringResource(res, *args.toTypedArray())

    /** Outside composition, e.g. for a snackbar. */
    @SuppressLint("ResourceType")
    fun resolve(resources: Resources): String =
        if (quantity != null) resources.getQuantityString(res, quantity, *args.toTypedArray())
        else resources.getString(res, *args.toTypedArray())
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
    "PASSWORD_TOO_SHORT" to R.string.api_error_password_too_short,
    "PASSWORD_TOO_LONG" to R.string.api_error_password_too_long,
    "PASSWORD_EQUALS_EMAIL" to R.string.api_error_password_equals_email,
    "WRONG_PASSWORD" to R.string.api_error_wrong_password,
    "NO_PASSWORD" to R.string.api_error_no_password,
    "DELETE_CONFIRMATION_INVALID" to R.string.api_error_delete_confirmation_invalid,
    "ADMIN_ACCOUNT_UNDELETABLE" to R.string.api_error_admin_account_undeletable,
    "ACCOUNT_BUSY" to R.string.api_error_account_busy,
    "GARMIN_NOT_CONFIGURED" to R.string.api_error_garmin_not_configured,
    "GARMIN_NOT_CONNECTED" to R.string.api_error_garmin_not_connected,
    "GARMIN_NO_PERMISSION" to R.string.api_error_garmin_no_permission,
    "GARMIN_RECONNECT_NEEDED" to R.string.api_error_garmin_reconnect_needed,
    "GARMIN_NO_FTP" to R.string.api_error_garmin_no_ftp,
    "GARMIN_SEND_RUNNING" to R.string.api_error_garmin_send_running,
    "GARMIN_WORKOUT_NOT_SENDABLE" to R.string.api_error_garmin_workout_not_sendable,
    "GARMIN_RATE_LIMITED" to R.string.api_error_garmin_rate_limited,
    "GARMIN_UNAVAILABLE" to R.string.api_error_garmin_unavailable,
    "COACH_BUSY" to R.string.api_error_coach_busy,
    "COACH_MONTHLY_LIMIT" to R.string.api_error_coach_monthly_limit,
    "COACH_REVIEW_EXISTS" to R.string.ask_coach_review_exists,
    "CONVERSATION_BUSY" to R.string.api_error_conversation_busy,
    "CONVERSATION_READ_ONLY" to R.string.api_error_conversation_read_only,
    "PLAN_OVERLAP" to R.string.api_error_plan_overlap,
    "PLAN_NO_BLOCK" to R.string.api_error_plan_no_block,
    "PLAN_FINISHED" to R.string.api_error_plan_finished,
    "PLAN_ADJUSTMENT_RUNNING" to R.string.api_error_plan_adjustment_running,
    "PROPOSAL_OUTDATED" to R.string.api_error_proposal_outdated,
    "REVIEW_NO_PLAN_CHANGE" to R.string.api_error_review_no_plan_change,
    "PLAN_CHANGE_NOT_APPLICABLE" to R.string.api_error_plan_change_not_applicable,
    "IMPORT_RUNNING" to R.string.api_error_import_running,
    "IMPORT_QUEUE_FULL" to R.string.api_error_import_queue_full,
)

fun ApiError.toUiText(): UiText = when (this) {
    ApiError.Network -> UiText(R.string.error_unreachable)
    ApiError.Unexpected -> UiText(R.string.error_unexpected)
    is ApiError.Http -> TRANSLATED_ERROR_CODES[code]?.let { UiText(it) }
        ?: UiText(R.string.error_request_failed, listOf(status))
}
