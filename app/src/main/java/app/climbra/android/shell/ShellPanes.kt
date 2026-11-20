package app.climbra.android.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import app.climbra.android.ui.common.ClearPaneViewModels
import app.climbra.android.ui.common.LocalPaneItem
import app.climbra.android.ui.common.PaneItem
import app.climbra.android.ui.common.PaneViewModels

/** The window widths of spec 019: the rail, two panes for the training log and the coach, and for the calendar. */
val RAIL_WIDTH = 600.dp
val TWO_PANES_WIDTH = 840.dp
val CALENDAR_TWO_PANES_WIDTH = 1200.dp

/** The list pane's width of the training log and the coach; the calendar's detail pane's. */
internal val LIST_PANE_WIDTH = 380.dp
internal val CALENDAR_DETAIL_WIDTH = 440.dp

/** The window width from which the tab shows its list and its detail side by side; `null`: never (spec 019). */
val TopLevelDestination.twoPanesFrom: Dp?
    get() = when (this) {
        TopLevelDestination.ACTIVITIES, TopLevelDestination.COACH -> TWO_PANES_WIDTH
        TopLevelDestination.CALENDAR -> CALENDAR_TWO_PANES_WIDTH
        else -> null
    }

/** The tabs showing two panes in a window [width] wide. */
internal fun twoPaneTabs(width: Dp): Set<TopLevelDestination> =
    TopLevelDestination.entries.filter { tab -> tab.twoPanesFrom?.let { width >= it } == true }.toSet()

/** Where the start screen of a tab keeps the item of its detail pane, as [PaneItem.encode]. */
internal const val PANE_ITEM = "paneItem"

internal fun NavController.setPaneItem(tab: TopLevelDestination, item: PaneItem?) {
    runCatching { getBackStackEntry(tab.startRoute) }.getOrNull()?.savedStateHandle?.set(PANE_ITEM, item?.encode())
}

/** The start screen of a tab that can show two panes, and whether it does now. */
internal class PaneHost(
    val entry: NavBackStackEntry,
    val twoPanes: Boolean,
    val nav: NavController,
    val navigator: ShellNavigator,
)

internal class Placeholder(@param:StringRes val text: Int, @param:DrawableRes val icon: Int)

/**
 * The list of a tab and, in a window wide enough, the item chosen in it next to it (spec 019). The item is kept by
 * the tab's start screen, so it outlives a change of the window: with one pane again it becomes a screen of its own
 * over the list ([route]). Back from the two panes leaves the tab, as from its list.
 */
@Composable
internal fun Panes(
    host: PaneHost,
    placeholder: Placeholder,
    route: (PaneItem) -> Any?,
    list: @Composable () -> Unit,
    detail: @Composable (PaneItem, ShellNavigator) -> Unit,
    listWidth: Dp? = LIST_PANE_WIDTH,
    detailWidth: Dp? = null,
) {
    val handle = host.entry.savedStateHandle
    val encoded by remember(handle) { handle.getStateFlow<String?>(PANE_ITEM, null) }.collectAsState()
    val item = encoded?.let(PaneItem::decode)
    if (!host.twoPanes) {
        LaunchedEffect(item) {
            if (item != null) {
                handle[PANE_ITEM] = null
                route(item)?.let { host.nav.navigate(it) }
            }
        }
        ClearPaneViewModels()
        Box(Modifier.fillMaxSize()) { list() }
        return
    }
    val paneNavigator = remember(host.navigator, handle) { host.navigator.forPane { handle[PANE_ITEM] = null } }
    Row(Modifier.fillMaxSize().testTag("two-panes")) {
        Box(listWidth?.let { Modifier.width(it).fillMaxHeight() } ?: Modifier.weight(1f).fillMaxHeight()) {
            CompositionLocalProvider(LocalPaneItem provides item) { list() }
        }
        VerticalDivider()
        Box((detailWidth?.let { Modifier.width(it).fillMaxHeight() } ?: Modifier.weight(1f).fillMaxHeight()).testTag("detail-pane")) {
            if (item == null) {
                ClearPaneViewModels()
                PanePlaceholder(placeholder)
            } else {
                key(encoded) { PaneViewModels(item) { detail(item, paneNavigator) } }
            }
        }
    }
}

@Composable
private fun PanePlaceholder(placeholder: Placeholder) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("pane-placeholder"),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(placeholder.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            stringResource(placeholder.text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A ride, workout or conversation on a tab's stack. In a window showing the tab's two panes (made wider, or opened
 * so before) it moves into the detail pane next to the list.
 */
@Composable
internal fun DetailScreen(
    tab: TopLevelDestination,
    twoPanes: Boolean,
    nav: NavController,
    item: PaneItem,
    content: @Composable () -> Unit,
) {
    if (twoPanes) {
        LaunchedEffect(Unit) {
            nav.popBackStack(tab.startRoute, inclusive = false)
            nav.setPaneItem(tab, item)
        }
    } else {
        Box(Modifier.fillMaxSize()) { content() }
    }
}
