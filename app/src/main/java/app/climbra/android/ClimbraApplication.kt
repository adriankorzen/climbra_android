package app.climbra.android

import android.app.Application
import app.climbra.android.push.PushTokens
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ClimbraApplication : Application() {

    /** Created at start, so the push token follows the session from the first login (spec 020). */
    @Inject
    lateinit var pushTokens: PushTokens

    override fun onCreate() {
        // Firebase from the build's settings instead of google-services.json (spec 020); none without them
        if (BuildConfig.PUSH_ENABLED && FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        }
        super.onCreate()
    }
}
