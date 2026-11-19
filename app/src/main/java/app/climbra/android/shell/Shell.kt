package app.climbra.android.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import app.climbra.android.R
import app.climbra.android.coach.AdjustmentArgs
import app.climbra.android.coach.CoachFinished
import app.climbra.android.coach.ConversationTab
import app.climbra.android.ui.common.UiText
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// Each tab is a graph of its own, so a ride opened from it stays on that tab's back stack

@Serializable
data object DashboardGraph

@Serializable
data object DashboardRoute

/** A ride opened from the dashboard's recent rides (spec 003). */
@Serializable
data class DashboardRideRoute(val id: Long)

@Serializable
data object ActivitiesGraph

@Serializable
data object ActivitiesRoute

/** A ride opened from the training log (spec 003). */
@Serializable
data class ActivitiesRideRoute(val id: Long)

/** Importing rides from files (spec 009). */
@Serializable
data object ActivitiesImportRoute

@Serializable
data object CalendarGraph

@Serializable
data object CalendarRoute

/** A planned workout of the calendar (spec 005); [date] is its ISO date. */
@Serializable
data class CalendarWorkoutRoute(val id: Long, val date: String)

/** A ride opened from the calendar (spec 005). */
@Serializable
data class CalendarRideRoute(val id: Long)

/** The adjustment form opened from a plan of the calendar (spec 008); the arguments are [AdjustmentArgs]'. */
@Serializable
data class CalendarAdjustmentRoute(
    val planId: Long,
    val date: String,
    val suggestion: Boolean = false,
    val fromDate: String? = null,
    val note: String? = null,
)

@Serializable
data object CoachGraph

/** The conversation list (spec 006). */
@Serializable
data object CoachRoute

/** [tab] `PANEL` opens the conversation on its panel, e.g. a proposal (spec 008). */
@Serializable
data class CoachConversationRoute(val id: Long, val tab: String? = null)

/** The adjustment form opened from a review (spec 008). */
@Serializable
data class CoachAdjustmentRoute(
    val planId: Long,
    val date: String,
    val suggestion: Boolean = false,
    val fromDate: String? = null,
    val note: String? = null,
)

@Serializable
data object CoachTrainingBlockRoute

@Serializable
data object CoachFileReviewRoute

/** A ride opened from a coach conversation. */
@Serializable
data class CoachRideRoute(val id: Long)

@Serializable
data object ProfileGraph

@Serializable
data object ProfileRoute

/** The feedback form (spec 010). */
@Serializable
data object ProfileFeedbackRoute

/** The password reset of an account without a password (spec 013). */
@Serializable
data object ProfileForgotPasswordRoute

/** Top-level destinations; the web app's sidebar / tab bar (spec 002 of cyclingapp) as a bottom navigation bar. */
enum class TopLevelDestination(
    val graph: Any,
    val graphClass: KClass<*>,
    val startRoute: Any,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    DASHBOARD(DashboardGraph, DashboardGraph::class, DashboardRoute, R.string.nav_dashboard, R.drawable.ic_dashboard, R.drawable.ic_dashboard_fill1),
    ACTIVITIES(
        ActivitiesGraph, ActivitiesGraph::class, ActivitiesRoute, R.string.nav_activities,
        R.drawable.ic_directions_bike, R.drawable.ic_directions_bike_fill1,
    ),
    CALENDAR(
        CalendarGraph, CalendarGraph::class, CalendarRoute, R.string.nav_calendar,
        R.drawable.ic_calendar_month, R.drawable.ic_calendar_month_fill1,
    ),
    COACH(CoachGraph, CoachGraph::class, CoachRoute, R.string.nav_coach, R.drawable.ic_forum, R.drawable.ic_forum_fill1),
    PROFILE(ProfileGraph, ProfileGraph::class, ProfileRoute, R.string.nav_profile, R.drawable.ic_person, R.drawable.ic_person_fill1),
}

/** Where a screen of the shell can lead; one object for every tab's screens. */
class ShellNavigator internal constructor(private val nav: NavController) {

    fun back() {
        nav.popBackStack()
    }

    /** A ride on the stack of the tab shown now (spec 003). */
    fun openRide(id: Long) {
        nav.navigate(
            when (currentTab()) {
                TopLevelDestination.DASHBOARD -> DashboardRideRoute(id)
                TopLevelDestination.CALENDAR -> CalendarRideRoute(id)
                TopLevelDestination.COACH -> CoachRideRoute(id)
                else -> ActivitiesRideRoute(id)
            },
        )
    }

