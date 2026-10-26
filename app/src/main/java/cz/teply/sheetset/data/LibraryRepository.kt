package cz.teply.sheetset.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import cz.teply.sheetset.pdf.AnnotationJson
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipFile

const val MAX_PDF_BYTES = 250L * 1024L * 1024L

class PdfImport(
    val displayName: String,
    val mimeType: String?,
    val declaredSize: Long?,
    val open: () -> InputStream,
)

class PdfImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LibraryRepository(private val root: File) {
    private val mutex = Mutex()
    private val catalogFile = File(root, "catalog.json")
    private val scoresDirectory = File(root, "scores")
    private val annotationsDirectory = File(root, "annotations")

    suspend fun load(): LibraryCatalog = withContext(Dispatchers.IO) {
        mutex.withLock { loadCatalog() }
    }

    suspend fun importPdf(source: PdfImport): Score = withContext(Dispatchers.IO) {
        mutex.withLock { importLocked(source) }
    }

    fun pdfFile(score: Score): File {
        require(score.fileName.matches(Regex("[A-Za-z0-9-]+\\.pdf"))) { "Invalid score file" }
        val file = File(scoresDirectory, score.fileName).canonicalFile
        require(file.parentFile == scoresDirectory.canonicalFile) { "Invalid score path" }
        require(file.isFile) { "Score file is missing" }
        return file
    }

