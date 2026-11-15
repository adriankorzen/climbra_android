package app.climbra.android.coach

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.activities.workoutTypeName
import app.climbra.android.calendar.methodologyName
import app.climbra.android.data.api.ActivityReview
import app.climbra.android.data.api.CoachConversation
import app.climbra.android.data.api.PlanAssessment
import app.climbra.android.data.api.PlannedWorkout
import app.climbra.android.data.api.ReviewPlanChange
import app.climbra.android.data.api.TrainingBlock
import app.climbra.android.data.api.TrainingWeek
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors
import app.climbra.android.ui.workout.WorkoutStepChart
import app.climbra.android.ui.workout.WorkoutStepsTable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import kotlinx.coroutines.launch

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier.semantics { heading() })
}

@Composable
private fun Bullets(items: List<String>, tag: String) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (items.isEmpty()) {
            Text("–", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extendedColors.textFaint)
        }
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * The latest review of a ride (the web's `review-panel`): RPEs, findings, recommendations by category, the next
 * session, and the coach's plan assessment read-only (its actions are spec 007's).
 */
@Composable
fun ReviewPanel(review: ActivityReview, planChange: ReviewPlanChange?, fmt: Fmt, onOpenWorkout: (PlannedWorkout) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("review-panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(workoutTypeName(review.sessionType), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.testTag("review-session-type"))
            LabeledValue(stringResource(R.string.review_estimated_rpe), fmt.num(review.estimatedRpe), Modifier.testTag("review-estimated-rpe"))
            LabeledValue(stringResource(R.string.review_rider_rpe), fmt.num(review.riderRpe), Modifier.testTag("review-rider-rpe"))
        }
        Text(review.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("review-summary"))
        review.rpeAssessment?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("review-assessment"))
        }
        SectionTitle(stringResource(R.string.review_strengths))
        Bullets(review.strengths, "review-strengths")
        SectionTitle(stringResource(R.string.review_issues))
        Bullets(review.issues, "review-issues")
        SectionTitle(stringResource(R.string.review_recommendations))
        // Grouped by category in the order the categories first appear
        val groups = review.recommendations.groupBy { it.category }
        Column(Modifier.testTag("review-recommendations"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (category, items) ->
                Column(Modifier.testTag("recommendation-$category")) {
                    Text(recommendationName(category), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.extendedColors.textFaint)
                    items.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        val assessment = review.planAssessment
        when {
            assessment != null -> PlanAssessmentSection(assessment, review.nextSession, planChange, fmt, onOpenWorkout)
            review.nextSession != null -> {
                SectionTitle(stringResource(R.string.review_next_session))
                WorkoutTile(review.nextSession, fmt, onOpenWorkout, tag = "next-session")
            }
        }
    }
}

@Composable
private fun PlanAssessmentSection(
    assessment: PlanAssessment,
    next: PlannedWorkout?,
    change: ReviewPlanChange?,
    fmt: Fmt,
    onOpenWorkout: (PlannedWorkout) -> Unit,
) {
    Column(Modifier.testTag("plan-assessment"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (assessment.verdict == PlanAssessment.VERDICT_KEEP) {
            SectionTitle(stringResource(R.string.review_next_planned))
            change?.plannedWorkout?.let { WorkoutTile(it, fmt, onOpenWorkout, tag = "planned-tile") }
            Text(stringResource(R.string.review_keeps_plan, assessment.reason), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("keeps-plan"))
        } else {
            SectionTitle(stringResource(R.string.review_suggests_change, fmt.date(assessment.workoutDate, DateStyle.WEEKDAY)), Modifier.testTag("suggests-change"))
            Text(stringResource(R.string.review_planned), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.extendedColors.textFaint)
            change?.plannedWorkout?.let { WorkoutTile(it, fmt, onOpenWorkout, tag = "planned-tile") }
                ?: Text("–", color = MaterialTheme.extendedColors.textFaint)
            Text(stringResource(R.string.review_proposed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.extendedColors.textFaint)
            next?.let { WorkoutTile(it, fmt, onOpenWorkout, tag = "proposed-tile") }
            Text(assessment.reason, style = MaterialTheme.typography.bodyMedium)
            when (change?.status) {
                ReviewPlanChange.STATUS_APPLIED -> Pill(
                    stringResource(R.string.review_in_plan),
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    Modifier.testTag("in-plan"),
                )
                "NOT_APPLICABLE" -> Text(stringResource(R.string.review_not_applicable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint)
            }
        }
    }
}

/** Monday first, as the backend's weeks. */
private val WEEK_DAYS = DayOfWeek.entries

/** The workouts of a week by weekday, Monday first (the web's `workoutsByWeekday`). */
internal fun workoutsByDay(week: TrainingWeek): List<List<PlannedWorkout>> = WEEK_DAYS.map { day ->
    week.workouts.filter { workout ->
        try {
            LocalDate.parse(workout.date.take(10)).dayOfWeek == day
        } catch (e: DateTimeParseException) {
            false
        }
    }
}

/**
 * The training block (the web's `training-block-view`, phone layout): its head, then one week at a time in a pager
 * with its days Monday–Sunday.
 */
@Composable
fun TrainingBlockPanel(block: TrainingBlock, fmt: Fmt, onOpenWorkout: (PlannedWorkout) -> Unit) {
    val scope = rememberCoroutineScope()
    val weeks = block.weeks
    val pager = rememberPagerState { weeks.size }
    Column(Modifier.fillMaxWidth().testTag("block-panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(block.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }.testTag("block-name"))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${fmt.date(block.startDate, DateStyle.DAY_MONTH_YEAR)} – ${fmt.date(block.endDate, DateStyle.DAY_MONTH_YEAR)}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Pill(methodologyName(block.methodology), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        }
        block.goal?.takeIf { it.isNotBlank() }?.let {
            Text("${stringResource(R.string.block_goal)}: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("block-goal"))
        }
        block.rationale?.takeIf { it.isNotBlank() }?.let { Rationale(it) }
        if (weeks.isEmpty()) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                enabled = pager.currentPage > 0,
                modifier = Modifier.testTag("week-previous"),
            ) { Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.block_previous_week)) }
            Text(
                stringResource(R.string.block_week_of, pager.currentPage + 1, weeks.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).testTag("week-of"),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                enabled = pager.currentPage < weeks.size - 1,
                modifier = Modifier.testTag("week-next"),
            ) { Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.block_next_week)) }
        }
        HorizontalPager(state = pager, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().testTag("week-pager")) { page ->
            Week(weeks[page], fmt, onOpenWorkout)
        }
    }
}

