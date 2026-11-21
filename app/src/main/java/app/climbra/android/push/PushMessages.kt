package app.climbra.android.push

import app.climbra.android.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a push of the backend tells (cyclingapp spec 056): only the type and ids. */
sealed interface PushMessage {
    data class Coach(val conversationId: Long, val answered: Boolean) : PushMessage

    data class Import(val jobId: Long, val finished: Boolean) : PushMessage

    companion object {
        /** The message and the account it is for; `null` for an unknown type (a newer backend) or missing ids. */
        fun parse(data: Map<String, String>): Pair<Long, PushMessage>? {
            val userId = data["userId"]?.toLongOrNull() ?: return null
            val message = when (data["type"]) {
                "COACH_ANSWERED" -> data["conversationId"]?.toLongOrNull()?.let { Coach(it, answered = true) }
                "COACH_FAILED" -> data["conversationId"]?.toLongOrNull()?.let { Coach(it, answered = false) }
                "IMPORT_FINISHED" -> data["jobId"]?.toLongOrNull()?.let { Import(it, finished = true) }
                "IMPORT_FAILED" -> data["jobId"]?.toLongOrNull()?.let { Import(it, finished = false) }
                else -> null
            } ?: return null
            return userId to message
        }
    }
}

/** Shows a push as a notification; [AndroidPushNotifier] in the app. */
interface PushNotifier {
    fun show(message: PushMessage)
}

/**
 * Handles the pushes (spec 020): one for another account or nobody is dropped; in the foreground the screens reload
 * and the snackbars tell as before; in the background a notification. During a large import (spec 014) its own
 * notification tells the end, so the import pushes are dropped.
 */
class PushMessages(
    private val session: StateFlow<SessionState>,
    private val foreground: StateFlow<Boolean>,
    private val largeImport: () -> Boolean,
    private val notifier: PushNotifier,
    private val coachChanged: () -> Unit,
    private val ridesChanged: () -> Unit,
) {
    fun received(data: Map<String, String>) {
        val (userId, message) = PushMessage.parse(data) ?: return
        if (session.value.accountId() != userId) return
        if (message is PushMessage.Import && largeImport()) return
        if (foreground.value) {
            when (message) {
                is PushMessage.Coach -> coachChanged()
                is PushMessage.Import -> ridesChanged()
            }
            return
        }
        notifier.show(message)
    }
}

/** Where a tapped notification leads (spec 020). */
sealed interface PushTarget {
    data class Conversation(val id: Long) : PushTarget

    data object TrainingLog : PushTarget
}

/** The target of a tapped notification, until the shell opens it; logged out it is dropped. */
class PushTargets {
    private val mutablePending = MutableStateFlow<PushTarget?>(null)
    val pending: StateFlow<PushTarget?> = mutablePending.asStateFlow()

    fun offer(target: PushTarget) {
        mutablePending.value = target
    }

    fun taken(target: PushTarget) {
        mutablePending.compareAndSet(target, null)
    }
}
