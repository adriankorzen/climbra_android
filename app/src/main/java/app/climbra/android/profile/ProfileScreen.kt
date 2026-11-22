package app.climbra.android.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.push.notificationsEnabled
import app.climbra.android.push.openAppNotificationSettings
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.data.api.IntervalsThresholds
import app.climbra.android.data.api.Rider
import app.climbra.android.data.api.ThresholdEntry
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.ThemeMode
import java.time.LocalDate

/** What the profile can do; every action has a no-op default, so tests pass only what they check. */
interface ProfileActions {
    fun refresh() {}
    fun retry(section: ProfileSection) {}
    fun riderChange(form: RiderForm) {}
    fun saveRider() {}
    fun openThresholdForm(entry: ThresholdEntry?) {}
    fun thresholdChange(form: ThresholdForm) {}
    fun closeThresholdForm() {}
    fun saveThreshold() {}
    fun askDeleteThreshold(entry: ThresholdEntry) {}
    fun confirm() {}
    fun dismissConfirmation() {}
    fun intervalsChange(form: IntervalsForm) {}
    fun connectIntervals() {}
    fun askDisconnectIntervals() {}
    fun syncIntervals() {}
    fun proposeThresholds(proposal: IntervalsThresholds) {}
    fun askDisconnectGarmin() {}
    fun connectGarmin() {}
    fun setLanguage(language: String) {}
    fun setTheme(mode: ThemeMode) {}
    fun passwordChange(form: PasswordForm) {}
    fun changePassword() {}
    fun forgotPassword() {}
    fun openTerms() {}
    fun openPrivacy() {}
    fun openDeleteAccount() {}
    fun deleteAccountChange(value: String) {}
    fun closeDeleteAccount() {}
    fun deleteAccount() {}
    fun logout() {}
    fun messageShown() {}
    fun openFeedback() {}
    fun syncGarmin() {}
    fun openNotificationSettings() {}
}