    fun openWorkout(id: Long, date: String) {
        nav.navigate(CalendarWorkoutRoute(id, date))
    }

    /** The week the caller told the calendar, not a workout or ride left open on its stack (spec 005). */
    fun openCalendar() = nav.openTab(TopLevelDestination.CALENDAR)

    fun openActivities() = nav.openTab(TopLevelDestination.ACTIVITIES)

    fun openProfile() = nav.openTab(TopLevelDestination.PROFILE)

    /** The password reset on the profile's stack (spec 013). */
    fun openForgotPassword() {
        nav.navigate(ProfileForgotPasswordRoute) { launchSingleTop = true }
    }

    /** The feedback form on the profile's stack (spec 010). */
    fun openFeedback() {
        nav.navigate(ProfileFeedbackRoute) { launchSingleTop = true }
    }

    /** The import screen on the training log's stack (spec 009); shown already, it stays as it is. */
    fun openImport() {
        if (currentTab() != TopLevelDestination.ACTIVITIES) openActivities()
        nav.navigate(ActivitiesImportRoute) { launchSingleTop = true }
    }

    /** A single ride was added: its detail in place of the import screen. */
    fun rideAdded(id: Long) {
        nav.navigate(ActivitiesRideRoute(id)) {
            popUpTo<ActivitiesImportRoute> { inclusive = true }
        }
    }

    fun openCoach() = nav.openTab(TopLevelDestination.COACH)

    /** The Coach tab with the list as its start and the conversation on top, so back returns to the list (spec 006). */
    fun openConversation(id: Long) {
        if (currentTab() != TopLevelDestination.COACH) openCoach()
        nav.navigate(CoachConversationRoute(id))
    }

    /** An accepted form gives way to its conversation. */
    fun replaceWithConversation(id: Long) {
        nav.navigate(CoachConversationRoute(id)) {
            popUpTo<CoachRoute> { inclusive = false }
        }
    }

    /** The adjustment form on the stack of the tab shown now: the calendar's, else the coach's (spec 008). */
    fun openAdjustment(args: AdjustmentArgs) {
        nav.navigate(
            if (currentTab() == TopLevelDestination.CALENDAR) {
                CalendarAdjustmentRoute(args.planId, args.date, args.suggestion, args.fromDate, args.note)
            } else {
                CoachAdjustmentRoute(args.planId, args.date, args.suggestion, args.fromDate, args.note)
            },
        )
    }

    /** The accepted adjustment's conversation on the Coach tab, in place of the form. */
    fun adjustmentAccepted(conversationId: Long) {
        if (currentTab() == TopLevelDestination.COACH) {
            replaceWithConversation(conversationId)
        } else {
            back()
            openConversation(conversationId)
        }
    }

    /** A proposal: its conversation on the Coach tab, on the proposal tab (spec 008). */
    fun openProposal(conversationId: Long) {
        if (currentTab() != TopLevelDestination.COACH) openCoach()
        nav.navigate(CoachConversationRoute(conversationId, ConversationTab.PANEL.name))
    }

    fun newTrainingBlock() {
        if (currentTab() != TopLevelDestination.COACH) openCoach()
        nav.navigate(CoachTrainingBlockRoute)
    }

    fun reviewFile() {
        if (currentTab() != TopLevelDestination.COACH) openCoach()
        nav.navigate(CoachFileReviewRoute)
    }

    private fun currentTab(): TopLevelDestination? = nav.currentDestination?.tab()
}

private fun NavDestination.tab(): TopLevelDestination? =
    TopLevelDestination.entries.firstOrNull { item -> hierarchy.any { it.hasRoute(item.graphClass) } }

/**
 * The app after login; each tab keeps its state when switching (spec 001 AC11), and tapping a tab shows its start
 * screen, not a ride or workout opened from it (spec 003). The ride detail is a destination on the Dashboard,
 * Activities, Calendar and Coach stacks; the coach's requests that end in the background are told in a snackbar
 * with "Open" ([coachEvents], spec 006), as the coach's short [notices].
 */
