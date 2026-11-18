package app.climbra.android.coach

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.calendar.OpenOnWeb
import app.climbra.android.data.api.CoachConversation
import app.climbra.android.data.api.CoachMessage
import app.climbra.android.data.api.ConversationType
import app.climbra.android.data.api.PendingCoachRequest
import app.climbra.android.data.api.PlannedWorkout
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.parseMarkdown
import java.time.Instant
import java.time.format.DateTimeParseException

@Composable
fun ConversationRoute(
    config: AppConfig,
    onBack: () -> Unit,
    onOpenRide: (Long) -> Unit,
    /** The calendar at the week of an ISO date, or where it was without one. */
    onOpenCalendar: (String?) -> Unit,
    onDeleted: () -> Unit,
    onAdjust: (AdjustmentArgs) -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleStartEffect(viewModel) {
        viewModel.setShown(true)
        onStopOrDispose { viewModel.setShown(false) }
    }
    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }
    val files = remember(context) { ContentResolverFileSource(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(PickedFile(uri.toString(), files.name(uri.toString()) ?: uri.lastPathSegment.orEmpty()))
    }
    ConversationScreen(
        state = state,
        fmt = rememberFmt(),
        now = rememberNow(TIME_TICK_MILLIS),
        actions = ConversationActions(
            onBack = onBack,
            onRetryLoad = viewModel::retryLoad,
            onDraftChange = viewModel::onDraftChange,
            onAttach = { picker.launch(RIDE_FILE_TYPES) },
            onRemoveFile = viewModel::removeFile,
            onSend = viewModel::send,
            onSendAgain = viewModel::sendAgain,
            onEdit = viewModel::edit,
            onRetry = viewModel::retry,
            onAskDelete = viewModel::askDelete,
            onCancelDelete = viewModel::cancelDelete,
            onDelete = viewModel::delete,
            onTab = viewModel::selectTab,
            onSavePlan = { viewModel.savePlan(false) },
            onReplacePlan = { viewModel.savePlan(true) },
            onCancelOverlap = viewModel::cancelOverlap,
            onOpenRide = onOpenRide,
            onOpenCalendar = onOpenCalendar,
            onOpenWeb = { path -> openWebPage(context, config.webPage(path)) },
            onOpenLink = { url -> openWebPage(context, url) },
            onMessageShown = viewModel::messageShown,
            onAccept = viewModel::acceptProposal,
            onAskReject = viewModel::askReject,
            onCancelReject = viewModel::cancelReject,
            onReject = viewModel::rejectProposal,
            onRetryProposal = { viewModel.retryProposal() },
            onReplaceInPlan = viewModel::replaceInPlan,
            onAdjust = onAdjust,
        ),
    )
}

private const val TIME_TICK_MILLIS = 60_000L

data class ConversationActions(
    val onBack: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val onAttach: () -> Unit = {},
    val onRemoveFile: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onSendAgain: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAskDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onTab: (ConversationTab) -> Unit = {},
    val onSavePlan: () -> Unit = {},
    val onReplacePlan: () -> Unit = {},
    val onCancelOverlap: () -> Unit = {},
    val onOpenRide: (Long) -> Unit = {},
    val onOpenCalendar: (String?) -> Unit = {},
    /** A page of the web app, e.g. `coach/12` or `terms`. */
    val onOpenWeb: (String) -> Unit = {},
    /** A link in an answer. */
    val onOpenLink: (String) -> Unit = {},
    val onMessageShown: () -> Unit = {},
    // Spec 008
    val onAccept: () -> Unit = {},
    val onAskReject: () -> Unit = {},
    val onCancelReject: () -> Unit = {},
    val onReject: () -> Unit = {},
    val onRetryProposal: () -> Unit = {},
    val onReplaceInPlan: () -> Unit = {},
    val onAdjust: (AdjustmentArgs) -> Unit = {},
)

