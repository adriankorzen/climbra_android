package app.climbra.android.session

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** The UI language: the account's once logged in, the device's before (spec 001). */
interface AppLocales {
    /** [language] is the account's `pl` or `en`; anything else keeps the device language. */
    fun useAccountLanguage(language: String?)

    fun useDeviceLanguage()
}

/** Per-app language through AppCompat; it recreates the activity when the language changes. */
class AppCompatLocales : AppLocales {

    private val main = Handler(Looper.getMainLooper())

    override fun useAccountLanguage(language: String?) {
        if (language in SUPPORTED) {
            set(LocaleListCompat.forLanguageTags(language))
        }
    }

    override fun useDeviceLanguage() {
        set(LocaleListCompat.getEmptyLocaleList())
    }

    private fun set(locales: LocaleListCompat) {
        main.post {
            if (AppCompatDelegate.getApplicationLocales() != locales) {
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
    }

    private companion object {
        val SUPPORTED = setOf("pl", "en")
    }
}
