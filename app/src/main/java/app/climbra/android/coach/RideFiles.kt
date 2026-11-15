package app.climbra.android.coach

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.InputStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/** A file the rider chose for the coach: [uri] of the system picker and its display [name]. */
data class PickedFile(val uri: String, val name: String) {
    val rideFile: Boolean get() = isRideFile(name)
}

/** The coach accepts FIT and ZIP files only (the web's `isRideFile`). */
fun isRideFile(name: String): Boolean = name.lowercase().let { it.endsWith(".fit") || it.endsWith(".zip") }

/** The MIME types the system file picker offers. */
val RIDE_FILE_TYPES = arrayOf("application/octet-stream", "application/zip", "application/vnd.ant.fit")

/** Reads a chosen file; nothing is stored on the device (spec 006). */
interface RideFileSource {
    /** @throws FileNotFoundException when the file is gone */
    fun open(uri: String): InputStream

    /** The display name, `null` when unknown. */
    fun name(uri: String): String?
}

class ContentResolverFileSource(private val context: Context) : RideFileSource {
    override fun open(uri: String): InputStream =
        context.contentResolver.openInputStream(uri.toUri()) ?: throw FileNotFoundException(uri)

    override fun name(uri: String): String? = context.contentResolver
        .query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}

/**
 * The file as the multipart part [field], streamed from [source] when sent (again on a retry of the request, e.g.
 * after a new CSRF token).
 */
fun RideFileSource.part(field: String, file: PickedFile): MultipartBody.Part {
    val type = (if (file.name.lowercase().endsWith(".zip")) "application/zip" else "application/octet-stream").toMediaType()
    val body = object : RequestBody() {
        override fun contentType(): MediaType = type

        override fun writeTo(sink: BufferedSink) {
            open(file.uri).source().use { sink.writeAll(it) }
        }
    }
    return MultipartBody.Part.createFormData(field, file.name, body)
}