/** The web's `conversation-view` (spec 006): the chat, and the latest review or training block next to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(state: ConversationUiState, fmt: Fmt, now: Instant, actions: ConversationActions) {
    val snackbar = remember { SnackbarHostState() }
    val message = when (val m = state.message) {
        ConversationMessage.PlanAdded -> stringResource(R.string.calendar_added)
        ConversationMessage.PlanUpdated -> stringResource(R.string.calendar_updated)
        ConversationMessage.ProposalAccepted -> stringResource(R.string.proposal_accepted)
        ConversationMessage.ProposalRejected -> stringResource(R.string.proposal_rejected)
        ConversationMessage.WorkoutReplaced -> stringResource(R.string.review_replaced)
        is ConversationMessage.Failed -> m.error.toUiText().resolve()
        null -> null
    }
    LaunchedEffect(state.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onMessageShown()
        }
    }
    var workout by remember { mutableStateOf<PlannedWorkout?>(null) }
    val conversation = state.conversation
    val uriHandler = remember(actions) {
        object : UriHandler {
            override fun openUri(uri: String) = actions.onOpenLink(uri)
        }
    }
    Scaffold(
        topBar = { ConversationTopBar(conversation, actions) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.loading || state.submitting || state.savingPlan) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("conversation-progress"))
            }
            when {
                state.notFound -> ErrorBanner(stringResource(R.string.coach_not_found), Modifier.padding(16.dp).testTag("conversation-not-found"))
                state.error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ErrorBanner("${stringResource(R.string.coach_conversation_failed)} ${state.error.toUiText().resolve()}", Modifier.testTag("conversation-error"))
                    OutlinedButton(onClick = actions.onRetryLoad) { Text(stringResource(R.string.retry)) }
                }
                conversation != null -> CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    Header(conversation, fmt, actions)
                    if (state.hasPanel) Tabs(state, conversation, actions.onTab)
                    if (state.hasPanel && state.tab == ConversationTab.PANEL) {
                        Panel(state, conversation, fmt, actions) { workout = it }
                    } else {
                        Chat(state, conversation, fmt, now, actions, Modifier.weight(1f))
                        if (conversation.readOnly) ReadOnlyNote() else Composer(state, fmt, actions)
                    }
                }
            }
        }
    }
    workout?.let { WorkoutDetailSheet(it, fmt) { workout = null } }
    state.overlap?.let { name ->
        OverlapDialog(name, conversation?.latestTrainingBlock?.startDate, state.savingPlan, fmt, actions.onReplacePlan, actions.onCancelOverlap)
    }
    if (state.confirmingReject) {
        AlertDialog(
            onDismissRequest = actions.onCancelReject,
            text = { Text(stringResource(R.string.proposal_confirm_reject)) },
            confirmButton = {
                TextButton(onClick = actions.onReject, modifier = Modifier.testTag("confirm-reject")) {
                    Text(stringResource(R.string.proposal_reject))
                }
            },
            dismissButton = { TextButton(onClick = actions.onCancelReject) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (state.confirmingDelete && conversation != null) {
        AlertDialog(
            onDismissRequest = actions.onCancelDelete,
            text = { Text(stringResource(R.string.coach_confirm_delete, conversation.title)) },
            confirmButton = {
                TextButton(onClick = actions.onDelete, modifier = Modifier.testTag("confirm-delete")) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = actions.onCancelDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The day a plan of the conversation starts, for the calendar's week. */
private fun planDate(conversation: CoachConversation): String? =
    conversation.latestTrainingBlock?.startDate ?: conversation.reviewPlanChange?.workoutDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationTopBar(conversation: CoachConversation?, actions: ConversationActions) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(conversation?.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = actions.onBack, modifier = Modifier.testTag("conversation-back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            }
        },
        actions = {
            if (conversation != null) {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("conversation-menu")) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    conversation.activityId?.let { activityId ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.coach_open_ride)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_directions_bike), null) },
                            onClick = {
                                menu = false
                                actions.onOpenRide(activityId)
                            },
                            modifier = Modifier.testTag("action-open-ride"),
                        )
                    }
                    if (conversation.plan != null || conversation.planId != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.coach_open_plan)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_calendar_month), null) },
                            onClick = {
                                menu = false
                                actions.onOpenCalendar(planDate(conversation))
                            },
                            modifier = Modifier.testTag("action-open-plan"),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            actions.onAskDelete()
                        },
                        modifier = Modifier.testTag("action-delete"),
                    )
                }
            }
        },
    )
}

