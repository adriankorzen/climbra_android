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
import androidx.navigation.compose.rememberNavController
import app.climbra.android.R
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

@Serializable
data object DashboardRoute

@Serializable
data object ActivitiesRoute

@Serializable
data object ProfileRoute

/** Top-level destinations; the web app's sidebar / tab bar (spec 002 of cyclingapp) as a bottom navigation bar. */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    DASHBOARD(DashboardRoute, DashboardRoute::class, R.string.nav_dashboard, R.drawable.ic_dashboard, R.drawable.ic_dashboard_fill1),
    ACTIVITIES(
        ActivitiesRoute, ActivitiesRoute::class, R.string.nav_activities,
        R.drawable.ic_directions_bike, R.drawable.ic_directions_bike_fill1,
    ),
    PROFILE(ProfileRoute, ProfileRoute::class, R.string.nav_profile, R.drawable.ic_person, R.drawable.ic_person_fill1),
}

/** The app after login; each tab keeps its own back stack and state when switching (spec 001 AC11). */
@Composable
fun Shell(
    dashboard: @Composable () -> Unit,
    activities: @Composable () -> Unit,
    profile: @Composable () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { item ->
                    val selected = destination?.hierarchy?.any { it.hasRoute(item.routeClass) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route) {
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
        NavHost(
            navController = navController,
            startDestination = DashboardRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<DashboardRoute> { Box(Modifier.fillMaxSize()) { dashboard() } }
            composable<ActivitiesRoute> { Box(Modifier.fillMaxSize()) { activities() } }
            composable<ProfileRoute> { Box(Modifier.fillMaxSize()) { profile() } }
        }
    }
}
