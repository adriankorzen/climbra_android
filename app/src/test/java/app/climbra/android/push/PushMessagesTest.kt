package app.climbra.android.push

import app.climbra.android.data.api.AccountResponse
import app.climbra.android.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 020 AC3, AC5, AC6: which pushes show a notification, and what the foreground does instead. */
class PushMessagesTest {

    private val account = AccountResponse(id = 7, email = "anna@example.com", role = "USER", termsAccepted = true)
    private val session = MutableStateFlow<SessionState>(SessionState.LoggedIn(account))
    private val foreground = MutableStateFlow(false)
    private var largeImport = false
    private val shown = mutableListOf<PushMessage>()
    private var coachChanges = 0
    private var rideChanges = 0

    private val messages = PushMessages(
        session = session,
        foreground = foreground,
        largeImport = { largeImport },
        notifier = object : PushNotifier {
            override fun show(message: PushMessage) {
                shown += message
            }
        },
        coachChanged = { coachChanges++ },
        ridesChanged = { rideChanges++ },
    )

    private fun data(type: String, vararg ids: Pair<String, String>, userId: String = "7") =
        mapOf("type" to type, "userId" to userId) + ids

    @Test
    fun theMessagesAreParsed() {
        assertEquals(7L to PushMessage.Coach(12, answered = true), PushMessage.parse(data("COACH_ANSWERED", "conversationId" to "12")))
        assertEquals(7L to PushMessage.Coach(12, answered = false), PushMessage.parse(data("COACH_FAILED", "conversationId" to "12")))
        assertEquals(7L to PushMessage.Import(3, finished = true), PushMessage.parse(data("IMPORT_FINISHED", "jobId" to "3")))
        assertEquals(7L to PushMessage.Import(3, finished = false), PushMessage.parse(data("IMPORT_FAILED", "jobId" to "3")))
        assertNull(PushMessage.parse(data("COACH_ANSWERED")))
        assertNull(PushMessage.parse(mapOf("type" to "COACH_ANSWERED", "conversationId" to "1")))
    }

    // AC4 (background): the four types show
    @Test
    fun inTheBackgroundTheyShow() {
        messages.received(data("COACH_ANSWERED", "conversationId" to "12"))
        messages.received(data("COACH_FAILED", "conversationId" to "13"))
        messages.received(data("IMPORT_FINISHED", "jobId" to "3"))
        messages.received(data("IMPORT_FAILED", "jobId" to "4"))

        assertEquals(
            listOf(PushMessage.Coach(12, true), PushMessage.Coach(13, false), PushMessage.Import(3, true), PushMessage.Import(4, false)),
            shown,
        )
    }

    // AC3
    @Test
    fun anotherAccountNobodyOrAnUnknownTypeShowNothing() {
        messages.received(data("COACH_ANSWERED", "conversationId" to "12", userId = "8"))
        messages.received(data("SOMETHING_NEW", "conversationId" to "12"))
        session.value = SessionState.LoggedOut()
        messages.received(data("COACH_ANSWERED", "conversationId" to "12"))

        assertTrue(shown.isEmpty())
        assertEquals(0, coachChanges)
    }

    // AC5
    @Test
    fun inTheForegroundTheScreensReloadInstead() {
        foreground.value = true
        messages.received(data("COACH_ANSWERED", "conversationId" to "12"))
        messages.received(data("IMPORT_FINISHED", "jobId" to "3"))

        assertTrue(shown.isEmpty())
        assertEquals(1, coachChanges)
        assertEquals(1, rideChanges)
    }

    // AC6
    @Test
    fun duringALargeImportItsPushesShowNothing() {
        largeImport = true
        messages.received(data("IMPORT_FINISHED", "jobId" to "3"))
        messages.received(data("COACH_ANSWERED", "conversationId" to "12"))

        assertEquals(listOf<PushMessage>(PushMessage.Coach(12, true)), shown)
    }

    // AC8
    @Test
    fun thePermissionIsAskedOnlyWhenItHelps() {
        assertTrue(shouldAskForNotifications(pushEnabled = true, sdk = 33, granted = false, asked = false))
        assertTrue(!shouldAskForNotifications(pushEnabled = false, sdk = 33, granted = false, asked = false))
        assertTrue(!shouldAskForNotifications(pushEnabled = true, sdk = 32, granted = false, asked = false))
        assertTrue(!shouldAskForNotifications(pushEnabled = true, sdk = 33, granted = true, asked = false))
        assertTrue(!shouldAskForNotifications(pushEnabled = true, sdk = 33, granted = false, asked = true))
    }
}