@Composable
private fun Header(conversation: CoachConversation, fmt: Fmt, actions: ConversationActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConversationTypeBadge(conversation.type, Modifier.testTag("conversation-type"))
            Text(fmt.instant(conversation.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.extendedColors.textFaint)
        }
    }
}

@Composable
private fun Tabs(state: ConversationUiState, conversation: CoachConversation, onTab: (ConversationTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
        Tab(
            selected = state.tab == ConversationTab.CHAT,
            onClick = { onTab(ConversationTab.CHAT) },
            text = { Text(stringResource(R.string.coach_chat)) },
            modifier = Modifier.testTag("tab-chat"),
        )
        Tab(
            selected = state.tab == ConversationTab.PANEL,
            onClick = { onTab(ConversationTab.PANEL) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(
                            when {
                                state.hasProposal -> R.string.proposal_tab
                                conversation.latestTrainingBlock != null -> R.string.coach_training_block
                                else -> R.string.coach_review
                            },
                        ),
                    )
                    if (state.updated) {
                        Pill(stringResource(R.string.coach_updated), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, Modifier.testTag("panel-updated"))
                    }
                }
            },
            modifier = Modifier.testTag("tab-panel"),
        )
    }
}

@Composable
private fun Panel(
    state: ConversationUiState,
    conversation: CoachConversation,
    fmt: Fmt,
    actions: ConversationActions,
    onOpenWorkout: (PlannedWorkout) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val block = conversation.latestTrainingBlock
        val review = conversation.latestReview
        when {
            state.hasProposal -> ProposalPanel(
                proposal = state.proposal,
                error = state.proposalError,
                deciding = state.deciding,
                fmt = fmt,
                onAccept = actions.onAccept,
                onReject = actions.onAskReject,
                onRetry = actions.onRetryProposal,
                onOpenWorkout = onOpenWorkout,
            )
            block != null -> {
                TrainingBlockPanel(block, fmt, onOpenWorkout)
                if (conversation.type == ConversationType.TRAINING_BLOCK) {
                    PlanActions(conversation, state.savingPlan, actions.onSavePlan) { actions.onOpenCalendar(block.startDate) }
                }
            }
            review != null -> {
                val change = conversation.reviewPlanChange
                val adjustNote = review.planAssessment?.let { stringResource(R.string.review_adjust_note, conversation.title, it.reason) }
                ReviewPanel(
                    review,
                    change,
                    fmt,
                    onOpenWorkout,
                    ReviewPlanActions(
                        replacing = state.replacing || state.busy,
                        onReplace = actions.onReplaceInPlan,
                        onAdjust = change?.planId?.let { planId ->
                            change.workoutDate?.let { date ->
                                { actions.onAdjust(AdjustmentArgs(planId, date, fromDate = review.planAssessment?.workoutDate ?: date, note = adjustNote)) }
                            }
                        },
                    ),
                )
            }
        }
    }
}

/** One entry of the chat, in reading order. */
private sealed interface ChatItem {
    val key: String

    data class Message(val message: CoachMessage) : ChatItem {
        override val key get() = "message-${message.id}"
    }

    data class Referral(val id: Long) : ChatItem {
        override val key get() = "referral-$id"
    }

    data class BlockCta(val id: Long) : ChatItem {
        override val key get() = "block-cta-$id"
    }

    data class Pending(val request: PendingCoachRequest) : ChatItem {
        override val key get() = "pending"
    }

    data class Failed(val request: PendingCoachRequest) : ChatItem {
        override val key get() = "failed"
    }

    data class Refused(val refused: RefusedRequest) : ChatItem {
        override val key get() = "refused"
    }

    data class Waiting(val request: PendingCoachRequest?) : ChatItem {
        override val key get() = "waiting"
    }
}

