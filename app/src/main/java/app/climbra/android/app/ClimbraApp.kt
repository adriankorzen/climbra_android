package app.climbra.android.app

import android.widget.Toast
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import app.climbra.android.auth.ForgotPasswordRoute
import app.climbra.android.auth.ForgotPasswordViewModel
import app.climbra.android.auth.LoggedOutContent
import app.climbra.android.activities.ActivitiesRoute
import app.climbra.android.activities.ImportRoute
import app.climbra.android.activities.RideImport
import app.climbra.android.activities.SharedRideFiles
import app.climbra.android.activities.RideDetailRoute
import app.climbra.android.auth.TermsRoute
import app.climbra.android.calendar.CalendarRequests
import app.climbra.android.calendar.CalendarRoute
import app.climbra.android.calendar.WorkoutDetailRoute
import java.time.LocalDate
import app.climbra.android.dashboard.DashboardRoute
import app.climbra.android.data.api.AccountResponse
import app.climbra.android.profile.FeedbackRoute
import app.climbra.android.profile.ProfileRoute
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.shell.Shell
import app.climbra.android.shell.ShellNavigator
import app.climbra.android.coach.AdjustmentFormRoute
import app.climbra.android.coach.CoachActivity
import app.climbra.android.garmin.GarminConnectFlow
import app.climbra.android.garmin.GarminConnectHost
import app.climbra.android.garmin.GarminSync
import app.climbra.android.intervals.IntervalsSync
import kotlinx.coroutines.flow.merge
import app.climbra.android.coach.CoachListRoute
import app.climbra.android.coach.CoachNavigation
import app.climbra.android.coach.ConversationRoute
import app.climbra.android.coach.FileReviewRoute
import app.climbra.android.coach.TrainingBlockFormRoute
import app.climbra.android.ui.common.openWebPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    private val session: SessionRepository,
    val config: AppConfig,
    private val calendarRequests: CalendarRequests,
    val coachActivity: CoachActivity,
    val intervalsSync: IntervalsSync,
    val rideImport: RideImport,
    val sharedRideFiles: SharedRideFiles,
    val garminSync: GarminSync,
    val garminConnect: GarminConnectFlow,
) : ViewModel() {

    val state: StateFlow<SessionState> = session.state

    init {
        retry()
    }

    fun retry() {
        viewModelScope.launch { session.start() }
    }

    /** The logged-in account's email, for the password reset of the profile (spec 013). */
    fun accountEmail(): String? = (state.value as? SessionState.LoggedIn)?.account?.email

    /** The calendar shows the week of [date] (an ISO date) when opened next (spec 005). */
    fun showCalendarWeek(date: String?) {
        date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }?.let(calendarRequests::show)
    }
}

