package app.climbra.android.data.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Cookies of the backend, kept like a browser does: all of them in memory, the persistent ones (with an expiry, e.g.
 * the remember-me session cookie) also in [store] so the session survives a restart of the app (spec 001).
 */
class PersistentCookieJar(
    private val store: CookieStore,
    private val now: () -> Long = System::currentTimeMillis,
) : CookieJar {

    private val cookies: MutableList<Cookie> by lazy {
        store.load().map { it.toCookie() }.filter { it.expiresAt > now() }.toMutableList()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val time = now()
        for (cookie in cookies) {
            this.cookies.removeAll { it.sameIdentity(cookie) }
            if (cookie.expiresAt > time) {
                this.cookies += cookie
            }
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val time = now()
        if (cookies.removeAll { it.expiresAt <= time }) {
            persist()
        }
        return cookies.filter { it.matches(url) }
    }

    /** Value of the cookie [name] that would be sent to [url], or `null`. */
    fun value(url: HttpUrl, name: String): String? = loadForRequest(url).lastOrNull { it.name == name }?.value

    /** Forgets every cookie, e.g. after a logout or an expired session. */
    @Synchronized
    fun clear() {
        cookies.clear()
        store.save(emptyList())
    }

    private fun persist() {
        store.save(cookies.filter { it.persistent }.map { StoredCookie.of(it) })
    }

    private fun Cookie.sameIdentity(other: Cookie) =
        name == other.name && domain == other.domain && path == other.path && hostOnly == other.hostOnly
}
