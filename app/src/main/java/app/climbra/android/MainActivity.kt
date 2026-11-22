package app.climbra.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.activities.ImportService
import app.climbra.android.activities.SharedRideFiles
import app.climbra.android.activities.sharedFiles
import app.climbra.android.app.AppViewModel
import app.climbra.android.auth.EmailLinks
import app.climbra.android.auth.emailLink
import app.climbra.android.push.AndroidPushNotifier.Companion.pushTarget
import app.climbra.android.push.PushTargets
import app.climbra.android.coach.RideFileSource
import app.climbra.android.garmin.GarminConnectFlow
import app.climbra.android.garmin.garminConnectState
import app.climbra.android.app.ClimbraApp
import app.climbra.android.session.SessionState
import app.climbra.android.ui.theme.ClimbraTheme
import app.climbra.android.ui.theme.ThemeMode
import app.climbra.android.ui.theme.ThemeSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** AppCompat for the per-app language on Android 10-12. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val appViewModel: AppViewModel by viewModels()

    @Inject
    lateinit var themeSettings: ThemeSettings

    @Inject
    lateinit var sharedRideFiles: SharedRideFiles

    @Inject
    lateinit var rideFiles: RideFileSource

    @Inject
    lateinit var garminConnect: GarminConnectFlow

    @Inject
    lateinit var emailLinks: EmailLinks

    @Inject
    lateinit var pushTargets: PushTargets

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { appViewModel.state.value == SessionState.Loading }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ride files shared from another app (spec 009); not again after a configuration change
        if (savedInstanceState == null) receive(intent)
        setContent {
            // The rider's choice in the profile (spec 004), the system's otherwise
            val mode by themeSettings.mode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            ClimbraTheme(darkTheme = dark) {
                ClimbraApp(appViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        // The end of Garmin Connect's consent (spec 016)
        intent.garminConnectState()?.let { return garminConnect.returned(it) }
        // The links of the account emails (spec 021)
        intent.emailLink()?.let { return emailLinks.offer(it) }
        // A tapped notification (spec 020)
        intent.pushTarget()?.let { return pushTargets.offer(it) }
        if (intent?.action == ImportService.ACTION_OPEN_IMPORT) sharedRideFiles.openScreen()
        else sharedRideFiles.offer(rideFiles.sharedFiles(intent))
    }
}
