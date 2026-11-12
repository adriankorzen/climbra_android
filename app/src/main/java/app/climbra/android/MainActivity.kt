package app.climbra.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.app.AppViewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { appViewModel.state.value == SessionState.Loading }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}
