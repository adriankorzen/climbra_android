package app.climbra.android.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.data.api.CoachConversationSummary
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.extendedColors
import java.time.Instant
import kotlinx.coroutines.delay

/** Where the coach's screens lead; the shell's navigation (spec 006). */
data class CoachNavigation(
    val openConversation: (Long) -> Unit = {},
    val newTrainingBlock: () -> Unit = {},
    val reviewFile: () -> Unit = {},
    /** "Review a ride": the Activities tab. */
    val openActivities: () -> Unit = {},
)

@Composable
fun CoachListRoute(config: AppConfig, navigation: CoachNavigation, viewModel: CoachListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CoachListScreen(
        state = state,
        fmt = rememberFmt(),
        now = rememberNow(RELATIVE_TICK_MILLIS),
        navigation = navigation,
        actions = CoachListActions(
            onRefresh = viewModel::refresh,
            onFilter = viewModel::selectFilter,
            onAskDelete = viewModel::askDelete,
            onCancelDelete = viewModel::cancelDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onMessageShown = viewModel::messageShown,
            onOpenWeb = { openWebPage(context, config.webPage("")) },
        ),
    )
}

data class CoachListActions(
    val onRefresh: () -> Unit = {},
    val onFilter: (ConversationFilter) -> Unit = {},
    val onAskDelete: (CoachConversationSummary) -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
    val onOpenWeb: () -> Unit = {},
)

/** The current time, moving on every [tickMillis] while shown ("3 min ago" counts on). */
@Composable
fun rememberNow(tickMillis: Long): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(tickMillis)
            value = Instant.now()
        }
    }
    return now
}

private const val RELATIVE_TICK_MILLIS = 30_000L

