package app.climbra.android.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.activities.humanize
import app.climbra.android.activities.workoutTypeName
import app.climbra.android.data.api.CoachQuota
import app.climbra.android.data.api.ConversationType
import app.climbra.android.data.api.PlannedWorkout
import app.climbra.android.data.net.ApiError
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors

/** What decides whether a button may send a request: the credits and the account's running request (spec 006). */
@Immutable
data class CoachLimits(val quota: CoachQuota? = null, val atCapacity: Boolean = false) {
    /** A request of [kind] may be sent; [free] for the free first review of a ride. */
    fun allows(kind: CostKind, free: Boolean = false): Boolean = !atCapacity && (free || quota.covers(kind))
}

@Composable
fun conversationTypeName(type: String): String = when (type) {
    ConversationType.ACTIVITY_REVIEW -> stringResource(R.string.conversation_type_activity_review)
    ConversationType.FILE_REVIEW -> stringResource(R.string.conversation_type_file_review)
    ConversationType.TRAINING_BLOCK -> stringResource(R.string.conversation_type_training_block)
    ConversationType.PLAN_ADJUSTMENT -> stringResource(R.string.conversation_type_plan_adjustment)
    else -> humanize(type)
}

/** The type badge of a conversation, in the type's color as the web's `badge review / file / block / adjustment`. */
@Composable
fun ConversationTypeBadge(type: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (type) {
        ConversationType.ACTIVITY_REVIEW -> colors.primaryContainer to colors.onPrimaryContainer
        ConversationType.TRAINING_BLOCK -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Pill(conversationTypeName(type), container, content, modifier)
}

@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** The headline of a refused request by its status (the web's `coachErrorKey`). */
fun coachErrorHeadline(error: ApiError): Int = when (error) {
    ApiError.Network -> R.string.error_unreachable
    ApiError.Unexpected -> R.string.coach_error_other
    is ApiError.Http -> when (error.status) {
        400 -> R.string.coach_error_validation
        409 -> R.string.coach_error_conflict
        422 -> R.string.coach_error_unprocessable
        429 -> R.string.coach_error_limit
        502, 504 -> R.string.coach_error_provider
        503 -> R.string.coach_error_not_configured
        else -> R.string.coach_error_other
    }
}

/** A refused request: the headline by status and the backend's detail below it. */
@Composable
fun CoachRequestError(error: ApiError, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(coachErrorHeadline(error)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (error !is ApiError.Network) {
                Text(error.toUiText().resolve(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The text of a failed background request (`coachRequest.errors.*`). */
fun requestErrorText(code: String?): Int = when (code) {
    "PROVIDER_ERROR" -> R.string.coach_request_error_provider_error
    "PROVIDER_QUOTA" -> R.string.coach_request_error_provider_quota
    "REFUSED" -> R.string.coach_request_error_refused
    "INVALID_ANSWER" -> R.string.coach_request_error_invalid_answer
    "TIMEOUT" -> R.string.coach_request_error_timeout
    "INTERRUPTED" -> R.string.coach_request_error_interrupted
    "NOT_CONFIGURED" -> R.string.coach_request_error_not_configured
    else -> R.string.coach_request_error_unexpected
}

@Composable
fun recommendationName(category: String): String = when (category) {
    "PACING" -> stringResource(R.string.recommendation_pacing)
    "INTENSITY" -> stringResource(R.string.recommendation_intensity)
    "VOLUME" -> stringResource(R.string.recommendation_volume)
    "RECOVERY" -> stringResource(R.string.recommendation_recovery)
    "NUTRITION" -> stringResource(R.string.recommendation_nutrition)
    "TECHNIQUE" -> stringResource(R.string.recommendation_technique)
    "OTHER" -> stringResource(R.string.recommendation_other)
    else -> humanize(category)
}

/**
 * The cost line under a button that sends a request of [kind] (the web's `coach-quota` in cost mode): the running
 * request's hint, free, unlimited, not enough credits in the error color, or the cost and what is left.
 */
@Composable
fun CoachCostLine(limits: CoachLimits, kind: CostKind, fmt: Fmt, modifier: Modifier = Modifier, free: Boolean = false) {
    val quota = limits.quota
    val faint = MaterialTheme.extendedColors.textFaint
    val error = MaterialTheme.colorScheme.error
    val (text, color) = when {
        limits.atCapacity -> stringResource(R.string.coach_busy_hint) to error
        quota == null -> return
        free -> stringResource(R.string.quota_free) to MaterialTheme.colorScheme.primary
        quota.unlimited -> stringResource(R.string.quota_uses_unlimited, quota.cost(kind)) to faint
        !quota.covers(kind) -> stringResource(R.string.quota_not_enough, quota.cost(kind), quota.remaining, resetDate(quota, fmt)) to error
        else -> stringResource(R.string.quota_uses, quota.cost(kind), quota.remaining) to faint
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier.testTag("cost-line").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun resetDate(quota: CoachQuota, fmt: Fmt): String = fmt.instant(quota.resetsAt ?: quota.monthly?.resetsAt, withTime = false)

/** The credits under the coach's title (the web's `coach-quota` in summary mode). */
@Composable
fun CreditsSummary(quota: CoachQuota?, fmt: Fmt, modifier: Modifier = Modifier) {
    quota ?: return
    val faint = MaterialTheme.extendedColors.textFaint
    FlowRow(
        modifier = modifier.testTag("credits-summary"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (quota.unlimited) {
            Text(stringResource(R.string.quota_unlimited, quota.used), style = MaterialTheme.typography.bodySmall, color = faint)
            return@FlowRow
        }
        Pill(
            stringResource(if (quota.plan == "SUBSCRIPTION") R.string.quota_plan_subscription else R.string.quota_plan_free),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (quota.exhausted) {
            Text(
                stringResource(R.string.quota_exhausted, resetDate(quota, fmt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("credits-exhausted"),
            )
            return@FlowRow
        }
        quota.monthly?.let { monthly ->
            Text(
                stringResource(R.string.quota_remaining, monthly.remaining, monthly.limit, fmt.instant(monthly.resetsAt, withTime = false)),
                style = MaterialTheme.typography.bodySmall,
                color = faint,
            )
        }
        quota.bonus.firstOrNull()?.let { earliest ->
            Text(
                stringResource(R.string.quota_bonus, quota.bonus.sumOf { it.remaining }, fmt.instant(earliest.expiresAt, withTime = false)),
                style = MaterialTheme.typography.bodySmall,
                color = faint,
                modifier = Modifier.testTag("credits-bonus"),
            )
        }
    }
}

/** Under every input to the coach (cyclingapp spec 031). */
@Composable
fun HealthNote(onOpenTerms: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag("health-note")) {
        Text(stringResource(R.string.health_note_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint)
        TextButton(onClick = onOpenTerms, modifier = Modifier.testTag("health-note-terms")) {
            Text(stringResource(R.string.health_note_terms), style = MaterialTheme.typography.labelMedium)
        }
    }
}

private val RPE_TEXTS = listOf(
    R.string.rpe_1, R.string.rpe_2, R.string.rpe_3, R.string.rpe_4, R.string.rpe_5,
    R.string.rpe_6, R.string.rpe_7, R.string.rpe_8, R.string.rpe_9, R.string.rpe_10,
)

/** RPE 1–10 in two rows of five; tapping the selected value clears it. */
@Composable
fun RpeScale(value: Int?, onChange: (Int?) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier.testTag("rpe-scale"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.rpe_label), style = MaterialTheme.typography.labelLarge)
        listOf(1..5, 6..10).forEach { values ->
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                values.forEachIndexed { index, rpe ->
                    SegmentedButton(
                        selected = value == rpe,
                        onClick = { onChange(if (value == rpe) null else rpe) },
                        shape = SegmentedButtonDefaults.itemShape(index, values.count()),
                        enabled = enabled,
                        icon = {},
                        modifier = Modifier.testTag("rpe-$rpe"),
                    ) { Text("$rpe", fontFamily = MonoFamily) }
                }
            }
        }
        Text(
            value?.let { stringResource(RPE_TEXTS[it - 1]) } ?: stringResource(R.string.rpe_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("rpe-description"),
        )
    }
}

/** A workout the coach proposes (the web's `workout-tile`); tapping opens its detail. */
@Composable
fun WorkoutTile(workout: PlannedWorkout, fmt: Fmt, onOpen: (PlannedWorkout) -> Unit, modifier: Modifier = Modifier, tag: String = "workout-tile") {
    OutlinedCard(onClick = { onOpen(workout) }, modifier = modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(workoutTypeName(workout.type), stringResource(R.string.workout_indoor).takeIf { workout.indoor }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.extendedColors.textFaint,
            )
            Text(workout.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!workout.rest) {
                Text(
                    "${fmt.hm(workout.plannedDurationMinutes * 60)} · ${fmt.num(workout.plannedTss)} TSS · RPE ${fmt.num(workout.targetRpe)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Mon, 16 Nov" of an ISO date. */
fun Fmt.weekday(date: String?): String = date(date, DateStyle.WEEKDAY)

/** A row label with a value, for the review's RPEs. */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.extendedColors.textFaint)
        Text(value, style = MaterialTheme.typography.labelLarge, fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold)
    }
}
