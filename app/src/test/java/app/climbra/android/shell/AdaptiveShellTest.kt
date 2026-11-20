package app.climbra.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.ui.common.LocalInPane
import app.climbra.android.ui.common.LocalPaneItem
import app.climbra.android.ui.theme.ClimbraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The detail screens' ViewModels read their item from the arguments, as from their route. */
class ArgsViewModel(savedState: SavedStateHandle) : ViewModel() {
    val id: Long = checkNotNull(savedState.get<Long>("id"))
    val date: String? = savedState.get<String>("date")
    val tab: String? = savedState.get<String>("tab")
}

/**
 * Spec 019 against the real shell. The window is 1280 dp wide (two panes everywhere); [width] narrows what the shell
 * is told, as rotating or folding the device would.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp")
class AdaptiveShellTest {

    @get:Rule
    val compose = createComposeRule()

    private var width by mutableIntStateOf(1280)

    private fun shell() = compose.setContent {
        val base = LocalConfiguration.current
        val configuration = Configuration(base).apply { screenWidthDp = width }
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            ClimbraTheme(darkTheme = false) {
                Shell(
                    dashboard = { nav -> Column {
                        Open("dashboard-ride") { nav.openRide(7) }
                        Open("dashboard-conversation") { nav.openConversation(5) }
                    } },
                    activities = { nav -> Column {
                        Open("ride-3") { nav.openRide(3) }
                        Text("list-selected ${LocalPaneItem.current}", Modifier.testTag("activities-list"))
                    } },
                    profile = { Text("profile") },
                    calendar = { nav -> Column { Open("workout-1") { nav.openWorkout(1, "2026-11-10") } } },
                    workout = { nav -> Column {
                        val args: ArgsViewModel = viewModel()
                        Text("workout ${args.id} ${args.date}", Modifier.testTag("workout-screen"))
                        Open("workout-ride") { nav.openRide(11) }
                    } },
                    ride = { nav -> Column {
                        val args: ArgsViewModel = viewModel()
                        Text("ride ${args.id} pane ${LocalInPane.current}", Modifier.testTag("ride-screen"))
                        // A deleted ride goes back, as RideDetailRoute's onDeleted
                        Open("ride-delete") { nav.back() }
                    } },
                    coach = { nav -> Column {
                        Open("conversation-4") { nav.openConversation(4) }
                        Open("training-block") { nav.newTrainingBlock() }
                    } },
                    conversation = { nav -> Column {
                        val args: ArgsViewModel = viewModel()
                        Text("conversation ${args.id}", Modifier.testTag("conversation-screen"))
                    } },
                    trainingBlock = { Text("block", Modifier.testTag("block-screen")) },
                )
            }
        }
    }

    @Composable
    private fun Open(name: String, open: () -> Unit) {
        Button(onClick = open, modifier = Modifier.testTag("open-$name")) { Text(name) }
    }

    private fun text(tag: String, text: String) = compose.onNodeWithTag(tag).assert(
        androidx.compose.ui.test.hasText(text, substring = true),
    )

    // AC1
    @Test
    fun aCompactWindowShowsTheBarAndWiderOnesTheRail() {
        width = 411
        shell()
        compose.onNodeWithTag("shell-bar").assertExists()

        width = 700
        compose.onNodeWithTag("shell-rail").assertExists()
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("nav-activities").assertIsSelected()
        // Medium: one pane, a ride is a screen of its own and the selected destination returns to the list
        compose.onNodeWithTag("open-ride-3").performClick()
        text("ride-screen", "ride 3 pane false")
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("open-ride-3").assertIsDisplayed()
    }

    // AC2, AC1 (the selected destination again empties the pane)
    @Test
    fun theTrainingLogShowsTheChosenRideNextToTheList() {
        shell()
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("pane-placeholder").assertIsDisplayed()
        compose.onNodeWithText("Choose a ride").assertIsDisplayed()

        compose.onNodeWithTag("open-ride-3").performClick()
        text("ride-screen", "ride 3 pane true")
        compose.onNodeWithTag("open-ride-3").assertIsDisplayed()
        text("activities-list", "Ride(id=3)")

        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("pane-placeholder").assertIsDisplayed()
    }

    // AC3
    @Test
    fun theCoachShowsAConversationNextToItsListAlsoFromAnotherTab() {
        shell()
        compose.onNodeWithTag("open-dashboard-conversation").performClick()
        compose.onNodeWithTag("nav-coach").assertIsSelected()
        text("conversation-screen", "conversation 5")
        compose.onNodeWithTag("open-conversation-4").assertIsDisplayed()

        compose.onNodeWithTag("open-conversation-4").performClick()
        text("conversation-screen", "conversation 4")
    }

    // AC4
    @Test
    fun theCalendarShowsTwoPanesFromTwelveHundred() {
        width = 1000
        shell()
        compose.onNodeWithTag("nav-calendar").performClick()
        compose.onNodeWithTag("two-panes").assertDoesNotExist()
        compose.onNodeWithTag("open-workout-1").performClick()
        text("workout-screen", "workout 1 2026-11-10")
        compose.onNodeWithTag("open-workout-1").assertDoesNotExist()

        width = 1280
        compose.onNodeWithTag("two-panes").assertExists()
        compose.onNodeWithTag("open-workout-1").assertIsDisplayed()
        text("workout-screen", "workout 1 2026-11-10")

        // A ride opened from the workout takes its place in the pane
        compose.onNodeWithTag("open-workout-ride").performClick()
        text("ride-screen", "ride 11 pane true")
        compose.onNodeWithTag("open-workout-1").assertIsDisplayed()
    }

    // AC5, AC6
    @Test
    fun resizingKeepsTheChosenItem() {
        shell()
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("open-ride-3").performClick()

        width = 700
        text("ride-screen", "ride 3 pane false")
        compose.onNodeWithTag("open-ride-3").assertDoesNotExist()

        width = 1280
        text("ride-screen", "ride 3 pane true")
        compose.onNodeWithTag("open-ride-3").assertIsDisplayed()

        // One pane: Back from the ride returns to the list
        width = 700
        compose.onNodeWithTag("open-ride-delete").performClick()
        compose.onNodeWithTag("open-ride-3").assertIsDisplayed()
        compose.onNodeWithTag("ride-screen").assertDoesNotExist()
    }

    // AC7
    @Test
    fun deeperScreensCoverBothPanesAndADeletedItemLeavesThePane() {
        shell()
        compose.onNodeWithTag("nav-coach").performClick()
        compose.onNodeWithTag("open-training-block").performClick()
        compose.onNodeWithTag("block-screen").assertIsDisplayed()
        compose.onNodeWithTag("two-panes").assertDoesNotExist()

        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("open-ride-3").performClick()
        compose.onNodeWithTag("open-ride-delete").performClick()
        compose.onNodeWithTag("pane-placeholder").assertIsDisplayed()
        compose.onNodeWithTag("open-ride-3").assertIsDisplayed()
    }

    // A ride from the dashboard keeps its own screen over the dashboard (spec 003)
    @Test
    fun aRideFromTheDashboardStaysAScreenOfItsOwn() {
        shell()
        compose.onNodeWithTag("open-dashboard-ride").performClick()
        text("ride-screen", "ride 7 pane false")
        compose.onNodeWithTag("nav-dashboard").assertIsSelected()
    }
}
