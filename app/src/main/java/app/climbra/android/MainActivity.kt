package app.climbra.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.climbra.android.app.AppViewModel
import app.climbra.android.app.ClimbraApp
import app.climbra.android.session.SessionState
import app.climbra.android.ui.theme.ClimbraTheme
import dagger.hilt.android.AndroidEntryPoint

/** AppCompat for the per-app language on Android 10-12. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { appViewModel.state.value == SessionState.Loading }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClimbraTheme {
                ClimbraApp(appViewModel)
            }
        }
    }
}
