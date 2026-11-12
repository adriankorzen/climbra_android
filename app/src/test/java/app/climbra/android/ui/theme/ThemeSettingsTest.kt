package app.climbra.android.ui.theme

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 004 AC11: the theme choice is kept on the device, like the system's until one is picked. */
@RunWith(AndroidJUnit4::class)
class ThemeSettingsTest {

    @Test
    fun theChoiceIsStoredAndReadAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = DataStoreThemeSettings(context)
        assertEquals(ThemeMode.SYSTEM, settings.mode.first())

        settings.setMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, settings.mode.first())
        // What a later start reads
        assertEquals(ThemeMode.DARK, DataStoreThemeSettings(context).mode.first())
        settings.setMode(ThemeMode.SYSTEM)
    }
}
