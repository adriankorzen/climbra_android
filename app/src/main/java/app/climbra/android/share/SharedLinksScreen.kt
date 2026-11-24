package app.climbra.android.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.climbra.android.R
import app.climbra.android.data.api.ShareApi
import app.climbra.android.data.api.ShareKind
import app.climbra.android.data.api.ShareLink
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.RiderRepository
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.LocalOffline
import app.climbra.android.ui.common.OfflineNote
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SharedLinksUiState(
    val loading: Boolean = true,
    val links: List<ShareLink> = emptyList(),
    val error: UiText? = null,
    /** Asked to revoke, waiting for the confirmation. */
    val confirming: ShareLink? = null,
    val revoking: Boolean = false,
    /** Revoked: the screen says so once. */
    val revoked: Boolean = false,
)

/** The rider's links that still work (spec 028, cyclingapp spec 062), with revoking. */
@HiltViewModel
class SharedLinksViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: ShareApi,
) : ViewModel() {

    private val mutableState = MutableStateFlow(SharedLinksUiState())
    val state: StateFlow<SharedLinksUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    /** Each time the screen is shown: a link created meanwhile appears. */
    fun load() {
        if (loadJob?.isActive == true) return
        mutableState.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val riderId = riderId() ?: return@launch
            val result = apiCall(Network.json) { api.active(riderId) }
            mutableState.update {
                when (result) {
                    is ApiResult.Success -> it.copy(loading = false, links = result.value)
                    is ApiResult.Failure -> it.copy(loading = false, error = result.error.toUiText())
                }
            }
        }
    }

    fun askRevoke(link: ShareLink) = mutableState.update { it.copy(confirming = link) }

    fun dismiss() = mutableState.update { it.copy(confirming = null) }

    fun confirmRevoke() {
        val link = state.value.confirming ?: return
        mutableState.update { it.copy(confirming = null, revoking = true, error = null) }
        viewModelScope.launch {
            val riderId = riderId() ?: return@launch
            when (val result = apiCall(Network.json) { api.revoke(riderId, link.id) }) {
                is ApiResult.Success -> {
                    mutableState.update { it.copy(revoking = false, revoked = true, links = it.links.filterNot { other -> other.id == link.id }) }
                    load()
                }
                is ApiResult.Failure -> mutableState.update { it.copy(revoking = false, error = result.error.toUiText()) }
            }
        }
    }

    fun revokedShown() = mutableState.update { it.copy(revoked = false) }

    private suspend fun riderId(): Long? = when (val result = riders.load()) {
        is ApiResult.Success -> result.value?.id ?: run {
            mutableState.update { it.copy(loading = false, revoking = false) }
            null
        }
        is ApiResult.Failure -> {
            mutableState.update { it.copy(loading = false, revoking = false, error = result.error.toUiText()) }
            null
        }
    }
}

@Composable
fun SharedLinksRoute(onBack: () -> Unit, viewModel: SharedLinksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }
    SharedLinksScreen(
        state = state,
        fmt = rememberFmt(),
        onBack = onBack,
        onRetry = viewModel::load,
        onRevoke = viewModel::askRevoke,
        onConfirm = viewModel::confirmRevoke,
        onDismiss = viewModel::dismiss,
        onRevokedShown = viewModel::revokedShown,
    )
}

/** What a link shows, as the list and the confirmation name it. */
@Composable
fun describe(link: ShareLink, fmt: Fmt): String = if (link.kind == ShareKind.WEEK && link.weekStart != null) {
    val monday = LocalDate.parse(link.weekStart)
    stringResource(
        R.string.shared_links_week,
        fmt.date(monday.toString(), DateStyle.DAY_MONTH),
        fmt.date(monday.plusDays(6).toString(), DateStyle.DAY_MONTH),
    )
} else {
    val name = link.activityName ?: stringResource(R.string.activity_unnamed)
    link.activityDate?.let { "$name · ${fmt.date(it, DateStyle.DAY_MONTH_YEAR)}" } ?: name
}

/** Profile → Shared links (spec 028; web: `shared-links-section`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLinksScreen(
    state: SharedLinksUiState,
    fmt: Fmt,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRevoke: (ShareLink) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRevokedShown: () -> Unit = {},
) {
    val offline = LocalOffline.current
    val snackbar = remember { SnackbarHostState() }
    val revokedText = stringResource(R.string.shared_links_revoked)
    LaunchedEffect(state.revoked) {
        if (state.revoked) {
            snackbar.showSnackbar(revokedText)
            onRevokedShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shared_links_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("shared-links-back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("shared-links"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                if (state.loading || state.revoking) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("shared-links-progress"))
                Text(
                    stringResource(R.string.shared_links_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                if (offline) OfflineNote(Modifier.padding(horizontal = 16.dp))
                state.error?.let {
                    ErrorBanner(it.resolve(), Modifier.padding(horizontal = 16.dp).testTag("shared-links-error"))
                    OutlinedButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.retry)) }
                }
                if (!state.loading && state.error == null && state.links.isEmpty()) {
                    Text(
                        stringResource(R.string.shared_links_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp).testTag("shared-links-empty"),
                    )
                }
            }
            items(state.links, key = { it.id }) { link ->
                val details = buildList {
                    add(stringResource(R.string.shared_links_expires, fmt.instant(link.expiresAt, withTime = false)))
                    if (link.hidePower) add(stringResource(R.string.shared_links_power_hidden))
                    if (link.hideHeartRate) add(stringResource(R.string.shared_links_hr_hidden))
                }.joinToString(" · ")
                ListItem(
                    headlineContent = { Text(describe(link, fmt)) },
                    supportingContent = { Text(details) },
                    leadingContent = {
                        Icon(painterResource(if (link.kind == ShareKind.WEEK) R.drawable.ic_calendar_month else R.drawable.ic_directions_bike), contentDescription = null)
                    },
                    trailingContent = {
                        TextButton(
                            onClick = { onRevoke(link) },
                            enabled = !offline && !state.revoking,
                            modifier = Modifier.testTag("revoke-${link.id}"),
                        ) { Text(stringResource(R.string.shared_links_revoke)) }
                    },
                    modifier = Modifier.testTag("shared-link-${link.id}"),
                )
                HorizontalDivider()
            }
        }
    }
    state.confirming?.let { link ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.shared_links_revoke_title)) },
            text = { Text(stringResource(R.string.shared_links_revoke_confirm, describe(link, fmt))) },
            confirmButton = {
                TextButton(onClick = onConfirm, enabled = !offline, modifier = Modifier.testTag("confirm-revoke")) {
                    Text(stringResource(R.string.shared_links_revoke), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
