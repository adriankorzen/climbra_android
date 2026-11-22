package app.climbra.android.push

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The notifications are allowed for the app (the permission on Android 13+, and not turned off). */
fun notificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

/** The system's notification settings of the app: the channels turn the types on or off (spec 020). */
fun openAppNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private val Context.pushStore: DataStore<Preferences> by preferencesDataStore(name = "push")

/** Whether the permission was asked after a coach request already (spec 020): once only. */
interface PushPermissionAsked {
    suspend fun asked(): Boolean
    suspend fun markAsked()
}

class DataStorePushPermissionAsked(private val context: Context) : PushPermissionAsked {
    private val key = booleanPreferencesKey("asked_after_coach")
    private val value: Flow<Boolean> = context.pushStore.data.map { it[key] == true }

    override suspend fun asked(): Boolean = value.first()

    override suspend fun markAsked() {
        context.pushStore.edit { it[key] = true }
    }
}

/** Asking is worth it: Firebase is set up, Android 13+, not allowed and not asked after a coach request before. */
fun shouldAskForNotifications(pushEnabled: Boolean, sdk: Int, granted: Boolean, asked: Boolean): Boolean =
    pushEnabled && sdk >= Build.VERSION_CODES.TIRAMISU && !granted && !asked

/**
 * Asks for the notification permission after the first coach request the app accepted (spec 020), when the
 * answer will come in the background; refused, it is not asked again (the profile's row opens the settings).
 */
@Composable
fun AskForNotificationsAfterCoach(pushEnabled: Boolean, accepted: Flow<Unit>, asked: PushPermissionAsked) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val currentLauncher = rememberUpdatedState(launcher)
    LaunchedEffect(accepted, pushEnabled) {
        if (!pushEnabled) return@LaunchedEffect
        accepted.collect {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (shouldAskForNotifications(pushEnabled, Build.VERSION.SDK_INT, granted, asked.asked())) {
                asked.markAsked()
                currentLauncher.value.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
