package app.climbra.android.auth

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a request that sends an email gets for its `captchaToken` (cyclingapp spec 032, spec 013). */
sealed interface CaptchaResult {
    /** The backend asks for no captcha (no site key): no token. */
    data object Off : CaptchaResult

    data class Token(val value: String) : CaptchaResult

    /** The check could not load or failed: nothing is sent. */
    data object Unavailable : CaptchaResult
}

/** A fresh token for one request: tokens work once. */
fun interface CaptchaSource {
    suspend fun token(): CaptchaResult
}

/** The token of the request, `null` when the backend asks for none. */
val CaptchaResult.value: String? get() = (this as? CaptchaResult.Token)?.value

/**
 * Cloudflare Turnstile in a `WebView` (spec 013), as the web's `TurnstileWidget`: invisible until a token is asked for,
 * shown only when Cloudflare needs an interaction. [siteKey] `null` means the check is off.
 */
class TurnstileController(
    private val siteKey: String?,
    private val baseUrl: String,
    private val language: String,
    private val dark: Boolean,
) : CaptchaSource {

    /** Cloudflare asks the rider for an interaction: the widget is shown. */
    var interactive by mutableStateOf(false)
        private set

    private var webView: WebView? = null
    private var ready = CompletableDeferred<Boolean>()
    private var pending: CompletableDeferred<String?>? = null

    override suspend fun token(): CaptchaResult {
        val key = siteKey ?: return CaptchaResult.Off
        val view = webView ?: return CaptchaResult.Unavailable
        val loaded = withTimeoutOrNull(SCRIPT_TIMEOUT_MILLIS) { ready.await() } == true
        if (!loaded) {
            // The next attempt loads the page again, e.g. after the network came back
            withContext(Dispatchers.Main) { load(view) }
            return CaptchaResult.Unavailable
        }
        val answer = CompletableDeferred<String?>()
        pending = answer
        withContext(Dispatchers.Main) {
            view.evaluateJavascript("climbraToken(${quoted(key)}, ${quoted(language)}, '${if (dark) "dark" else "light"}')", null)
        }
        val token = withTimeoutOrNull(TOKEN_TIMEOUT_MILLIS) { answer.await() }
        pending = null
        return token?.let { CaptchaResult.Token(it) } ?: CaptchaResult.Unavailable
    }

    @SuppressLint("SetJavaScriptEnabled")
    internal fun attach(view: WebView) {
        webView = view
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        // The page stays where it is: nothing navigates the widget's frame away
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame
        }
        view.addJavascriptInterface(Bridge(), "Climbra")
        if (siteKey != null) load(view)
    }

    internal fun detach() {
        webView?.destroy()
        webView = null
        pending?.complete(null)
    }

    private fun load(view: WebView) {
        ready = CompletableDeferred()
        val page = view.context.assets.open(PAGE).bufferedReader().use { it.readText() }
        view.loadDataWithBaseURL(baseUrl, page, "text/html", "utf-8", null)
    }

    private fun quoted(text: String) = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** Called by the page on the WebView's JavaScript thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun ready() {
            ready.complete(true)
        }

        @JavascriptInterface
        fun unavailable() {
            ready.complete(false)
        }

        @JavascriptInterface
        fun token(token: String) {
            webView?.post { interactive = false }
            pending?.complete(token)
        }

        @JavascriptInterface
        fun failed() {
            webView?.post { interactive = false }
            pending?.complete(null)
        }

        @JavascriptInterface
        fun interactive(shown: Boolean) {
            webView?.post { interactive = shown }
        }
    }

    companion object {
        private const val PAGE = "turnstile.html"
        private const val SCRIPT_TIMEOUT_MILLIS = 15_000L

        /** Long enough for a challenge the rider has to answer. */
        private const val TOKEN_TIMEOUT_MILLIS = 120_000L
    }
}

@Composable
fun rememberTurnstile(siteKey: String?, baseUrl: String, language: String, dark: Boolean): TurnstileController =
    remember(siteKey, baseUrl, language, dark) { TurnstileController(siteKey, baseUrl, language, dark) }

/** The widget's place above the send button; nothing without a site key, a line until Cloudflare needs the rider. */
@Composable
fun TurnstileView(controller: TurnstileController, siteKey: String?) {
    if (siteKey == null) return
    AndroidView(
        factory = { context -> WebView(context).also(controller::attach) },
        onRelease = { controller.detach() },
        modifier = Modifier.fillMaxWidth().height(if (controller.interactive) 72.dp else 1.dp).testTag("turnstile"),
    )
}