@Composable
fun Shell(
    dashboard: @Composable (ShellNavigator) -> Unit,
    activities: @Composable (ShellNavigator) -> Unit,
    profile: @Composable (ShellNavigator) -> Unit,
    calendar: @Composable (ShellNavigator) -> Unit = {},
    workout: @Composable (ShellNavigator) -> Unit = {},
    ride: @Composable (ShellNavigator) -> Unit = {},
    coach: @Composable (ShellNavigator) -> Unit = {},
    conversation: @Composable (ShellNavigator) -> Unit = {},
    trainingBlock: @Composable (ShellNavigator) -> Unit = {},
    fileReview: @Composable (ShellNavigator) -> Unit = {},
    adjustment: @Composable (ShellNavigator) -> Unit = {},
    rideImport: @Composable (ShellNavigator) -> Unit = {},
    feedback: @Composable (ShellNavigator) -> Unit = {},
    forgotPassword: @Composable (ShellNavigator) -> Unit = {},
    coachEvents: Flow<CoachFinished> = emptyFlow(),
    notices: Flow<UiText> = emptyFlow(),
    /** Shared files wait for the import screen (spec 009). */
    importRequests: Flow<Unit> = emptyFlow(),
    /** The end of an import while its screen is not shown, with "Show". */
    importNotices: Flow<UiText> = emptyFlow(),
) {
    val navController = rememberNavController()
    val navigator = remember(navController) { ShellNavigator(navController) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(coachEvents) {
        coachEvents.collect { event ->
            launch {
                val text = resources.getString(if (event.failed) R.string.coach_failed else R.string.coach_answered, event.title)
                val result = snackbar.showSnackbar(text, actionLabel = resources.getString(R.string.coach_open), duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) navigator.openConversation(event.conversationId)
            }
        }
    }
    LaunchedEffect(notices) {
        notices.collect { notice ->
            launch { snackbar.showSnackbar(notice.resolve(resources)) }
        }
    }
    LaunchedEffect(importRequests) {
        importRequests.collect { navigator.openImport() }
    }
    LaunchedEffect(importNotices) {
        importNotices.collect { notice ->
            launch {
                val result = snackbar.showSnackbar(
                    notice.resolve(resources),
                    actionLabel = resources.getString(R.string.import_show),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) navigator.openImport()
            }
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { item ->
                    val selected = destination?.hierarchy?.any { it.hasRoute(item.graphClass) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.openTab(item) },
                        icon = {
                            Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null)
                        },
                        label = { Text(stringResource(item.label)) },
                        modifier = Modifier.testTag("nav-${item.name.lowercase()}"),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("shell-snackbar")) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = DashboardGraph,
            // The bars inset is applied here: nested Scaffolds and TopAppBars must not add it again
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            navigation<DashboardGraph>(startDestination = DashboardRoute) {
                composable<DashboardRoute> { Screen { dashboard(navigator) } }
                composable<DashboardRideRoute> { Screen { ride(navigator) } }
            }
            navigation<ActivitiesGraph>(startDestination = ActivitiesRoute) {
                composable<ActivitiesRoute> { Screen { activities(navigator) } }
                composable<ActivitiesRideRoute> { Screen { ride(navigator) } }
                composable<ActivitiesImportRoute> { Screen { rideImport(navigator) } }
            }
            navigation<CalendarGraph>(startDestination = CalendarRoute) {
                composable<CalendarRoute> { Screen { calendar(navigator) } }
                composable<CalendarWorkoutRoute> { Screen { workout(navigator) } }
                composable<CalendarRideRoute> { Screen { ride(navigator) } }
                composable<CalendarAdjustmentRoute> { Screen { adjustment(navigator) } }
            }
            navigation<CoachGraph>(startDestination = CoachRoute) {
                composable<CoachRoute> { Screen { coach(navigator) } }
                composable<CoachConversationRoute> { Screen { conversation(navigator) } }
                composable<CoachTrainingBlockRoute> { Screen { trainingBlock(navigator) } }
                composable<CoachFileReviewRoute> { Screen { fileReview(navigator) } }
                composable<CoachRideRoute> { Screen { ride(navigator) } }
                composable<CoachAdjustmentRoute> { Screen { adjustment(navigator) } }
            }
            navigation<ProfileGraph>(startDestination = ProfileRoute) {
                composable<ProfileRoute> { Screen { profile(navigator) } }
                composable<ProfileFeedbackRoute> { Screen { feedback(navigator) } }
                composable<ProfileForgotPasswordRoute> { Screen { forgotPassword(navigator) } }
            }
        }
    }
}

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) { content() }
}

/**
 * Shows the tab's start screen with its state restored (list scroll, filters), never a ride or workout left open on
 * its stack: tapping a tab, selected or not, leads to that tab's page.
 */
private fun NavController.openTab(item: TopLevelDestination) {
    navigate(item.graph) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    popBackStack(item.startRoute, inclusive = false)
}