@Composable
private fun Rationale(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp).testTag("block-rationale"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.block_rationale), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.rotate(if (expanded) 180f else 0f))
        }
        if (expanded) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("block-rationale-text"))
        }
    }
}

@Composable
private fun Week(week: TrainingWeek, fmt: Fmt, onOpenWorkout: (PlannedWorkout) -> Unit) {
    val locale = fmt.locale
    Column(Modifier.fillMaxWidth().testTag("week-${week.weekNumber}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.block_week, week.weekNumber), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("${fmt.num(week.plannedTss)} TSS", style = MaterialTheme.typography.labelLarge, fontFamily = MonoFamily, modifier = Modifier.testTag("week-tss-${week.weekNumber}"))
            if (week.isRecoveryWeek) {
                Pill(stringResource(R.string.calendar_recovery), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Modifier.testTag("week-recovery-${week.weekNumber}"))
            }
        }
        week.focus?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint)
        }
        workoutsByDay(week).forEachIndexed { index, workouts ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    WEEK_DAYS[index].getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp).padding(top = 8.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (workouts.isEmpty()) {
                        Text("–", color = MaterialTheme.extendedColors.textFaint, modifier = Modifier.padding(top = 8.dp))
                    }
                    workouts.forEachIndexed { i, workout ->
                        WorkoutTile(workout, fmt, onOpenWorkout, tag = "block-workout-${week.weekNumber}-$index-$i")
                    }
                }
            }
        }
    }
}

