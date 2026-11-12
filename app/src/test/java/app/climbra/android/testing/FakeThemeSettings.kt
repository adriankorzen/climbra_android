package app.climbra.android.testing

import app.climbra.android.ui.theme.ThemeMode
import app.climbra.android.ui.theme.ThemeSettings
import kotlinx.coroutines.flow.MutableStateFlow

class FakeThemeSettings(initial: ThemeMode = ThemeMode.SYSTEM) : ThemeSettings {
    override val mode = MutableStateFlow(initial)

    override suspend fun setMode(mode: ThemeMode) {
        this.mode.value = mode
    }
}