@Composable
fun ProfileRoute(
    config: AppConfig,
    onOpenFeedback: () -> Unit = {},
    onForgotPassword: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Read again when the rider comes back from the system's settings (spec 020)
    var notificationsOn by remember { mutableStateOf(notificationsEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResumed()
        notificationsOn = notificationsEnabled(context)
    }
    val actions = remember(viewModel, config, context, onOpenFeedback, onForgotPassword) {
        object : ProfileActions {
            override fun refresh() = viewModel.refresh()
            override fun retry(section: ProfileSection) = viewModel.retry(section)
            override fun riderChange(form: RiderForm) = viewModel.onRiderChange(form)
            override fun saveRider() = viewModel.saveRider()
            override fun openThresholdForm(entry: ThresholdEntry?) = viewModel.openThresholdForm(entry)
            override fun thresholdChange(form: ThresholdForm) = viewModel.onThresholdChange(form)
            override fun closeThresholdForm() = viewModel.closeThresholdForm()
            override fun saveThreshold() = viewModel.saveThreshold()
            override fun askDeleteThreshold(entry: ThresholdEntry) = viewModel.askDeleteThreshold(entry)
            override fun confirm() = viewModel.confirm()
            override fun dismissConfirmation() = viewModel.dismissConfirmation()
            override fun intervalsChange(form: IntervalsForm) = viewModel.onIntervalsChange(form)
            override fun connectIntervals() = viewModel.connectIntervals()
            override fun askDisconnectIntervals() = viewModel.askDisconnectIntervals()
            override fun syncIntervals() = viewModel.syncIntervals()
            override fun proposeThresholds(proposal: IntervalsThresholds) = viewModel.proposeThresholds(proposal)
            override fun askDisconnectGarmin() = viewModel.askDisconnectGarmin()
            override fun connectGarmin() = viewModel.connectGarmin()
            override fun setLanguage(language: String) = viewModel.setLanguage(language)
            override fun setTheme(mode: ThemeMode) = viewModel.setTheme(mode)
            override fun passwordChange(form: PasswordForm) = viewModel.onPasswordChange(form)
            override fun changePassword() = viewModel.changePassword()
            override fun forgotPassword() = onForgotPassword()
            override fun openTerms() = openWebPage(context, config.webPage("terms"))
            override fun openPrivacy() = openWebPage(context, config.webPage("privacy"))
            override fun openDeleteAccount() = viewModel.openDeleteAccount()
            override fun deleteAccountChange(value: String) = viewModel.onDeleteAccountChange(value)
            override fun closeDeleteAccount() = viewModel.closeDeleteAccount()
            override fun deleteAccount() = viewModel.deleteAccount()
            override fun logout() = viewModel.logout()
            override fun messageShown() = viewModel.messageShown()
            override fun openFeedback() = onOpenFeedback()
            override fun syncGarmin() = viewModel.syncGarmin()
            override fun openNotificationSettings() = openAppNotificationSettings(context)
        }
    }
    ProfileScreen(
        state = state,
        fmt = rememberFmt(),
        today = viewModel.today(),
        actions = actions,
        notificationsOn = notificationsOn.takeIf { config.pushEnabled },
    )
}

/** The profile of the web app (`profile-page`): the sections in its order, pairs side by side on wide screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    fmt: Fmt,
    today: LocalDate,
    actions: ProfileActions,
    /** The notifications are on (spec 020); `null` hides the row (no Firebase in the build). */
    notificationsOn: Boolean? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.resolve()
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.messageShown()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("profile-snackbar")) }) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            BoxWithConstraints {
                val wide = maxWidth >= 600.dp
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                        .testTag("profile-content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(state, fmt)
                    if (state.loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("profile-loading"))
                    }
                    if (state.riderFailed) {
                        ErrorBanner(stringResource(R.string.profile_load_failed), Modifier.testTag("profile-error"))
                        OutlinedButton(onClick = actions::refresh, modifier = Modifier.testTag("profile-retry")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                    val riderKnown = !state.loading && !state.riderFailed
                    Pair(wide, { if (riderKnown) RiderCard(state, actions) }, { PreferencesCard(state, actions) })
                    if (state.rider != null) {
                        Pair(
                            wide,
                            { ThresholdsCard(state, fmt, actions) },
                            { FtpTrendCard(state.thresholds, fmt, today) },
                        )
                        Pair(wide, { PowerZonesCard(state.zones, fmt, actions) }, { HeartRateZonesCard(state.zones, fmt, actions) })
                        IntervalsCard(state, fmt, actions)
                        GarminCard(state, fmt, actions)
                    }
                    GarminSyncCard(state, actions)
                    AccountCard(state, fmt, actions, notificationsOn)
                    OutlinedButton(onClick = actions::logout, modifier = Modifier.testTag("profile-logout")) {
                        Icon(painterResource(R.drawable.ic_logout), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.logout), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
    state.thresholdForm?.let { ThresholdSheet(it, state.thresholdError, fmt, today, actions) }
    state.confirmation?.let { ConfirmationDialog(it, fmt, actions) }
    state.deleteAccount?.let { form -> state.account?.let { DeleteAccountDialog(form, it, actions) } }
}

@Composable
private fun Header(state: ProfileUiState, fmt: Fmt) {
    Column {
        Text(
            stringResource(if (state.noRider) R.string.profile_create_title else R.string.nav_profile),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }.testTag("profile-title"),
        )
        val subtitle = when {
            state.noRider -> stringResource(R.string.profile_create_hint)
            state.rider?.currentThresholds != null -> currentThresholds(state.rider, fmt)
            else -> null
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("profile-subtitle"),
            )
        }
    }
}

/** `FTP 250 W (3,47 W/kg) · LTHR 165 bpm · Max HR 185 bpm`, as the web's page header. */
@Composable
private fun currentThresholds(rider: Rider, fmt: Fmt): String {
    val current = rider.currentThresholds ?: return ""
    val perKg = rider.ftpWattsPerKg?.let { " (${stringResource(R.string.unit_watts_per_kg, fmt.num(it, 2))})" }.orEmpty()
    return "FTP ${fmt.num(current.ftpWatts)} W$perKg · LTHR ${fmt.num(current.lthrBpm)} bpm · " +
        "${stringResource(R.string.profile_max_hr)} ${fmt.num(current.maxHeartRateBpm)} bpm"
}

/** Two cards side by side on wide screens, one under the other on phones. */
@Composable
private fun Pair(wide: Boolean, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { first() }
            Column(Modifier.weight(1f)) { second() }
        }
    } else {
        first()
        second()
    }
}
