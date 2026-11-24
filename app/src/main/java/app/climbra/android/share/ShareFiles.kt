package app.climbra.android.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * The share image's file and the intents of the system's share sheet (spec 028). Images live in `cache/shares` only,
 * one per ride or week, and the folder is emptied when the app starts.
 */
object ShareFiles {

    const val DIRECTORY = "shares"

    /** Matches the provider in the manifest. */
    fun authority(context: Context) = "${context.packageName}.files"

    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    /** Deletes the images shared earlier; the apps they went to have their own copies. */
    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    /** Writes the image as `<name>.png`, replacing an earlier one of the same ride or week. */
    fun savePng(context: Context, bitmap: Bitmap, name: String): File {
        val directory = directory(context).apply { mkdirs() }
        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** The system's share sheet for a PNG, readable by the app it goes to. */
    fun imageIntent(uri: Uri, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", uri)
        return Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The system's share sheet for a link: "<title> – <url>", the title also as the subject. */
    fun linkIntent(title: String, url: String, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) url else "$title – $url")
            .putExtra(Intent.EXTRA_SUBJECT, title)
        return Intent.createChooser(send, chooserTitle)
    }
}