private fun chatItems(state: ConversationUiState, conversation: CoachConversation): List<ChatItem> = buildList {
    val blockMessage = state.blockMessageId
    conversation.messages.sortedBy { it.sequence }.forEach { message ->
        add(ChatItem.Message(message))
        if (message.medicalReferral) add(ChatItem.Referral(message.id))
        if (message.id == blockMessage) add(ChatItem.BlockCta(message.id))
    }
    val pending = conversation.pendingRequest
    if (pending != null) {
        if (!pending.text.isNullOrBlank()) add(ChatItem.Pending(pending))
        if (pending.failed) add(ChatItem.Failed(pending))
    }
    state.refused?.let { add(ChatItem.Refused(it)) }
    if (conversation.busy) add(ChatItem.Waiting(pending))
}

@Composable
private fun Chat(state: ConversationUiState, conversation: CoachConversation, fmt: Fmt, now: Instant, actions: ConversationActions, modifier: Modifier) {
    // Reversed: the list starts at its end, the newest entry
    val items = chatItems(state, conversation).asReversed()
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("chat"),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ChatItem.Message -> MessageBubble(item.message, fmt, now)
                is ChatItem.Referral -> Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("medical-referral-${item.id}"),
                ) {
                    Text(stringResource(R.string.coach_medical_referral), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
                is ChatItem.BlockCta -> BlockCta(conversation, actions)
                is ChatItem.Pending -> UserBubble(item.request.text.orEmpty(), item.request.fileNames, null, Modifier.testTag("pending-request"))
                is ChatItem.Failed -> FailedRequest(item.request, state, conversation, actions)
                is ChatItem.Refused -> RefusedMessage(item.refused, state, actions)
                is ChatItem.Waiting -> Waiting(item.request, conversation, state.serverOffsetMillis, fmt)
            }
        }
    }
}

@Composable
private fun BlockCta(conversation: CoachConversation, actions: ConversationActions) {
    val plan = conversation.plan
    val hint = when {
        plan == null -> R.string.coach_block_ready
        plan.outdated -> R.string.coach_block_revised
        else -> R.string.coach_block_in_calendar
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("block-cta")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("block-cta-hint"))
            OutlinedButton(onClick = { actions.onTab(ConversationTab.PANEL) }, modifier = Modifier.testTag("show-block")) {
                Icon(painterResource(R.drawable.ic_calendar_month), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.coach_show_training_block))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: CoachMessage, fmt: Fmt, now: Instant) {
    val time = fmt.timeOrDate(message.createdAt, now)
    if (message.role == CoachMessage.ROLE_USER) {
        UserBubble(message.text.orEmpty(), message.files, time, Modifier.testTag("message-${message.id}"))
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            modifier = Modifier.widthIn(max = 640.dp).testTag("message-${message.id}"),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val parsed = remember(message.text) { parseMarkdown(message.text.orEmpty()) }
                Markdown(parsed, modifier = Modifier.testTag("markdown-${message.id}"))
                Files(message.files)
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, files: List<String>, time: String?, modifier: Modifier = Modifier, error: Boolean = false) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = modifier.widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium)
                Files(files)
                time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint) }
            }
        }
    }
}

@Composable
private fun Files(files: List<String>) {
    if (files.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { name ->
            AssistChip(
                onClick = {},
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_attach_file), null, Modifier.size(16.dp)) },
                modifier = Modifier.testTag("file-$name"),
            )
        }
    }
}

@Composable
private fun FailedRequest(request: PendingCoachRequest, state: ConversationUiState, conversation: CoachConversation, actions: ConversationActions) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("request-failed").semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(requestErrorText(request.errorCode)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!conversation.readOnly) {
                    OutlinedButton(onClick = actions.onRetry, enabled = state.canRetry, modifier = Modifier.testTag("request-retry")) {
                        Icon(painterResource(R.drawable.ic_refresh), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.coach_retry))
                    }
                }
                if (request.kind == PendingCoachRequest.KIND_MESSAGE) {
                    TextButton(onClick = actions.onEdit, modifier = Modifier.testTag("request-edit")) { Text(stringResource(R.string.coach_edit_again)) }
                } else {
                    TextButton(onClick = actions.onDelete, enabled = !state.submitting, modifier = Modifier.testTag("request-delete")) {
                        Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.delete))
                    }
                }
            }
            if (!conversation.readOnly) CoachCostLine(state.limits, state.retryKind, rememberFmt())
        }
    }
}

