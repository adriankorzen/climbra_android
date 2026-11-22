package app.climbra.android.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.app.NotificationCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.R
import app.climbra.android.push.AndroidPushNotifier.Companion.intent
import app.climbra.android.push.AndroidPushNotifier.Companion.pushTarget
import app.climbra.android.shell.ArgsViewModel
import app.climbra.android.shell.Shell
import app.climbra.android.ui.theme.ClimbraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Spec 020 AC4, AC7: the notifications' channels and texts, one per item, and where a tap leads. */
@RunWith(AndroidJUnit4::class)
class PushNotificationsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun notifier(granted: Boolean = true): AndroidPushNotifier {
        if (granted) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        else shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        return AndroidPushNotifier(context)
    }

    private fun title(tag: String, id: Int) =
        shadowOf(manager).getNotification(tag, id)?.extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()

    // AC4
    @Test
    fun theTypesShowTheirTextsOnTheirChannels() {
        val notifier = notifier()
        assertEquals(setOf("coach", "imports"), manager.notificationChannels.map { it.id }.toSet())

        notifier.show(PushMessage.Coach(12, answered = true))
        notifier.show(PushMessage.Import(3, finished = false))

        assertEquals("The coach has answered", title("coach", 12))
        assertEquals("coach", shadowOf(manager).getNotification("coach", 12).channelId)
        assertEquals("The import failed", title("import", 3))
        assertEquals("imports", shadowOf(manager).getNotification("import", 3).channelId)

        // A newer one replaces the conversation's
        notifier.show(PushMessage.Coach(12, answered = false))
        assertEquals("The coach could not answer", title("coach", 12))
        assertEquals(2, shadowOf(manager).allNotifications.size)

        AndroidPushNotifier.clearConversation(context, 12)
        assertNull(shadowOf(manager).getNotification("coach", 12))
    }

    @Test
    fun theContentOfEveryType() {
        val finished = AndroidPushNotifier.content(PushMessage.Import(4, finished = true))
        assertEquals(R.string.push_import_finished_title, finished.title)
        assertEquals(R.string.push_import_finished_text, finished.text)
        assertEquals(PushTarget.TrainingLog, finished.target)
        val failed = AndroidPushNotifier.content(PushMessage.Coach(5, answered = false))
        assertEquals(R.string.push_coach_failed_text, failed.text)
        assertEquals(PushTarget.Conversation(5), failed.target)
    }

    @Test
    fun withoutThePermissionNothingShows() {
        notifier(granted = false).show(PushMessage.Coach(12, answered = true))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    // AC7: the tap's intent carries the target
    @Test
    fun theTapCarriesTheTarget() {
        val app: Context = context
        assertEquals(PushTarget.Conversation(9), PushTarget.Conversation(9).intent(app).pushTarget())
        assertEquals(PushTarget.TrainingLog, PushTarget.TrainingLog.intent(app).pushTarget())
        assertNull(android.content.Intent(android.content.Intent.ACTION_VIEW).pushTarget())

        val targets = PushTargets()
        targets.offer(PushTarget.Conversation(1))
        targets.offer(PushTarget.TrainingLog)
        targets.taken(PushTarget.Conversation(1))
        assertEquals(PushTarget.TrainingLog, targets.pending.value)
    }

    // AC7: the shell opens the conversation on the Coach tab, or the training log
    @Test
    fun theShellOpensTheTarget() {
        var target by mutableStateOf<PushTarget?>(null)
        val taken = mutableListOf<PushTarget>()
        compose.setContent {
            ClimbraTheme(darkTheme = false) {
                Shell(
                    dashboard = { Text("dashboard", Modifier.testTag("dashboard-screen")) },
                    activities = { Text("training log", Modifier.testTag("activities-screen")) },
                    profile = {},
                    coach = { Text("coach list", Modifier.testTag("coach-screen")) },
                    conversation = {
                        val args: ArgsViewModel = viewModel()
                        Text("conversation ${args.id}", Modifier.testTag("conversation-${args.id}"))
                    },
                    pushTarget = target,
                    onPushTargetTaken = { taken += it },
                )
            }
        }
        compose.onNodeWithTag("dashboard-screen").assertIsDisplayed()

        target = PushTarget.Conversation(4)
        compose.onNodeWithTag("conversation-4").assertIsDisplayed()
        target = PushTarget.TrainingLog
        compose.onNodeWithTag("activities-screen").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(PushTarget.Conversation(4), PushTarget.TrainingLog), taken) }
    }
}