/** The web's `coach-page` list on a phone: conversations, filters, credits and the ways to start (spec 006). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachListScreen(
    state: CoachListUiState,
    fmt: Fmt,
    now: Instant,
    navigation: CoachNavigation,
    actions: CoachListActions,
) {
    val snackbar = remember { SnackbarHostState() }
    val message = when (val m = state.message) {
        CoachListMessage.Deleted -> stringResource(R.string.coach_deleted)
        is CoachListMessage.Failed -> m.error.toUiText().resolve()
        null -> null
    }
    LaunchedEffect(state.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onMessageShown()
        }
    }
    var newMenu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.nav_coach), modifier = Modifier.semantics { heading() })
                        CreditsSummary(state.quota, fmt)
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.noRider) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { newMenu = true },
                        icon = { Icon(painterResource(R.drawable.ic_add), null) },
                        text = { Text(stringResource(R.string.coach_new)) },
                        modifier = Modifier.testTag("coach-new"),
                    )
                    DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conversation_type_training_block)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_calendar_month), null) },
                            onClick = {
                                newMenu = false
                                navigation.newTrainingBlock()
                            },
                            modifier = Modifier.testTag("coach-new-block"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.coach_review_file)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_upload_file), null) },
                            onClick = {
                                newMenu = false
                                navigation.reviewFile()
                            },
                            modifier = Modifier.testTag("coach-new-file"),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize().testTag("coach-list"),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                if (state.loading || state.busy) {
                    item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("coach-loading")) }
                }
                state.error?.let { error ->
                    item(key = "error") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ErrorBanner("${stringResource(R.string.coach_load_failed)} ${error.toUiText().resolve()}", Modifier.testTag("coach-error"))
                            OutlinedButton(onClick = actions.onRefresh, modifier = Modifier.testTag("coach-retry")) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
                if (state.noRider) {
                    item(key = "no-rider") { NoRiderHint(actions.onOpenWeb) }
                    return@LazyColumn
                }
                if (state.empty) {
                    item(key = "welcome") { Welcome(navigation) }
                    return@LazyColumn
                }
                if (state.conversations.isNotEmpty()) {
                    item(key = "filters") { Filters(state.filter, actions.onFilter) }
                }
                if (state.shown.isEmpty() && state.conversations.isNotEmpty()) {
                    item(key = "none") {
                        Text(
                            stringResource(R.string.coach_no_conversations),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp).testTag("coach-none"),
                        )
                    }
                }
                items(state.shown, key = { it.id }) { conversation ->
                    ConversationRow(conversation, fmt, now, navigation.openConversation, actions.onAskDelete)
                    HorizontalDivider()
                }
            }
        }
    }
    state.deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = actions.onCancelDelete,
            text = { Text(stringResource(R.string.coach_confirm_delete, conversation.title)) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmDelete, modifier = Modifier.testTag("confirm-delete")) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = actions.onCancelDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Filters(selected: ConversationFilter, onFilter: (ConversationFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConversationFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onFilter(filter) },
                label = { Text(filter.type?.let { conversationTypeName(it) } ?: stringResource(R.string.coach_filter_all)) },
                modifier = Modifier.testTag("filter-${filter.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: CoachConversationSummary,
    fmt: Fmt,
    now: Instant,
    onOpen: (Long) -> Unit,
    onDelete: (CoachConversationSummary) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                ConversationTypeBadge(conversation.type)
                when (conversation.status) {
                    CoachConversationSummary.STATUS_BUSY -> StatusText(stringResource(R.string.coach_status_busy), MaterialTheme.colorScheme.primary, "status-busy-${conversation.id}")
                    CoachConversationSummary.STATUS_FAILED -> StatusText(stringResource(R.string.coach_status_failed), MaterialTheme.colorScheme.error, "status-failed-${conversation.id}")
                }
                if (conversation.activityId != null) {
                    Icon(
                        painterResource(R.drawable.ic_directions_bike),
                        stringResource(R.string.coach_linked_ride),
                        Modifier.size(16.dp).testTag("ride-icon-${conversation.id}"),
                        tint = MaterialTheme.extendedColors.textFaint,
                    )
                }
                if (conversation.planId != null) {
                    Icon(
                        painterResource(R.drawable.ic_calendar_month),
                        stringResource(R.string.coach_linked_plan),
                        Modifier.size(16.dp).testTag("plan-icon-${conversation.id}"),
                        tint = MaterialTheme.extendedColors.textFaint,
                    )
                }
                Text(
                    fmt.relative(conversation.lastMessageAt ?: conversation.createdAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.extendedColors.textFaint,
                    modifier = Modifier.testTag("time-${conversation.id}"),
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu-${conversation.id}")) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            onDelete(conversation)
                        },
                        modifier = Modifier.testTag("delete-${conversation.id}"),
                    )
                }
            }
        },
        modifier = Modifier.clickable { onOpen(conversation.id) }.testTag("conversation-${conversation.id}"),
    )
}

@Composable
private fun StatusText(text: String, color: androidx.compose.ui.graphics.Color, tag: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(tag))
}

/** The web's welcome with the three ways to start. */
@Composable
private fun Welcome(navigation: CoachNavigation) {
    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp).testTag("coach-welcome")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.coach_welcome_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.coach_welcome), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WelcomeAction(R.drawable.ic_calendar_month, R.string.coach_new_block, R.string.coach_new_block_hint, "welcome-block", navigation.newTrainingBlock)
            WelcomeAction(R.drawable.ic_upload_file, R.string.coach_review_file, R.string.coach_review_file_hint, "welcome-file", navigation.reviewFile)
            WelcomeAction(R.drawable.ic_directions_bike, R.string.coach_review_ride, R.string.coach_review_ride_hint, "welcome-ride", navigation.openActivities)
        }
    }
}

@Composable
private fun WelcomeAction(icon: Int, title: Int, hint: Int, tag: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
            Icon(painterResource(icon), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(title))
        }
        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint)
    }
}

/** As the other tabs for an account without a rider. */
@Composable
fun NoRiderHint(onOpenWeb: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp).testTag("coach-no-rider")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dashboard_no_rider_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dashboard_no_rider_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Button(onClick = onOpenWeb) { Text(stringResource(R.string.dashboard_open_web)) }
        }
    }
}