@Composable
fun ClimbraApp(viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ClimbraContent(
        state = state,
        onRetry = viewModel::retry,
        login = { loggedOut ->
            LoggedOutContent(loggedOut, viewModel.config)
        },
        terms = { changed -> TermsRoute(documentsChanged = changed, config = viewModel.config) },
        shell = { _ ->
            val config = viewModel.config
            val context = LocalContext.current
            val coachNavigation: (ShellNavigator) -> CoachNavigation = { nav ->
                CoachNavigation(
                    openConversation = nav::openConversation,
                    newTrainingBlock = nav::newTrainingBlock,
                    reviewFile = nav::reviewFile,
                    openActivities = nav::openActivities,
                )
            }
            // The consent dialog and Garmin's page, asked for by the profile and the calendar (spec 016)
            GarminConnectHost(viewModel.garminConnect)
            Shell(
                dashboard = { nav ->
                    DashboardRoute(config = config, onOpenRide = nav::openRide, coach = coachNavigation(nav), onOpenCoach = nav::openCoach, onImport = nav::openImport)
                },
                activities = { nav -> ActivitiesRoute(config = config, onOpenRide = nav::openRide, onImport = nav::openImport) },
                rideImport = { nav ->
                    ImportRoute(
                        config = config,
                        onBack = nav::back,
                        onOpenRide = nav::openRide,
                        onAdded = { id ->
                            Toast.makeText(context, R.string.import_ride_added, Toast.LENGTH_SHORT).show()
                            nav.rideAdded(id)
                        },
                        onOpenProfile = nav::openProfile,
                    )
                },
                profile = { nav -> ProfileRoute(config = config, onOpenFeedback = nav::openFeedback, onForgotPassword = nav::openForgotPassword) },
                forgotPassword = { nav ->
                    val forgot: ForgotPasswordViewModel = hiltViewModel()
                    forgot.prefill(viewModel.accountEmail())
                    ForgotPasswordRoute(config = config, onBack = nav::back, viewModel = forgot)
                },
                feedback = { nav ->
                    FeedbackRoute(
                        onBack = nav::back,
                        onSent = {
                            Toast.makeText(context, R.string.feedback_sent, Toast.LENGTH_SHORT).show()
                            nav.back()
                        },
                    )
                },
                calendar = { nav ->
                    CalendarRoute(
                        config = config,
                        onOpenRide = nav::openRide,
                        onOpenWorkout = { nav.openWorkout(it.id, it.date) },
                        onOpenConversation = nav::openConversation,
                        onNewTrainingBlock = nav::newTrainingBlock,
                        onAdjust = nav::openAdjustment,
                        onOpenProposal = nav::openProposal,
                    )
                },
                workout = { nav -> WorkoutDetailRoute(onBack = nav::back, onOpenRide = nav::openRide) },
                ride = { nav ->
                    RideDetailRoute(
                        onBack = nav::back,
                        onDeleted = {
                            Toast.makeText(context, R.string.activity_deleted, Toast.LENGTH_SHORT).show()
                            nav.back()
                        },
                        onOpenCalendar = { date ->
                            viewModel.showCalendarWeek(date)
                            nav.openCalendar()
                        },
                        onOpenConversation = nav::openConversation,
                        onOpenTerms = { openWebPage(context, config.webPage("terms")) },
                    )
                },
                coach = { nav -> CoachListRoute(config = config, navigation = coachNavigation(nav)) },
                conversation = { nav ->
                    ConversationRoute(
                        config = config,
                        onBack = nav::back,
                        onOpenRide = nav::openRide,
                        onOpenCalendar = { date ->
                            viewModel.showCalendarWeek(date)
                            nav.openCalendar()
                        },
                        onDeleted = {
                            Toast.makeText(context, R.string.coach_deleted, Toast.LENGTH_SHORT).show()
                            nav.back()
                        },
                        onAdjust = nav::openAdjustment,
                    )
                },
                adjustment = { nav -> AdjustmentFormRoute(config = config, onBack = nav::back, onAccepted = nav::adjustmentAccepted) },
                trainingBlock = { nav -> TrainingBlockFormRoute(config = config, onBack = nav::back, onAccepted = nav::replaceWithConversation) },
                fileReview = { nav -> FileReviewRoute(config = config, onBack = nav::back, onAccepted = nav::replaceWithConversation) },
                coachEvents = viewModel.coachActivity.events,
                notices = remember(viewModel) {
                    merge(
                        viewModel.coachActivity.notices,
                        viewModel.intervalsSync.messages,
                        viewModel.sharedRideFiles.notices,
                        viewModel.garminSync.messages,
                        viewModel.garminConnect.messages,
                    )
                },
                importRequests = remember(viewModel) {
                    merge(viewModel.sharedRideFiles.pending.filterNotNull().map { }, viewModel.sharedRideFiles.opens)
                },
                importNotices = viewModel.rideImport.notices,
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
    login: @Composable (loggedOut: SessionState.LoggedOut) -> Unit,
    terms: @Composable (documentsChanged: Boolean) -> Unit,
    shell: @Composable (account: AccountResponse) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        when (state) {
            // The system splash screen stays up meanwhile (MainActivity)
            SessionState.Loading -> Box(Modifier.fillMaxSize().testTag("start-loading"))
            is SessionState.Unavailable -> StartUnavailable(onRetry)
            is SessionState.LoggedOut -> login(state)
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
