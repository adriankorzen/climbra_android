package app.climbra.android.data.net

import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.SESSION_COOKIE
import app.climbra.android.testing.TestBackend.Companion.account
import app.climbra.android.testing.TestBackend.Companion.json
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 001 AC2: cookies of the backend survive a restart of the app process. */
class PersistentCookieJarTest {

    private val file = File.createTempFile("cookies", ".bin").apply { delete() }

    @Test
    fun persistentCookiesAreSentAfterARestartSessionCookiesAreNot() = runTest {
        TestBackend(file).use { first ->
            first.on("GET /api/auth/me", json(account(), 200, SESSION_COOKIE, "PRE_LOGIN=temporary; Path=/"))
            first.api.me()
            assertTrue(file.exists())
            assertFalse("stored encrypted", file.readText().contains("session-1"))
        }

        // A new process: a new jar reading the same file
        TestBackend(file).use { restarted ->
            restarted.on("GET /api/auth/me", json(account()))
            restarted.api.me()

            val cookie = restarted.requests("GET /api/auth/me").single().headers["Cookie"]
            assertEquals("CYCLINGAPP_SESSION=session-1", cookie)
        }
    }

    @Test
    fun aCookieExpiredByTheServerIsForgotten() = runTest {
        TestBackend(file).use { backend ->
            backend.on("GET /api/auth/me", json(account(), 200, SESSION_COOKIE))
            backend.on("POST /api/auth/logout", TestBackend.noContent("CYCLINGAPP_SESSION=; Path=/; Max-Age=0"))
            backend.api.me()
            backend.api.logout()

            assertNull(backend.cookies.value(backend.server.url("/"), "CYCLINGAPP_SESSION"))
            assertFalse(file.exists())
        }
    }

    @Test
    fun clearForgetsEverythingAndAnUnreadableFileMeansNoCookies() = runTest {
        TestBackend(file).use { backend ->
            backend.on("GET /api/auth/me", json(account(), 200, SESSION_COOKIE))
            backend.api.me()
            backend.cookies.clear()
            assertNull(backend.cookies.value(backend.server.url("/"), "CYCLINGAPP_SESSION"))
        }
        assertFalse(file.exists())

        file.writeText("not encrypted json")
        assertTrue(EncryptedFileCookieStore(file, object : ByteCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(encrypted: ByteArray): ByteArray = throw java.security.GeneralSecurityException("bad")
        }).load().isEmpty())
        assertFalse(file.exists())
    }
}
