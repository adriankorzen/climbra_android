package app.climbra.android.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.climbra.android.MainActivity
import app.climbra.android.R

/**
 * The notifications of the pushes (spec 020): two channels, one notification per conversation or import (a newer
 * one replaces it), the tap opens the app at the target.
 */
class AndroidPushNotifier(private val context: Context) : PushNotifier {

    init {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_COACH, context.getString(R.string.push_channel_coach), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_IMPORTS, context.getString(R.string.push_channel_imports), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    override fun show(message: PushMessage) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val content = content(message)
        val notification = NotificationCompat.Builder(context, content.channel)
            .setSmallIcon(content.icon)
            .setContentTitle(context.getString(content.title))
            .setContentText(context.getString(content.text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(content.text)))
            .setAutoCancel(true)
            .setContentIntent(open(content.tag, content.id, content.target))
            .build()
        NotificationManagerCompat.from(context).notify(content.tag, content.id, notification)
    }

    private fun open(tag: String, id: Int, target: PushTarget): PendingIntent = PendingIntent.getActivity(
        context,
        (tag.hashCode() * 31) + id,
        target.intent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What a message shows; also read by the tests. */
    data class Content(val channel: String, val tag: String, val id: Int, val title: Int, val text: Int, val icon: Int, val target: PushTarget)

    companion object {
        const val CHANNEL_COACH = "coach"
        const val CHANNEL_IMPORTS = "imports"
        const val TAG_COACH = "coach"
        const val TAG_IMPORT = "import"
        const val ACTION_OPEN = "app.climbra.android.action.OPEN_PUSH"
        private const val EXTRA_CONVERSATION = "push_conversation"
        private const val EXTRA_TRAINING_LOG = "push_training_log"

        fun content(message: PushMessage): Content = when (message) {
            is PushMessage.Coach -> Content(
                channel = CHANNEL_COACH,
                tag = TAG_COACH,
                id = message.conversationId.toInt(),
                title = if (message.answered) R.string.push_coach_answered_title else R.string.push_coach_failed_title,
                text = if (message.answered) R.string.push_coach_answered_text else R.string.push_coach_failed_text,
                icon = R.drawable.ic_forum,
                target = PushTarget.Conversation(message.conversationId),
            )
            is PushMessage.Import -> Content(
                channel = CHANNEL_IMPORTS,
                tag = TAG_IMPORT,
                id = message.jobId.toInt(),
                title = if (message.finished) R.string.push_import_finished_title else R.string.push_import_failed_title,
                text = if (message.finished) R.string.push_import_finished_text else R.string.push_import_failed_text,
                icon = R.drawable.ic_upload_file,
                target = PushTarget.TrainingLog,
            )
        }

        fun PushTarget.intent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                when (this@intent) {
                    is PushTarget.Conversation -> putExtra(EXTRA_CONVERSATION, id)
                    PushTarget.TrainingLog -> putExtra(EXTRA_TRAINING_LOG, true)
                }
            }

        /** The target of a tapped notification, `null` for any other intent. */
        fun Intent?.pushTarget(): PushTarget? {
            if (this?.action != ACTION_OPEN) return null
            return when {
                hasExtra(EXTRA_CONVERSATION) -> PushTarget.Conversation(getLongExtra(EXTRA_CONVERSATION, 0))
                getBooleanExtra(EXTRA_TRAINING_LOG, false) -> PushTarget.TrainingLog
                else -> null
            }
        }

        /** Opening a conversation clears its notification (spec 020). */
        fun clearConversation(context: Context, conversationId: Long) {
            NotificationManagerCompat.from(context).cancel(TAG_COACH, conversationId.toInt())
        }
    }
}
