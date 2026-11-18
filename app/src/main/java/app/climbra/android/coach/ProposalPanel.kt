package app.climbra.android.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.activities.humanize
import app.climbra.android.data.api.PlanProposal
import app.climbra.android.data.api.PlannedWorkout
import app.climbra.android.data.api.ProposalDay
import app.climbra.android.data.api.ProposalWeek
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.parseMarkdown

@Composable
fun proposalStatusName(status: String): String = when (status) {
    "PENDING" -> stringResource(R.string.proposal_status_pending)
    "ACCEPTED" -> stringResource(R.string.proposal_status_accepted)
    "REJECTED" -> stringResource(R.string.proposal_status_rejected)
    "OUTDATED" -> stringResource(R.string.proposal_status_outdated)
    else -> humanize(status)
}

@Composable
private fun changeName(change: String): String = when (change) {
    "ADDED" -> stringResource(R.string.proposal_change_added)
    "REMOVED" -> stringResource(R.string.proposal_change_removed)
    "CHANGED" -> stringResource(R.string.proposal_change_changed)
    else -> humanize(change)
}

/**
 * The coach's proposal to adjust a plan (spec 008, the web's `proposal-view`): status, summary, the changed days
 * before → after, the weeks' planned TSS, and Accept / Reject while it is pending. Everything is the backend's.
 */
@Composable
fun ProposalPanel(
    proposal: PlanProposal?,
    error: app.climbra.android.data.net.ApiError?,
    deciding: Boolean,
    fmt: Fmt,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onRetry: () -> Unit,
    onOpenWorkout: (PlannedWorkout) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("proposal-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            proposal != null -> Content(proposal, deciding, fmt, onAccept, onReject, onOpenWorkout)
            error != null -> {
                CoachRequestError(error, Modifier.testTag("proposal-error"))
                OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("proposal-retry")) { Text(stringResource(R.string.retry)) }
            }
            else -> Text(stringResource(R.string.proposal_loading), color = MaterialTheme.extendedColors.textFaint)
        }
    }
}

@Composable
private fun Content(
    proposal: PlanProposal,
    deciding: Boolean,
    fmt: Fmt,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onOpenWorkout: (PlannedWorkout) -> Unit,
) {
    val pending = proposal.status == PlanProposal.STATUS_PENDING
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(
            proposalStatusName(proposal.status),
            if (pending) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            if (pending) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier.testTag("proposal-status"),
        )
        Text(
            stringResource(R.string.proposal_changes_from, fmt.date(proposal.fromDate, DateStyle.DAY_MONTH_YEAR)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.extendedColors.textFaint,
            modifier = Modifier.testTag("proposal-from"),
        )
    }
    proposal.summary?.takeIf { it.isNotBlank() }?.let { summary ->
        val parsed = remember(summary) { parseMarkdown(summary) }
        Markdown(parsed, modifier = Modifier.testTag("proposal-summary"))
    }
    Text(stringResource(R.string.proposal_changed_days), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    val changed = proposal.days.filter { it.change != ProposalDay.CHANGE_UNCHANGED }
    if (changed.isEmpty()) {
        Text(stringResource(R.string.proposal_no_changes), color = MaterialTheme.extendedColors.textFaint, modifier = Modifier.testTag("proposal-no-changes"))
    }
    changed.forEach { day -> DayCard(day, fmt, onOpenWorkout) }
    if (proposal.weeks.isNotEmpty()) {
        Text(stringResource(R.string.proposal_weeks), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        WeeksTable(proposal.weeks, fmt)
    }
    if (pending) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAccept, enabled = !deciding, modifier = Modifier.testTag("accept-proposal")) {
                Text(stringResource(R.string.proposal_accept))
            }
            OutlinedButton(onClick = onReject, enabled = !deciding, modifier = Modifier.testTag("reject-proposal")) {
                Text(stringResource(R.string.proposal_reject))
            }
        }
    }
}

@Composable
private fun DayCard(day: ProposalDay, fmt: Fmt, onOpenWorkout: (PlannedWorkout) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("proposal-day-${day.date}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fmt.weekday(day.date), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Pill(changeName(day.change), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (day.completed) {
                Text(stringResource(R.string.proposal_completed_kept), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint, modifier = Modifier.testTag("completed-${day.date}"))
            }
            Side(stringResource(R.string.proposal_before), day.before, fmt, onOpenWorkout, "before-${day.date}")
            Side(stringResource(R.string.proposal_after), day.after, fmt, onOpenWorkout, "after-${day.date}")
        }
    }
}

@Composable
private fun Side(label: String, workout: PlannedWorkout?, fmt: Fmt, onOpenWorkout: (PlannedWorkout) -> Unit, tag: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.extendedColors.textFaint)
    if (workout != null) {
        WorkoutTile(workout, fmt, onOpenWorkout, tag = tag)
    } else {
        Text(Fmt.DASH, color = MaterialTheme.extendedColors.textFaint, modifier = Modifier.testTag("$tag-none"))
    }
}

@Composable
private fun WeeksTable(weeks: List<ProposalWeek>, fmt: Fmt) {
    Column(Modifier.fillMaxWidth().testTag("proposal-weeks")) {
        WeekRow(
            stringResource(R.string.proposal_week),
            stringResource(R.string.proposal_before),
            stringResource(R.string.proposal_after),
            stringResource(R.string.proposal_focus),
            header = true,
        )
        weeks.forEach { week ->
            WeekRow(
                fmt.num(week.weekNumber),
                fmt.num(week.plannedTssBefore),
                fmt.num(week.plannedTssAfter),
                week.focusAfter ?: week.focusBefore ?: Fmt.DASH,
                changed = week.plannedTssBefore != week.plannedTssAfter,
                modifier = Modifier.testTag("proposal-week-${week.weekNumber}"),
            )
        }
    }
}

@Composable
private fun WeekRow(
    week: String,
    before: String,
    after: String,
    focus: String,
    modifier: Modifier = Modifier,
    header: Boolean = false,
    changed: Boolean = false,
) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val background = if (changed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    Row(modifier.fillMaxWidth().background(background).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(week, style = style, fontFamily = MonoFamily, modifier = Modifier.weight(0.6f))
        Text(before, style = style, fontFamily = MonoFamily, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(after, style = style, fontFamily = MonoFamily, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(focus, style = style, modifier = Modifier.weight(2.4f).padding(start = 12.dp))
    }
}
