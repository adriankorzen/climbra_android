package app.climbra.android.data.net

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Instant
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** A backend answer as it was received, and when. */
class SavedAnswer(val body: ByteArray, val contentType: String?, val savedAt: Instant)

/**
 * The backend's last answers to the requests the app reads offline (spec 018), one encrypted file per request.
 * At most [maxBytes] in all: the answers read least recently go first. A file that cannot be read counts as not
 * saved and is deleted.
 */
class OfflineCache(
    private val dir: File,
    private val cipher: ByteCipher,
    private val maxBytes: Long = MAX_BYTES,
    private val now: () -> Instant = Instant::now,
) {

    @Synchronized
    fun save(url: HttpUrl, body: ByteArray, contentType: String?) {
        dir.mkdirs()
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeLong(now().toEpochMilli())
                out.writeUTF(contentType.orEmpty())
                out.write(body)
            }
        }.toByteArray()
        val file = file(url)
        val temporary = File(dir, file.name + ".tmp")
        try {
            temporary.writeBytes(cipher.encrypt(plain))
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
            file.setLastModified(now().toEpochMilli())
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Not saved: ${e.message}")
            temporary.delete()
        } catch (e: GeneralSecurityException) {
            Log.w(LOG_TAG, "Not saved: ${e.message}")
            temporary.delete()
        }
        trim()
    }

    @Synchronized
    fun read(url: HttpUrl): SavedAnswer? {
        val file = file(url)
        if (!file.exists()) return null
        return try {
            DataInputStream(cipher.decrypt(file.readBytes()).inputStream()).use { input ->
                val savedAt = Instant.ofEpochMilli(input.readLong())
                val contentType = input.readUTF().ifEmpty { null }
                SavedAnswer(input.readBytes(), contentType, savedAt)
            }.also { file.setLastModified(now().toEpochMilli()) }
        } catch (e: GeneralSecurityException) {
            file.delete()
            null
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    /** The session ended: nothing of it stays on the device. */
    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles { file -> file.name.endsWith(SUFFIX) }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEach { file ->
            total += file.length()
            if (total > maxBytes) file.delete()
        }
    }

    /** Method, path and query; the host is the app's one backend. */
    private fun file(url: HttpUrl): File {
        val key = "GET ${url.encodedPath}?${url.encodedQuery.orEmpty()}"
        val hash = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, hash + SUFFIX)
    }

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
        private const val SUFFIX = ".bin"
        private const val LOG_TAG = "OfflineCache"

        /** The `GET`s whose answers the screens of spec 018 read offline. */
        private val SAVED = listOf(
            Regex("/api/auth/me"),
            Regex("/api/riders"),
            Regex("/api/riders/\\d+/(training-load|analyses|activities|rides|calendar)"),
            Regex("/api/activities/\\d+(/analysis|/recording)?"),
        )

        fun isSaved(request: Request): Boolean =
            request.method == "GET" && SAVED.any { it.matches(request.url.encodedPath) }
    }
}

/**
 * Saves the backend's successful answers to [OfflineCache.isSaved] requests and serves them when the backend cannot
 * be reached (spec 018). An HTTP error is the backend's answer and is never replaced; any answer means online.
 */
class OfflineInterceptor(private val cache: OfflineCache, private val connectivity: Connectivity) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (chain.call().isCanceled() || !OfflineCache.isSaved(request)) throw e
            val saved = cache.read(request.url) ?: throw e
            connectivity.servedSaved(saved.savedAt)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("Saved")
                .header(SAVED_AT, saved.savedAt.toString())
                .body(saved.body.toResponseBody(saved.contentType?.toMediaTypeOrNull()))
                .build()
        }
        connectivity.reached()
        if (!response.isSuccessful || !OfflineCache.isSaved(request)) return response
        val body = response.body
        val contentType = body.contentType()
        val bytes = body.bytes()
        cache.save(request.url, bytes, contentType?.toString())
        return response.newBuilder().body(bytes.toResponseBody(contentType)).build()
    }

    companion object {
        /** On an answer served from the device: when the backend sent it. */
        const val SAVED_AT = "X-Climbra-Saved-At"
    }
}
