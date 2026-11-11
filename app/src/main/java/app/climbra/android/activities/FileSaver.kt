package app.climbra.android.activities

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.IOException
import java.io.InputStream

/** Stores a downloaded file for the user; an interface so tests need no MediaStore. */
fun interface FileSaver {
    /** @throws IOException when the file cannot be written */
    fun saveToDownloads(name: String, mimeType: String, content: InputStream)
}

/** The Downloads collection of MediaStore: no storage permission from Android 10 on (the app's minimum). */
class MediaStoreFileSaver(private val context: Context) : FileSaver {

    override fun saveToDownloads(name: String, mimeType: String, content: InputStream) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads refused the file")
        try {
            resolver.openOutputStream(uri)?.use { output -> content.copyTo(output) }
                ?: throw IOException("Cannot write to Downloads")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
