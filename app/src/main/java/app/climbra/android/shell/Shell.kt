package app.climbra.android.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
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
import androidx.navigation.toRoute
import app.climbra.android.R
import app.climbra.android.coach.AdjustmentArgs
import app.climbra.android.coach.CoachFinished
import app.climbra.android.coach.ConversationTab
import app.climbra.android.data.net.OfflineState
import app.climbra.android.push.PushTarget
import app.climbra.android.ui.common.LocalOffline
import app.climbra.android.ui.common.PaneItem
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

/** The power curve opened from the dashboard's card (spec 026). */
@Serializable
data object DashboardPowerCurveRoute

/** The power curve opened from the training log's top bar (spec 026). */
@Serializable
data object ActivitiesPowerCurveRoute

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

/**
 * Where a screen of the shell can lead; one object for every tab's screens. A tab showing two panes now
 * ([twoPanes]) shows its rides, workouts and conversations in its detail pane instead of a screen of their own; in
 * that pane [closePane] is Back (spec 019).
 */
class ShellNavigator internal constructor(
    private val nav: NavController,
    private val twoPanes: () -> Set<TopLevelDestination> = { emptySet() },
    private val closePane: (() -> Unit)? = null,
) {

    /** The navigator of a detail pane: Back clears it. */
    internal fun forPane(close: () -> Unit) = ShellNavigator(nav, twoPanes, close)

    fun back() {
        closePane?.invoke() ?: nav.popBackStack()
    }

    /** A ride on the stack of the tab shown now (spec 003), or in its detail pane. */
    fun openRide(id: Long) {
        val tab = currentTab()
        if ((tab == TopLevelDestination.ACTIVITIES || tab == TopLevelDestination.CALENDAR) && showInPane(tab, PaneItem.Ride(id))) return
        nav.navigate(
            when (tab) {
                TopLevelDestination.DASHBOARD -> DashboardRideRoute(id)
                TopLevelDestination.CALENDAR -> CalendarRideRoute(id)
                TopLevelDestination.COACH -> CoachRideRoute(id)
                else -> ActivitiesRideRoute(id)
            },
        )
    }

    fun openWorkout(id: Long, date: String) {
        if (showInPane(TopLevelDestination.CALENDAR, PaneItem.Workout(id, date))) return
        nav.navigate(CalendarWorkoutRoute(id, date))
    }

    /** The week the caller told the calendar, not a workout or ride left open on its stack (spec 005). */
    fun openCalendar() = nav.openTab(TopLevelDestination.CALENDAR)

    fun openActivities() = nav.openTab(TopLevelDestination.ACTIVITIES)

    /** The power curve on the stack of the tab shown now, the dashboard's or the training log's (spec 026). */
    fun openPowerCurve() {
        nav.navigate(if (currentTab() == TopLevelDestination.DASHBOARD) DashboardPowerCurveRoute else ActivitiesPowerCurveRoute) {
            launchSingleTop = true
        }
    }

    /** A new ride of the automatic sync (spec 025): on the training log's tab, in its pane from 840 dp. */
    fun openRideOnTrainingLog(id: Long) {
        if (showInPane(TopLevelDestination.ACTIVITIES, PaneItem.Ride(id))) return
        if (currentTab() != TopLevelDestination.ACTIVITIES) openActivities()
        nav.navigate(ActivitiesRideRoute(id))
    }

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
        if (showInPane(TopLevelDestination.ACTIVITIES, PaneItem.Ride(id))) return
        nav.navigate(ActivitiesRideRoute(id)) {
            popUpTo<ActivitiesImportRoute> { inclusive = true }
        }
    }

    fun openCoach() = nav.openTab(TopLevelDestination.COACH)

    /** The Coach tab with the list as its start and the conversation on top, so back returns to the list (spec 006). */
    fun openConversation(id: Long) {
        if (showInPane(TopLevelDestination.COACH, PaneItem.Conversation(id))) return
        if (currentTab() != TopLevelDestination.COACH) openCoach()
        nav.navigate(CoachConversationRoute(id))
    }

    /** An accepted form gives way to its conversation. */
    fun replaceWithConversation(id: Long) {
        if (showInPane(TopLevelDestination.COACH, PaneItem.Conversation(id))) return
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
        if (showInPane(TopLevelDestination.COACH, PaneItem.Conversation(conversationId, ConversationTab.PANEL.name))) return
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

    /**
     * Shows [item] next to the list of [tab] when the tab shows two panes now: the tab's start screen, what was over
     * it gone. @return `false` with one pane
     */
    private fun showInPane(tab: TopLevelDestination, item: PaneItem): Boolean {
        if (tab !in twoPanes()) return false
        if (currentTab() == tab) nav.popBackStack(tab.startRoute, inclusive = false) else nav.openTab(tab)
        nav.setPaneItem(tab, item)
        return true
    }

    /** A tab of the navigation tapped: its start screen; the selected one again also empties its pane. */
    internal fun openTab(tab: TopLevelDestination, reselected: Boolean) {
        nav.openTab(tab)
        if (reselected && tab.twoPanesFrom != null) nav.setPaneItem(tab, null)
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
    powerCurve: @Composable (ShellNavigator) -> Unit = {},
    feedback: @Composable (ShellNavigator) -> Unit = {},
    forgotPassword: @Composable (ShellNavigator) -> Unit = {},
    coachEvents: Flow<CoachFinished> = emptyFlow(),
    notices: Flow<UiText> = emptyFlow(),
    /** Shared files wait for the import screen (spec 009). */
    importRequests: Flow<Unit> = emptyFlow(),
    /** The end of an import while its screen is not shown, with "Show". */
    importNotices: Flow<UiText> = emptyFlow(),
    /** Saved data is shown (spec 018): a banner with Retry, and actions that send something are disabled. */
    offline: OfflineState? = null,
    onRetryOffline: () -> Unit = {},
    /** A tapped notification's target (spec 020), opened and taken. */
    pushTarget: PushTarget? = null,
    onPushTargetTaken: (PushTarget) -> Unit = {},
) {
    val navController = rememberNavController()
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val twoPanes = twoPaneTabs(width)
    val currentTwoPanes by rememberUpdatedState(twoPanes)
    val navigator = remember(navController) { ShellNavigator(navController, { currentTwoPanes }) }
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
    LaunchedEffect(pushTarget) {
        val target = pushTarget ?: return@LaunchedEffect
        when (target) {
            is PushTarget.Conversation -> navigator.openConversation(target.id)
            PushTarget.TrainingLog -> navigator.openActivities()
            is PushTarget.Ride -> navigator.openRideOnTrainingLog(target.id)
        }
        onPushTargetTaken(target)
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
    val rail = width >= RAIL_WIDTH
    // The rail from medium windows, also on a phone in landscape (spec 019)
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { item ->
                val selected = destination?.hierarchy?.any { it.hasRoute(item.graphClass) } == true
                item(
                    selected = selected,
                    onClick = { navigator.openTab(item, reselected = selected) },
                    icon = { Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null) },
                    label = { Text(stringResource(item.label)) },
                    modifier = Modifier.testTag("nav-${item.name.lowercase()}"),
                )
            }
        },
        layoutType = if (rail) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
        modifier = Modifier.testTag(if (rail) "shell-rail" else "shell-bar"),
    ) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("shell-snackbar")) }) { padding ->
            // The bars inset is applied here: nested Scaffolds and TopAppBars must not add it again
            Column(Modifier.padding(padding).consumeWindowInsets(padding)) {
                offline?.let { OfflineBanner(it, onRetryOffline) }
                CompositionLocalProvider(LocalOffline provides (offline != null)) {
                    NavHost(
                        navController = navController,
                        startDestination = DashboardGraph,
                        modifier = Modifier.weight(1f),
                    ) {
                        navigation<DashboardGraph>(startDestination = DashboardRoute) {
                            composable<DashboardRoute> { Screen { dashboard(navigator) } }
                            composable<DashboardRideRoute> { Screen { ride(navigator) } }
                            composable<DashboardPowerCurveRoute> { Screen { powerCurve(navigator) } }
                        }
                        navigation<ActivitiesGraph>(startDestination = ActivitiesRoute) {
                            val tab = TopLevelDestination.ACTIVITIES
                            composable<ActivitiesRoute> { entry ->
                                Panes(
                                    PaneHost(entry, tab in twoPanes, navController, navigator),
                                    placeholder = Placeholder(R.string.pane_choose_ride, R.drawable.ic_directions_bike),
                                    route = { (it as? PaneItem.Ride)?.let { item -> ActivitiesRideRoute(item.id) } },
                                    list = { activities(navigator) },
                                    detail = { _, pane -> ride(pane) },
                                )
                            }
                            composable<ActivitiesRideRoute> { entry ->
                                DetailScreen(tab, tab in twoPanes, navController, PaneItem.Ride(entry.toRoute<ActivitiesRideRoute>().id)) {
                                    ride(navigator)
                                }
                            }
                            composable<ActivitiesImportRoute> { Screen { rideImport(navigator) } }
                            composable<ActivitiesPowerCurveRoute> { Screen { powerCurve(navigator) } }
                        }
                        navigation<CalendarGraph>(startDestination = CalendarRoute) {
                            val tab = TopLevelDestination.CALENDAR
                            composable<CalendarRoute> { entry ->
                                Panes(
                                    PaneHost(entry, tab in twoPanes, navController, navigator),
                                    placeholder = Placeholder(R.string.pane_choose_calendar_item, R.drawable.ic_calendar_month),
                                    route = { item ->
                                        when (item) {
                                            is PaneItem.Workout -> CalendarWorkoutRoute(item.id, item.date)
                                            is PaneItem.Ride -> CalendarRideRoute(item.id)
                                            else -> null
                                        }
                                    },
                                    list = { calendar(navigator) },
                                    detail = { item, pane -> if (item is PaneItem.Workout) workout(pane) else ride(pane) },
                                    // The month's grid takes the width, the detail is the narrower pane
                                    listWidth = null,
                                    detailWidth = CALENDAR_DETAIL_WIDTH,
                                )
                            }
                            composable<CalendarWorkoutRoute> { entry ->
                                val route = entry.toRoute<CalendarWorkoutRoute>()
                                DetailScreen(tab, tab in twoPanes, navController, PaneItem.Workout(route.id, route.date)) { workout(navigator) }
                            }
                            composable<CalendarRideRoute> { entry ->
                                DetailScreen(tab, tab in twoPanes, navController, PaneItem.Ride(entry.toRoute<CalendarRideRoute>().id)) {
                                    ride(navigator)
                                }
                            }
                            composable<CalendarAdjustmentRoute> { Screen { adjustment(navigator) } }
                        }
                        navigation<CoachGraph>(startDestination = CoachRoute) {
                            val tab = TopLevelDestination.COACH
                            composable<CoachRoute> { entry ->
                                Panes(
                                    PaneHost(entry, tab in twoPanes, navController, navigator),
                                    placeholder = Placeholder(R.string.pane_choose_conversation, R.drawable.ic_forum),
                                    route = { (it as? PaneItem.Conversation)?.let { item -> CoachConversationRoute(item.id, item.tab) } },
                                    list = { coach(navigator) },
                                    detail = { _, pane -> conversation(pane) },
                                )
                            }
                            composable<CoachConversationRoute> { entry ->
                                val route = entry.toRoute<CoachConversationRoute>()
                                DetailScreen(tab, tab in twoPanes, navController, PaneItem.Conversation(route.id, route.tab)) {
                                    conversation(navigator)
                                }
                            }
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
