package app.climbra.android.activities

import app.climbra.android.coach.PickedFile
import app.climbra.android.coach.RideFileSource
import app.climbra.android.data.api.ImportItem
import app.climbra.android.data.api.ImportItemStatus
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A ride file of the chosen files (spec 014): [path] is unique and names it in the import's outcomes. */
data class RideEntry(
    val path: String,
    /** The uncompressed ride file. */
    val rawBytes: Long,
    /** About what it takes in a batch ZIP. */
    val packedBytes: Long,
)

/** What the chosen files hold: the ride files, those that will not be sent and why, and entries that are not rides. */
data class ExportScan(val entries: List<RideEntry>, val rejected: List<ImportItem>, val ignored: Int)

/** A chosen file could not be read at all; nothing is sent. */
class UnreadableFileException(val fileName: String, cause: Throwable? = null) : IOException(fileName, cause)

/**
 * Reads the chosen files as the web's `import-archive` (cyclingapp spec 040): FIT and TCX files, gzipped or not, loose
 * or in ZIP archives nested up to [MAX_DEPTH] deep (a Garmin account export nests them). Everything is streamed from
 * the content URIs, always in the same order, so a second walk finds the entries of the first.
 */
class ExportReader(private val source: RideFileSource) {

    /** The first walk: what will be sent; [onFound] counts the ride files found so far. */
    suspend fun scan(files: List<PickedFile>, onFound: (Int) -> Unit = {}): ExportScan {
        val entries = mutableListOf<RideEntry>()
        val rejected = mutableListOf<ImportItem>()
        var ignored = 0
        walk(
            files,
            onRide = { path, stream ->
                val gzipped = path.lowercase().endsWith(".gz")
                val counted = CountingInputStream(stream)
                try {
                    val raw = if (gzipped) GZIPInputStream(counted).use(::count) else count(counted)
                    when {
                        raw > MAX_FIT_BYTES -> rejected += rejectedItem(path, ImportItemStatus.TOO_LARGE, TOO_LARGE_MESSAGE)
                        else -> {
                            entries += RideEntry(path, raw, if (gzipped) counted.count else raw / PLAIN_FIT_PACKING)
                            onFound(entries.size)
                        }
                    }
                } catch (e: IOException) {
                    rejected += rejectedItem(path, ImportItemStatus.INVALID, UNREADABLE_MESSAGE)
                }
            },
            onIgnored = { ignored++ },
            onBrokenArchive = { path -> rejected += rejectedItem(path, ImportItemStatus.INVALID, UNREADABLE_MESSAGE) },
        )
        return ExportScan(entries, rejected, ignored)
    }

    /**
     * Every ride file in order: [onRide] gets its path and its own bytes as chosen (gzipped or not), and may suspend,
     * e.g. to send a full batch before the walk goes on.
     *
     * @throws UnreadableFileException when a chosen file cannot be read; a nested archive only rejects itself
     */
    suspend fun walk(
        files: List<PickedFile>,
        onRide: suspend (path: String, stream: InputStream) -> Unit,
        onIgnored: (path: String) -> Unit = {},
        onBrokenArchive: (path: String) -> Unit = {},
    ) {
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val stream = try {
                source.open(file.uri)
            } catch (e: IOException) {
                throw UnreadableFileException(file.name, e)
            }
            stream.use {
                if (isZip(file.name)) {
                    try {
                        walkZip(ZipInputStream(it), "${file.name}/", depth = 1, onRide, onIgnored, onBrokenArchive)
                    } catch (e: IOException) {
                        throw UnreadableFileException(file.name, e)
                    }
                } else if (isImportFile(file.name)) {
                    onRide(entryName(file.name), it)
                } else {
                    onIgnored(file.name)
                }
            }
        }
    }

    private suspend fun walkZip(
        zip: ZipInputStream,
        prefix: String,
        depth: Int,
        onRide: suspend (String, InputStream) -> Unit,
        onIgnored: (String) -> Unit,
        onBrokenArchive: (String) -> Unit,
    ) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: return
            if (entry.isDirectory) continue
            val path = prefix + entry.name
            when {
                isRecording(entry.name) || isGzippedRecording(entry.name) -> onRide(entryName(path), KeepOpen(zip))
                isZip(entry.name) && depth < MAX_DEPTH -> {
                    try {
                        walkZip(ZipInputStream(KeepOpen(zip)), "$path/", depth + 1, onRide, onIgnored, onBrokenArchive)
                    } catch (e: UnreadableNested) {
                        throw e
                    } catch (e: IOException) {
                        // A broken archive inside rejects only itself; the outer one may not go on after it
                        onBrokenArchive(entryName(path))
                        drain(zip)
                    }
                }
                else -> onIgnored(path)
            }
        }
    }

    /** Reads what is left of the current entry, so the archive goes on with the next one. */
    private fun drain(zip: ZipInputStream) {
        val buffer = ByteArray(BUFFER)
        try {
            while (zip.read(buffer) >= 0) Unit
        } catch (e: IOException) {
            throw UnreadableNested(e)
        }
    }

    private class UnreadableNested(cause: Throwable) : IOException(cause)

    companion object {
        /** The backend's `FitArchiveReader.MAX_DEPTH`. */
        const val MAX_DEPTH = 3

        /** One uncompressed ride file (`upload.max-file-size`). */
        const val MAX_FIT_BYTES = 50L * 1024 * 1024

        /** Shorter than the backend's column for an item's file name. */
        const val MAX_NAME_LENGTH = 255

        /** Ride files compress at least this well; a batch that ends up too large is split. */
        const val PLAIN_FIT_PACKING = 4

        const val TOO_LARGE_MESSAGE = "The file is larger than 50 MB"
        const val UNREADABLE_MESSAGE = "The file cannot be read"
        private const val BUFFER = 64 * 1024

        fun isRecording(name: String) = name.lowercase().let { it.endsWith(".fit") || it.endsWith(".tcx") }

        fun isGzippedRecording(name: String) = name.lowercase().let { it.endsWith(".fit.gz") || it.endsWith(".tcx.gz") }

        fun isZip(name: String) = name.lowercase().endsWith(".zip")

        /** The end of a long path, which tells the file. */
        fun entryName(path: String) = if (path.length <= MAX_NAME_LENGTH) path else path.takeLast(MAX_NAME_LENGTH)

        private fun count(stream: InputStream): Long {
            val buffer = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) return total
                total += read
            }
        }
    }
}

/** Outcome of a file the app did not send (the web's `rejectedItem`). */
fun rejectedItem(fileName: String, status: ImportItemStatus, message: String) =
    ImportItem(fileName = fileName, status = status, message = message)

/** An entry of an archive: closing it does not close the archive. */
private class KeepOpen(stream: InputStream) : FilterInputStream(stream) {
    override fun close() = Unit
}

/** Counts the bytes read through it. */
internal class CountingInputStream(stream: InputStream) : FilterInputStream(stream) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
}