    suspend fun loadAnnotations(scoreId: String): DocumentAnnotations = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = annotationFile(scoreId)
            if (file.exists()) AnnotationJson.decode(file.readText()) else DocumentAnnotations()
        }
    }

    suspend fun saveAnnotations(scoreId: String, annotations: DocumentAnnotations) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                annotationsDirectory.mkdirsOrThrow()
                writeAtomic(annotationFile(scoreId), AnnotationJson.encode(annotations))
            }
        }
    }

    suspend fun createBackup(
        destination: OutputStream,
        settings: AppSettings,
        languageTag: String?,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeLibraryBackup(root, loadCatalog(), destination, settings, languageTag)
        }
    }

    suspend fun restoreBackup(source: InputStream): BackupMetadata? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val archive = stageBackup(source)
            try {
                if (isSheetSetBackup(archive)) {
                    restoreLibraryBackup(root, archive.inputStream())
                } else {
                    val prepared = prepareScorePdfImport(root, loadCatalog(), archive)
                    try {
                        writeCatalog(prepared.catalog)
                    } catch (error: Exception) {
                        prepared.createdFiles.forEach(File::delete)
                        throw error
                    }
                    null
                }
            } finally {
                archive.delete()
            }
        }
    }

    suspend fun createSetlist(name: String): Setlist {
        val id = UUID.randomUUID().toString()
        return updateCatalog { it.createSetlist(name, id) }.setlists.first { it.id == id }
    }

    suspend fun renameScore(scoreId: String, title: String) {
        updateCatalog { it.renameScore(scoreId, title) }
    }

    suspend fun addBookmark(scoreId: String, bookmark: Bookmark) {
        updateCatalog { it.addBookmark(scoreId, bookmark) }
    }

    suspend fun renameBookmark(scoreId: String, bookmarkId: String, title: String) {
        updateCatalog { it.renameBookmark(scoreId, bookmarkId, title) }
    }

    suspend fun deleteBookmark(scoreId: String, bookmarkId: String) {
        updateCatalog { it.deleteBookmark(scoreId, bookmarkId) }
    }

    suspend fun updateScoreLabels(scoreId: String, labels: List<String>) {
        updateCatalog { it.updateScoreLabels(scoreId, labels) }
    }

    suspend fun updateSetlistLabels(setlistId: String, labels: List<String>) {
        updateCatalog { it.updateSetlistLabels(setlistId, labels) }
    }

    suspend fun saveReaderPosition(scoreId: String, page: Int, part: Int, viewedAt: Long) {
        updateCatalog { it.saveReaderPosition(scoreId, page, part, viewedAt) }
    }

    suspend fun renameSetlist(setlistId: String, name: String) {
        updateCatalog { it.renameSetlist(setlistId, name) }
    }

    suspend fun deleteSetlist(setlistId: String) {
        updateCatalog { it.deleteSetlist(setlistId) }
    }

    suspend fun addScoreToSetlist(setlistId: String, scoreId: String) {
        updateCatalog { it.addScoreToSetlist(setlistId, scoreId) }
    }

    suspend fun removeScoreFromSetlist(setlistId: String, index: Int) {
        updateCatalog { it.removeScoreFromSetlist(setlistId, index) }
    }

    suspend fun reorderScores(setlistId: String, scoreIds: List<String>) {
        updateCatalog { it.reorderScores(setlistId, scoreIds) }
    }

    suspend fun deleteScore(scoreId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val catalog = loadCatalog()
                val score = catalog.scores.firstOrNull { it.id == scoreId } ?: return@withLock
                val files = listOf(annotationFile(scoreId), File(scoresDirectory, score.fileName))
                for (file in files) {
                    if (file.exists() && (!file.isFile || !file.delete())) {
                        throw IOException("Could not delete score file: ${file.name}")
                    }
                }
                writeCatalog(catalog.deleteScore(scoreId))
            }
        }
    }

    private fun importLocked(source: PdfImport): Score {
        if (source.mimeType != null && !source.mimeType.equals("application/pdf", ignoreCase = true)) {
            throw PdfImportException("Only PDF files are supported")
        }
        if (source.declaredSize != null && source.declaredSize !in 0..MAX_PDF_BYTES) {
            throw PdfImportException("PDF is larger than 250 MiB")
        }
        root.mkdirsOrThrow()
        scoresDirectory.mkdirsOrThrow()
        val id = UUID.randomUUID().toString()
        val temporary = File(scoresDirectory, ".$id.tmp")
        val destination = File(scoresDirectory, "$id.pdf")
        try {
            source.open().use { input -> copyBounded(input, temporary) }
            val pageCount = validatePdfFile(temporary)
            if (!temporary.renameTo(destination)) {
                throw PdfImportException("Could not store PDF")
            }
            val score = Score(
                id = id,
                title = cleanTitle(source.displayName),
                fileName = destination.name,
                pageCount = pageCount,
                importedAtEpochMs = System.currentTimeMillis(),
            )
            val catalog = loadCatalog()
            try {
                writeCatalog(catalog.copy(scores = catalog.scores + score))
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
            return score
        } catch (error: CancellationException) {
            throw error
        } catch (error: PdfImportException) {
            throw error
        } catch (error: Exception) {
            throw PdfImportException("Could not read PDF", error)
        } finally {
            temporary.delete()
        }
    }

    private fun loadCatalog(): LibraryCatalog = if (catalogFile.exists()) {
        CatalogJson.decode(catalogFile.readText())
    } else {
        LibraryCatalog()
    }

    private fun writeCatalog(catalog: LibraryCatalog) {
        writeAtomic(catalogFile, CatalogJson.encode(catalog))
    }

    private fun stageBackup(source: InputStream): File {
        val parent = root.parentFile ?: throw BackupException("Library has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw BackupException("Could not create backup directory")
        }
        val archive = File.createTempFile("sheetset-backup-", ".zip", parent)
        try {
            FileOutputStream(archive).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > MAX_BACKUP_BYTES) throw BackupException("Backup is too large")
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            return archive
        } catch (error: Exception) {
            archive.delete()
            throw error
        }
    }

    private fun isSheetSetBackup(archive: File): Boolean = try {
        ZipFile(archive).use { zip -> zip.getEntry("manifest.json") != null }
    } catch (error: Exception) {
        throw BackupException("Backup is not a valid ZIP archive", error)
    }

    private suspend fun updateCatalog(
        transform: (LibraryCatalog) -> LibraryCatalog,
    ): LibraryCatalog = withContext(Dispatchers.IO) {
        mutex.withLock {
            transform(loadCatalog()).also(::writeCatalog)
        }
    }

    private fun annotationFile(scoreId: String): File {
        require(scoreId.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid score ID" }
        return File(annotationsDirectory, "$scoreId.json")
    }

    private fun copyBounded(input: InputStream, destination: File) {
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PDF_BYTES) throw PdfImportException("PDF is larger than 250 MiB")
                output.write(buffer, 0, read)
            }
            output.fd.sync()
        }
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirsOrThrow()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun cleanTitle(displayName: String): String = displayName
        .replace(Regex("(?i)\\.pdf$"), "")
        .trim()
        .take(MAX_TITLE_LENGTH)
        .ifEmpty { "Untitled PDF" }

    private fun File.mkdirsOrThrow() {
        if (!exists() && !mkdirs()) throw IllegalStateException("Could not create ${name}")
    }
}

internal fun validatePdfFile(file: File): Int {
    val expected = "%PDF-".toByteArray(Charsets.US_ASCII)
    val actual = ByteArray(expected.size)
    FileInputStream(file).use { input ->
        require(input.read(actual) == expected.size && actual.contentEquals(expected)) {
            "File is not a valid PDF"
        }
    }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.pageCount.also { count -> require(count > 0) { "PDF has no pages" } }
        }
    }
}