/** The plan actions of a training block conversation (the web's `plan-actions`). */
@Composable
fun PlanActions(
    conversation: CoachConversation,
    saving: Boolean,
    onSave: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val plan = conversation.plan
    val disabled = saving || conversation.busy
    Column(Modifier.fillMaxWidth().testTag("plan-actions"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (plan == null) {
            Button(onClick = onSave, enabled = !disabled, modifier = Modifier.testTag("add-to-calendar")) {
                Icon(painterResource(R.drawable.ic_calendar_month), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.calendar_add))
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenCalendar, modifier = Modifier.testTag("in-calendar")) {
                    Icon(painterResource(R.drawable.ic_calendar_month), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.calendar_in_calendar))
                }
                if (plan.outdated) {
                    Button(onClick = onSave, enabled = !disabled, modifier = Modifier.testTag("update-in-calendar")) {
                        Icon(painterResource(R.drawable.ic_refresh), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.calendar_update))
                    }
                }
            }
            if (plan.outdated) {
                Text(stringResource(R.string.calendar_outdated_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint, modifier = Modifier.testTag("outdated-hint"))
            }
        }
    }
}

/** Asks before a saved block cuts another plan. */
@Composable
fun OverlapDialog(name: String, startDate: String?, saving: Boolean, fmt: Fmt, onReplace: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.calendar_overlap_title)) },
        text = { Text(stringResource(R.string.calendar_overlap_confirm, name, fmt.date(startDate, DateStyle.DAY_MONTH_YEAR))) },
        confirmButton = {
            TextButton(onClick = onReplace, enabled = !saving, modifier = Modifier.testTag("overlap-replace")) {
                Text(stringResource(R.string.calendar_replace))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
        modifier = Modifier.testTag("overlap-dialog"),
    )
}

/** A proposed workout in detail (the web's `workout-detail` in a dialog), as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailSheet(workout: PlannedWorkout, fmt: Fmt, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        WorkoutDetailContent(workout, fmt)
    }
}

@Composable
fun WorkoutDetailContent(workout: PlannedWorkout, fmt: Fmt) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("workout-sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(workout.title, style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Pill(workoutTypeName(workout.type), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            Text(fmt.date(workout.date, DateStyle.LONG), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (workout.indoor) {
                Pill(stringResource(R.string.workout_indoor), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Row(Modifier.fillMaxWidth().testTag("sheet-figures")) {
            SheetFigure(stringResource(R.string.activity_duration), fmt.hm(workout.plannedDurationMinutes * 60), Modifier.weight(1f))
            SheetFigure(stringResource(R.string.adherence_tss), fmt.num(workout.plannedTss), Modifier.weight(1f))
            SheetFigure(stringResource(R.string.adherence_if), fmt.num(workout.plannedIf, 2), Modifier.weight(1f))
            SheetFigure(stringResource(R.string.workout_target_rpe), fmt.num(workout.targetRpe), Modifier.weight(1f))
        }
        workout.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (workout.blocks.any { it.steps.isNotEmpty() }) {
            Text(stringResource(R.string.workout_profile), style = MaterialTheme.typography.titleSmall)
            WorkoutStepChart(workout.blocks, Modifier.fillMaxWidth().height(140.dp))
            WorkoutStepsTable(workout.blocks, fmt)
            if (workout.blocks.any { block -> block.steps.any { it.powerHighWatts == null } }) {
                Text(stringResource(R.string.workout_no_ftp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint)
            }
        }
    }
}

@Composable
private fun SheetFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily)
    }
}
