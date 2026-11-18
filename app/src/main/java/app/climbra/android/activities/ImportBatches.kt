package app.climbra.android.activities

import app.climbra.android.data.api.ImportItem
import app.climbra.android.data.api.ImportItemStatus
import app.climbra.android.data.api.ImportJob
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * A batch stays under the backend's limits of one upload (cyclingapp spec 040, the web's `import-batches`); keep these
 * below `upload.*` in its application.yml: max-rides 200, max-files 500, max-request-size 50 MB, max-total-size 300 MB.
 */
const val MAX_BATCH_FILES = 100
const val MAX_BATCH_PACKED_BYTES = 40L * 1024 * 1024
const val MAX_BATCH_RAW_BYTES = 200L * 1024 * 1024

/** A built batch over this does not fit in one request (`upload.max-request-size`). */
const val MAX_REQUEST_BYTES = MAX_IMPORT_BYTES

/** Divides the files into batches in their order, each within the limits; a file over a limit goes alone. */
fun divideIntoBatches(entries: List<RideEntry>): List<List<RideEntry>> {
    val batches = mutableListOf<List<RideEntry>>()
    var batch = mutableListOf<RideEntry>()
    var packed = 0L
    var raw = 0L
    for (entry in entries) {
        val full = batch.size >= MAX_BATCH_FILES ||
            packed + entry.packedBytes > MAX_BATCH_PACKED_BYTES ||
            raw + entry.rawBytes > MAX_BATCH_RAW_BYTES
        if (full && batch.isNotEmpty()) {
            batches += batch
            batch = mutableListOf()
            packed = 0
            raw = 0
        }
        batch += entry
        packed += entry.packedBytes
        raw += entry.rawBytes
    }
    if (batch.isNotEmpty()) batches += batch
    return batches
}

/**
 * Adds outcomes to those per file. A later outcome replaces an earlier one, except that an imported ride stays
 * imported: a batch sent again reports the ride as a duplicate of itself.
 */
fun MutableMap<String, ImportItem>.mergeOutcomes(items: List<ImportItem>) {
    for (item in items) {
        if (get(item.fileName)?.status != ImportItemStatus.IMPORTED) put(item.fileName, item)
    }
}

/** A job's counters from the outcomes of its files (the web's `countOutcomes`). */
fun ImportJob.countedFrom(outcomes: Collection<ImportItem>): ImportJob = copy(
    imported = outcomes.count { it.status == ImportItemStatus.IMPORTED },
    duplicates = outcomes.count { it.status == ImportItemStatus.DUPLICATE },
    skipped = outcomes.count { it.status == ImportItemStatus.NOT_CYCLING },
    overLimit = outcomes.count { it.status == ImportItemStatus.OVER_LIMIT },
    unavailable = outcomes.count { it.status == ImportItemStatus.UNAVAILABLE },
    failed = outcomes.count {
        it.status == ImportItemStatus.INVALID || it.status == ImportItemStatus.TOO_LARGE || it.status == ImportItemStatus.FAILED
    },
    items = outcomes.toList(),
)

/** One batch as a ZIP file in the app's cache: the ride files as chosen, gzipped or not. */
class BatchWriter(private val file: File) : AutoCloseable {
    private val counting = CountingOutputStream(BufferedOutputStream(FileOutputStream(file)))
    private val zip = ZipOutputStream(counting)
    private val names = mutableListOf<String>()

    /** The paths written so far. */
    val entries: List<String> get() = names

    /** About what the file takes so far. */
    val bytes: Long get() = counting.count

    fun add(path: String, stream: InputStream) {
        // Gzipped files do not compress again: no work spent on them
        zip.setLevel(if (path.lowercase().endsWith(".gz")) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry(path))
        stream.copyTo(zip)
        zip.closeEntry()
        names += path
    }

    /** Ends the ZIP; [file] is ready to send. */
    fun finish(): File {
        zip.close()
        return file
    }

    override fun close() {
        runCatching { zip.close() }
    }
}

private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}
