package app.climbra.android.activities

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import app.climbra.android.coach.PickedFile
import app.climbra.android.coach.RideFileSource

/** The content URIs another app shared or opened with Climbra (spec 009): "Share" with one or several files, "Open with". */
fun sharedUris(intent: Intent?): List<Uri> {
    intent ?: return emptyList()
    val uris = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> emptyList()
    }
    return uris.filter { it.scheme == "content" }
}

/** The shared files with their display names; the names decide what is a ride file. */
fun RideFileSource.sharedFiles(intent: Intent?): List<PickedFile> = sharedUris(intent).map { uri ->
    val text = uri.toString()
    PickedFile(text, runCatching { name(text) }.getOrNull() ?: uri.lastPathSegment.orEmpty())
}
