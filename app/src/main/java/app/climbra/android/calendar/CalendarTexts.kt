package app.climbra.android.calendar

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.climbra.android.R
import app.climbra.android.activities.humanize
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.GarminSendResult
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.api.SuggestionReason
import app.climbra.android.data.net.ApiError
import app.climbra.android.ui.common.TRANSLATED_ERROR_CODES
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt

/** What a finished calendar or workout action tells the rider in a snackbar. */
sealed interface CalendarMessage {
    data class Failed(val error: ApiError) : CalendarMessage
    data class PlanRemoved(val name: String) : CalendarMessage

    /** [from] and [to] are the 2 weeks of the import the connection named. */
    data class GarminPlanSent(val result: GarminSendResult, val from: String?, val to: String?) : CalendarMessage
    data class GarminWorkoutSent(val result: GarminSendResult) : CalendarMessage
    data class Saved(val fileName: String) : CalendarMessage
    data object SaveFailed : CalendarMessage
}

/** The message in words; a Garmin result gets one line per part, as the web's `reportGarminSend` toasts. */
@Composable
fun messageText(message: CalendarMessage, fmt: Fmt): String = when (message) {
    is CalendarMessage.Failed -> message.error.toUiText().resolve()
    is CalendarMessage.PlanRemoved -> stringResource(R.string.calendar_removed, message.name)
    is CalendarMessage.GarminPlanSent -> garminLines(
        stringResource(
            R.string.garmin_imported,
            fmt.date(message.from, DateStyle.DAY_MONTH),
            fmt.date(message.to, DateStyle.DAY_MONTH),
            message.result.sent,
            message.result.unchanged,
            message.result.removed,
        ),
        message.result,
        fmt,
    )
    is CalendarMessage.GarminWorkoutSent -> garminLines(
        stringResource(if (message.result.sent > 0) R.string.garmin_workout_sent else R.string.garmin_workout_not_sent),
        message.result,
        fmt,
    )
    is CalendarMessage.Saved -> stringResource(R.string.activity_saved_to_downloads, message.fileName)
    CalendarMessage.SaveFailed -> stringResource(R.string.activity_save_failed)
}

@Composable
private fun garminLines(summary: String, result: GarminSendResult, fmt: Fmt): String {
    val lines = mutableListOf(summary)
    if (result.failed.isNotEmpty()) {
        val titles = result.failed.joinToString(", ") { it.title ?: fmt.date(it.date, DateStyle.DAY_MONTH_YEAR) }
        lines += stringResource(R.string.garmin_failed, titles)
    }
    val reason = result.reason
    if (result.incomplete && reason != null) {
        lines += TRANSLATED_ERROR_CODES[reason]?.let { stringResource(it) } ?: reason
    }
    return lines.joinToString("\n")
}

/** Why a send needs a connection first (the web's `garmin.status.*`, and where to connect for none). */
@Composable
fun garminStatusText(status: GarminStatus): String = stringResource(
    when (status) {
        GarminStatus.NO_PERMISSION -> R.string.garmin_status_no_permission
        GarminStatus.NEEDS_RECONNECT -> R.string.garmin_status_needs_reconnect
        else -> R.string.garmin_connect_first
    },
)

@Composable
fun methodologyName(key: String?): String = when (key) {
    "AUTO" -> stringResource(R.string.methodology_auto)
    "POLARIZED" -> stringResource(R.string.methodology_polarized)
    "PYRAMIDAL" -> stringResource(R.string.methodology_pyramidal)
    "SWEET_SPOT" -> stringResource(R.string.methodology_sweet_spot)
    null -> "–"
    else -> humanize(key)
}

@Composable
fun workoutStatusName(key: String?): String = when (key) {
    "PLANNED" -> stringResource(R.string.workout_status_planned)
    "COMPLETED" -> stringResource(R.string.workout_status_completed)
    "MISSED" -> stringResource(R.string.workout_status_missed)
    "SKIPPED" -> stringResource(R.string.workout_status_skipped)
    "REST" -> stringResource(R.string.workout_status_rest)
    null -> "–"
    else -> humanize(key)
}

@Composable
fun stepKindName(key: String): String = when (key) {
    "WARMUP" -> stringResource(R.string.step_kind_warmup)
    "WORK" -> stringResource(R.string.step_kind_work)
    "RECOVERY" -> stringResource(R.string.step_kind_recovery)
    "COOLDOWN" -> stringResource(R.string.step_kind_cooldown)
    "STEADY" -> stringResource(R.string.step_kind_steady)
    else -> humanize(key)
}

/**
 * A suggestion reason in plain words (the web's `reasonText`), e.g. "Thu, 5 Nov – VO2max: 3 of 6 intervals";
 * [workouts] name the workout of a reason.
 */
@Composable
fun reasonText(reason: SuggestionReason, workouts: List<CalendarWorkout>, fmt: Fmt): String {
    val day = fmt.date(reason.date, DateStyle.WEEKDAY)
    val title = workouts.firstOrNull { it.id == reason.plannedWorkoutId }?.title
        ?: stringResource(R.string.adherence_workout)
    val planned = reasonValue(reason.metric, reason.planned, fmt)
    val actual = reasonValue(reason.metric, reason.actual, fmt)
    return when (reason.code) {
        "WORKOUT_DEVIATION" -> when (reason.metric) {
            "tss" -> stringResource(R.string.adherence_reason_tss, day, title, actual, planned)
            "intensityFactor" -> stringResource(R.string.adherence_reason_if, day, title, actual, planned)
            "intervals" -> stringResource(R.string.adherence_reason_intervals, day, title, actual, planned)
            else -> stringResource(R.string.adherence_reason_duration, day, title, actual, planned)
        }
        "KEY_WORKOUT_MISSED" -> stringResource(R.string.adherence_reason_key_missed, day, title)
        "WORKOUTS_MISSED" -> stringResource(R.string.adherence_reason_workouts_missed, actual)
        "UNPLANNED_LOAD" -> stringResource(R.string.adherence_reason_unplanned_load, day, actual)
        "WEEK_LOAD_DEVIATION" -> stringResource(
            R.string.adherence_reason_week_load,
            reason.weekNumber?.toString() ?: "–",
            actual,
            planned,
        )
        else -> humanize(reason.code)
    }
}

private fun reasonValue(metric: String?, value: Double?, fmt: Fmt): String = when {
    value == null -> Fmt.DASH
    metric == "duration" -> fmt.hm(value)
    metric == "intensityFactor" -> fmt.num(value, 2)
    else -> fmt.num(value)
}
