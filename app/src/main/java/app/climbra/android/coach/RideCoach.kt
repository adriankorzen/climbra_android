package app.climbra.android.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.activities.workoutTypeName
import app.climbra.android.ui.format.Fmt

/** What the coach parts of a ride detail can ask for. */
data class RideCoachActions(
    val onAsk: () -> Unit = {},
    val onCloseSheet: () -> Unit = {},
    val onChange: ((AskCoachState) -> AskCoachState) -> Unit = {},
    val onStart: () -> Unit = {},
    val onOpenConversation: (Long) -> Unit = {},
    val onOpenTerms: () -> Unit = {},
)

/** "Continue review" when the ride's review can go on, otherwise "Ask the coach" (under the ride's header). */
@Composable
fun RideCoachButton(state: AskCoachState, actions: RideCoachActions) {
    val conversation = state.conversationId
    FilledTonalButton(
        onClick = { if (state.canContinue && conversation != null) actions.onOpenConversation(conversation) else actions.onAsk() },
        modifier = Modifier.testTag(if (state.canContinue) "continue-review" else "ask-coach"),
    ) {
        Icon(painterResource(R.drawable.ic_auto_awesome), null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(if (state.canContinue) R.string.coach_continue_review else R.string.coach_ask))
    }
}

/** The coach card at the end of a ride detail: the latest review's summary, session type and RPE. */
@Composable
fun RideCoachCard(state: AskCoachState, fmt: Fmt, actions: RideCoachActions) {
    ElevatedCard(Modifier.fillMaxWidth().testTag("ride-coach-card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nav_coach), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                state.conversationId?.let { id ->
                    TextButton(onClick = { actions.onOpenConversation(id) }, modifier = Modifier.testTag("open-review")) {
                        Text(stringResource(R.string.coach_open_conversation))
                    }
                }
            }
            val review = state.review
            if (review == null) {
                Text(stringResource(R.string.coach_no_review), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("no-review"))
                if (state.conversationId == null) {
                    TextButton(onClick = actions.onAsk, modifier = Modifier.testTag("card-ask-coach")) { Text(stringResource(R.string.coach_ask)) }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Pill(workoutTypeName(review.sessionType), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        stringResource(R.string.coach_rpe_pair, fmt.num(review.estimatedRpe), fmt.num(review.riderRpe)),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.testTag("card-rpe"),
                    )
                }
                Text(review.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("card-summary"))
            }
        }
    }
}

/** The web's `ask-coach` dialog as a bottom sheet: RPE, a note, the language, the cost and "Start review". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskCoachSheet(state: AskCoachState, free: Boolean, fmt: Fmt, actions: RideCoachActions) {
    ModalBottomSheet(onDismissRequest = actions.onCloseSheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AskCoachContent(state, free, fmt, actions)
    }
}

@Composable
fun AskCoachContent(state: AskCoachState, free: Boolean, fmt: Fmt, actions: RideCoachActions) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("ask-coach-sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.ask_coach_title), style = MaterialTheme.typography.titleLarge)
        if (state.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        RpeScale(state.rpe, { rpe -> actions.onChange { it.copy(rpe = rpe) } }, enabled = !state.sending)
        OutlinedTextField(
            value = state.note,
            onValueChange = { text -> actions.onChange { it.copy(note = text) } },
            label = { Text(stringResource(R.string.ask_coach_note)) },
            placeholder = { Text(stringResource(R.string.ask_coach_note_placeholder)) },
            minLines = 2,
            enabled = !state.sending,
            modifier = Modifier.fillMaxWidth().testTag("ask-note"),
        )
        LanguageChoice(state.language) { language -> actions.onChange { it.copy(language = language) } }
        state.error?.let { CoachRequestError(it, Modifier.testTag("ask-error")) }
        HealthNote(actions.onOpenTerms)
        CoachCostLine(state.limits, CostKind.REVIEW, fmt, free = free)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = actions.onCloseSheet) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = actions.onStart,
                enabled = !state.sending && state.limits.allows(CostKind.REVIEW, free),
                modifier = Modifier.testTag("ask-start"),
            ) { Text(stringResource(R.string.ask_coach_start)) }
        }
    }
}
