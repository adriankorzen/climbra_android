package app.climbra.android.auth

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A link of the account emails opened in the app (spec 021); a missing token is an empty one (the invalid state). */
sealed interface EmailLink {
    data class Verify(val token: String) : EmailLink

    data class Reset(val token: String) : EmailLink

    data object Login : EmailLink

    data object ForgotPassword : EmailLink
}

/** The host of the App Links (the manifest's intent filter, cyclingapp spec 057). */
const val EMAIL_LINK_HOST = "climbra.app"

/** The link in [uri], or `null` when it is not one of the email links. */
fun emailLink(uri: Uri?): EmailLink? {
    if (uri == null || uri.scheme != "https" || uri.host != EMAIL_LINK_HOST) return null
    val token = { runCatching { uri.getQueryParameter("token") }.getOrNull()?.trim().orEmpty() }
    return when (uri.path?.trimEnd('/')) {
        "/verify-email" -> EmailLink.Verify(token())
        "/reset-password" -> EmailLink.Reset(token())
        "/login" -> EmailLink.Login
        "/forgot-password" -> EmailLink.ForgotPassword
        else -> null
    }
}

fun Intent?.emailLink(): EmailLink? = if (this?.action == Intent.ACTION_VIEW) emailLink(data) else null

/**
 * The email link the app was opened with, until the screens shown take it (app-scoped, like `SharedRideFiles`). A
 * newer link replaces one not taken yet.
 */
class EmailLinks {
    private val mutablePending = MutableStateFlow<EmailLink?>(null)
    val pending: StateFlow<EmailLink?> = mutablePending.asStateFlow()

    fun offer(link: EmailLink) {
        mutablePending.value = link
    }

    /** Takes [link] if it is still the pending one. */
    fun taken(link: EmailLink) {
        mutablePending.compareAndSet(link, null)
    }
}
