package app.climbra.android.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** Opens a page of the web app (registration, password reset, legal documents) in a Custom Tab. */
fun openWebPage(context: Context, url: String) {
    val uri = url.toUri()
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignored: ActivityNotFoundException) {
            // No browser at all: nothing to open the page with
        }
    }
}
