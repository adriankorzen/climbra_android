package app.climbra.android.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.auth.LoginRoute
import app.climbra.android.auth.TermsRoute
import app.climbra.android.dashboard.DashboardRoute
import app.climbra.android.data.api.AccountResponse
import app.climbra.android.profile.ProfileRoute
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.shell.PlaceholderScreen
import app.climbra.android.shell.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    private val session: SessionRepository,
    val config: AppConfig,
) : ViewModel() {

    val state: StateFlow<SessionState> = session.state

    init {
        retry()
    }

    fun retry() {
        viewModelScope.launch { session.start() }
    }
}

@Composable
fun ClimbraApp(viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ClimbraContent(
        state = state,
        onRetry = viewModel::retry,
        login = { expired -> LoginRoute(expired = expired, config = viewModel.config) },
        terms = { changed -> TermsRoute(documentsChanged = changed, config = viewModel.config) },
        shell = { account ->
            Shell(
                dashboard = { DashboardRoute(config = viewModel.config) },
                activities = { PlaceholderScreen(R.string.nav_activities) },
                profile = { ProfileRoute(email = account.email) },
            )
        },
    )
}

/**
 * The session decides the top-level screen (spec 001), so logging out or an expired session replaces everything
 * shown before: nothing of the shell stays on a back stack.
 */
@Composable
fun ClimbraContent(
    state: SessionState,
    onRetry: () -> Unit,
    login: @Composable (expired: Boolean) -> Unit,
    terms: @Composable (documentsChanged: Boolean) -> Unit,
    shell: @Composable (account: AccountResponse) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        when (state) {
            // The system splash screen stays up meanwhile (MainActivity)
            SessionState.Loading -> Box(Modifier.fillMaxSize().testTag("start-loading"))
            is SessionState.Unavailable -> StartUnavailable(onRetry)
            is SessionState.LoggedOut -> login(state.expired)
            is SessionState.NeedsTerms -> terms(state.account.legalDocumentsChanged)
            is SessionState.LoggedIn -> shell(state.account)
        }
    }
}

@Composable
private fun StartUnavailable(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("start-unavailable"),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_cloud_off),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(stringResource(R.string.start_unavailable_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.start_unavailable_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.testTag("start-retry")) { Text(stringResource(R.string.retry)) }
    }
}
