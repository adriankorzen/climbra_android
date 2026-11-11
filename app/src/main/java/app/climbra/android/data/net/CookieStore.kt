package app.climbra.android.data.net

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Cookie

/** Where [PersistentCookieJar] keeps the persistent cookies between app starts. */
interface CookieStore {
    fun load(): List<StoredCookie>
    fun save(cookies: List<StoredCookie>)
}

/** Encrypts and decrypts the stored bytes; production uses a Tink AEAD with a key in the Android Keystore. */
interface ByteCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
) {
    fun toCookie(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .expiresAt(expiresAt)
        .path(path)
        .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
        .apply { if (secure) secure() }
        .apply { if (httpOnly) httpOnly() }
        .build()

    companion object {
        fun of(cookie: Cookie) = StoredCookie(
            name = cookie.name,
            value = cookie.value,
            expiresAt = cookie.expiresAt,
            domain = cookie.domain,
            path = cookie.path,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly,
        )
    }
}

/** The cookies as encrypted JSON in one file; a file that cannot be read counts as no cookies (logged out). */
class EncryptedFileCookieStore(private val file: File, private val cipher: ByteCipher) : CookieStore {

    private val serializer = ListSerializer(StoredCookie.serializer())

    override fun load(): List<StoredCookie> {
        if (!file.exists()) return emptyList()
        return try {
            Json.decodeFromString(serializer, cipher.decrypt(file.readBytes()).decodeToString())
        } catch (e: GeneralSecurityException) {
            file.delete()
            emptyList()
        } catch (e: SerializationException) {
            file.delete()
            emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }

    override fun save(cookies: List<StoredCookie>) {
        if (cookies.isEmpty()) {
            file.delete()
            return
        }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(cipher.encrypt(Json.encodeToString(serializer, cookies).encodeToByteArray()))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }
}
