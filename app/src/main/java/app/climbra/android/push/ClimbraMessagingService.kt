package app.climbra.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** FCM's entry point (spec 020): new tokens and the backend's data messages. */
@AndroidEntryPoint
class ClimbraMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var tokens: PushTokens

    @Inject
    lateinit var messages: PushMessages

    override fun onNewToken(token: String) {
        tokens.newToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        messages.received(message.data)
    }
}
