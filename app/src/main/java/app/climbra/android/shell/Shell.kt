package app.climbra.android.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import app.climbra.android.R
import kotlin.reflect.KClass
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

@Serializable
data object ProfileGraph

@Serializable
data object ProfileRoute

/** Top-level destinations; the web app's sidebar / tab bar (spec 002 of cyclingapp) as a bottom navigation bar. */
enum class TopLevelDestination(
    val graph: Any,
    val graphClass: KClass<*>,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    DASHBOARD(DashboardGraph, DashboardGraph::class, R.string.nav_dashboard, R.drawable.ic_dashboard, R.drawable.ic_dashboard_fill1),
    ACTIVITIES(
        ActivitiesGraph, ActivitiesGraph::class, R.string.nav_activities,
        R.drawable.ic_directions_bike, R.drawable.ic_directions_bike_fill1,
    ),
    PROFILE(ProfileGraph, ProfileGraph::class, R.string.nav_profile, R.drawable.ic_person, R.drawable.ic_person_fill1),
}

/**
 * The app after login; each tab keeps its own back stack and state when switching (spec 001 AC11). The ride detail
 * is a destination on the Dashboard and Activities stacks; [ride] reads its id from the route.
 */
@Composable
fun Shell(
    dashboard: @Composable (openRide: (Long) -> Unit) -> Unit,
    activities: @Composable (openRide: (Long) -> Unit) -> Unit,
    profile: @Composable () -> Unit,
    ride: @Composable (onBack: () -> Unit) -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { item ->
                    val selected = destination?.hierarchy?.any { it.hasRoute(item.graphClass) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.graph) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null)
                        },
                        label = { Text(stringResource(item.label)) },
                        modifier = Modifier.testTag("nav-${item.name.lowercase()}"),
                    )
                }
            }
        },
    ) { padding ->
        val back: () -> Unit = { navController.popBackStack() }
        NavHost(
            navController = navController,
            startDestination = DashboardGraph,
            modifier = Modifier.padding(padding),
        ) {
            navigation<DashboardGraph>(startDestination = DashboardRoute) {
                composable<DashboardRoute> {
                    Box(Modifier.fillMaxSize()) { dashboard { id -> navController.navigate(DashboardRideRoute(id)) } }
                }
                composable<DashboardRideRoute> { Box(Modifier.fillMaxSize()) { ride(back) } }
            }
            navigation<ActivitiesGraph>(startDestination = ActivitiesRoute) {
                composable<ActivitiesRoute> {
                    Box(Modifier.fillMaxSize()) { activities { id -> navController.navigate(ActivitiesRideRoute(id)) } }
                }
                composable<ActivitiesRideRoute> { Box(Modifier.fillMaxSize()) { ride(back) } }
            }
            navigation<ProfileGraph>(startDestination = ProfileRoute) {
                composable<ProfileRoute> { Box(Modifier.fillMaxSize()) { profile() } }
            }
        }
    }
}