@Composable
private fun RefusedMessage(refused: RefusedRequest, state: ConversationUiState, actions: ConversationActions) {
    Column(Modifier.fillMaxWidth().testTag("refused"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (refused.text.isNotBlank()) {
            UserBubble(refused.text, listOfNotNull(refused.file?.name), null, Modifier.testTag("refused-text"), error = true)
        }
        CoachRequestError(refused.error, Modifier.testTag("refused-error"))
        if (refused.text.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onSendAgain, enabled = !state.locked, modifier = Modifier.testTag("send-again")) {
                    Icon(painterResource(R.drawable.ic_refresh), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.coach_send_again))
                }
                TextButton(onClick = actions.onEdit, modifier = Modifier.testTag("refused-edit")) { Text(stringResource(R.string.edit)) }
            }
        }
    }
}

/** The coach's bubble while it answers: queued or thinking, with the time since the request started. */
@Composable
private fun Waiting(request: PendingCoachRequest?, conversation: CoachConversation, serverOffsetMillis: Long, fmt: Fmt) {
    val now = rememberNow(1_000L)
    val started = listOfNotNull(request?.startedAt, request?.createdAt, conversation.updatedAt).firstNotNullOfOrNull {
        try {
            Instant.parse(it)
        } catch (e: DateTimeParseException) {
            null
        }
    }
    val seconds = started?.let { ((now.toEpochMilli() + serverOffsetMillis - it.toEpochMilli()) / 1000).coerceAtLeast(0) }
    Column(Modifier.fillMaxWidth().testTag("waiting"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)) {
            Row(
                Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TypingDots()
                Text(
                    stringResource(if (request?.status == PendingCoachRequest.STATUS_QUEUED) R.string.coach_queued else R.string.coach_thinking),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("waiting-text"),
                )
                seconds?.let {
                    Text(fmt.duration(it), style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, color = MaterialTheme.extendedColors.textFaint, modifier = Modifier.testTag("elapsed"))
                }
            }
        }
        Text(stringResource(R.string.coach_background_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint)
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 200), RepeatMode.Reverse),
                label = "dot$index",
            )
            Box(Modifier.size(6.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

@Composable
private fun ReadOnlyNote() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().testTag("read-only")) {
        Text(stringResource(R.string.coach_read_only), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Composer(state: ConversationUiState, fmt: Fmt, actions: ConversationActions) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("composer")) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.file?.let { file ->
                InputChip(
                    selected = false,
                    onClick = actions.onRemoveFile,
                    label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_attach_file), null, Modifier.size(16.dp)) },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.coach_remove_file), Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("chosen-file"),
                )
            }
            if (state.fileError) {
                Text(stringResource(R.string.coach_file_type), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("file-error"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onAttach, enabled = state.inputEnabled, modifier = Modifier.testTag("attach")) {
                    Icon(painterResource(R.drawable.ic_attach_file), stringResource(R.string.coach_attach))
                }
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = actions.onDraftChange,
                    enabled = state.inputEnabled,
                    placeholder = { Text(stringResource(R.string.coach_message_placeholder)) },
                    maxLines = 5,
                    modifier = Modifier.weight(1f).testTag("draft"),
                )
                IconButton(onClick = actions.onSend, enabled = state.canSend, modifier = Modifier.testTag("send")) {
                    Icon(painterResource(R.drawable.ic_send), stringResource(R.string.coach_send), tint = if (state.canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            CoachCostLine(state.limits, state.messageKind, fmt, Modifier.padding(horizontal = 8.dp))
            HealthNote(onOpenTerms = { actions.onOpenWeb("terms") }, Modifier.padding(horizontal = 8.dp))
        }
    }
}
