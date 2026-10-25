package cz.teply.sheetset.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import cz.teply.sheetset.pdf.AnnotationJson
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipFile

const val MAX_PDF_BYTES = 250L * 1024L * 1024L
// Recovery and file mutations must be exclusive across repository instances.
private val libraryFilesLock = Any()
private val deletionFileNames = setOf("annotations.json.bak", "annotations.json.new", "annotations.json", "score.pdf")

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
    private val backupArchive: File
        get() = File(
            root.parentFile ?: throw BackupException("Library has no parent directory"),
            ".${root.name}-backup.zip",
        )

    suspend fun load(): LibraryCatalog = withStorageLock { loadCatalog() }

    suspend fun importPdf(source: PdfImport): Score = withStorageLock { importLocked(source) }

    fun pdfFile(score: Score): File = synchronized(libraryFilesLock) {
        loadCatalog()
        require(score.fileName.matches(Regex("[A-Za-z0-9-]+\\.pdf"))) { "Invalid score file" }
        val file = File(scoresDirectory, score.fileName).canonicalFile
        require(file.parentFile == scoresDirectory.canonicalFile) { "Invalid score path" }
        require(file.isFile) { "Score file is missing" }
        file
    }

    suspend fun loadAnnotations(scoreId: String): DocumentAnnotations = withStorageLock {
        loadCatalog()
        val file = annotationFile(scoreId)
        openAtomicFile(file)?.bufferedReader(Charsets.UTF_8)?.use { AnnotationJson.decode(it.readText()) }
            ?: DocumentAnnotations()
    }

    suspend fun saveAnnotations(scoreId: String, annotations: DocumentAnnotations) = withStorageLock {
        loadCatalog()
        annotationsDirectory.mkdirsOrThrow()
        writeAtomic(annotationFile(scoreId), AnnotationJson.encode(annotations))
    }

    suspend fun createBackup(
        destination: OutputStream,
        settings: AppSettings,
        languageTag: String?,
    ) = withStorageLock {
        writeLibraryBackup(root, loadCatalog(), destination, settings, languageTag)
    }

    suspend fun restoreBackup(source: InputStream): BackupMetadata? = withStorageLock {
        val catalog = try {
            Result.success(loadCatalog())
        } catch (error: Exception) {
            Result.failure(error)
        }
        val archive = stageBackup(source)
        val prepared = try {
            if (isSheetSetBackup(archive)) {
                return@withStorageLock restoreLibraryBackup(root, archive.inputStream())
            }
            prepareScorePdfImport(root, catalog.getOrThrow(), archive)
        } finally {
            archive.delete()
        }
        try {
            writeCatalog(prepared.catalog)
        } catch (error: Exception) {
            cleanupNewScoreFiles(prepared.createdFiles, error)
            throw error
        }
        null
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

    suspend fun addScoreToSetlist(setlistId: String, scoreId: String) =
        addScoresToSetlist(setlistId, listOf(scoreId))

    suspend fun addScoresToSetlist(setlistId: String, scoreIds: List<String>) {
        if (scoreIds.isEmpty()) return
        updateCatalog { catalog ->
            scoreIds.fold(catalog) { current, scoreId -> current.addScoreToSetlist(setlistId, scoreId) }
        }
    }

    suspend fun removeScoreFromSetlist(setlistId: String, index: Int) {
        updateCatalog { it.removeScoreFromSetlist(setlistId, index) }
    }

    suspend fun reorderScores(setlistId: String, scoreIds: List<String>) {
        updateCatalog { it.reorderScores(setlistId, scoreIds) }
    }

    suspend fun deleteScore(scoreId: String): Unit = withStorageLock {
        val catalog = loadCatalog()
        val score = catalog.scores.firstOrNull { it.id == scoreId } ?: return@withStorageLock
        val files = deletionFiles(score)
        val staging = checkedLibraryFile(".deletions/${score.id}")
        val updated = catalog.deleteScore(scoreId)
        if (!staging.mkdirs()) throw IOException("Could not stage score deletion")
        try {
            files.forEach { (name, file) ->
                if (file.exists() && (!file.isFile || !file.renameTo(File(staging, name)))) {
                    throw IOException("Could not stage score file: ${file.name}")
                }
            }
            writeCatalog(updated)
        } catch (error: Exception) {
            try {
                loadCatalog()
            } catch (recoveryError: Exception) {
                error.addSuppressed(recoveryError)
            }
            throw error
        }
        // A committed catalog makes staged score files safe to delete.
        loadCatalog()
    }

    private fun importLocked(source: PdfImport): Score {
        recoverLibraryBackup(root)
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
                cleanupNewScoreFiles(listOf(destination), error)
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

    private fun loadCatalog(): LibraryCatalog {
        val archive = backupArchive
        if (archive.exists() && (!archive.isFile || !archive.delete())) {
            throw BackupException("Could not remove interrupted backup archive")
        }
        recoverLibraryBackup(root)
        val catalog = openAtomicFile(catalogFile)?.bufferedReader(Charsets.UTF_8)?.use {
            CatalogJson.decode(it.readText())
        }
        val deletions = checkedLibraryFile(".deletions")
        if (deletions.exists()) {
            val pending = deletions.listFiles() ?: throw IOException("Could not read staged deletions")
            if (pending.isNotEmpty() && catalog == null) throw IOException("Staged deletions have no catalog")
            pending.forEach { staging ->
                if (!staging.name.matches(Regex("[A-Za-z0-9-]+"))) {
                    throw IOException("Invalid staged score ID")
                }
                recoverDeletion(
                    checkedLibraryFile(".deletions/${staging.name}"),
                    catalog?.scores?.firstOrNull { it.id == staging.name },
                )
            }
        }
        return catalog ?: LibraryCatalog()
    }

    private fun deletionFiles(score: Score): Map<String, File> {
        require(score.id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid score ID" }
        require(score.fileName.matches(Regex("[A-Za-z0-9-]+\\.pdf"))) { "Invalid score file" }
        return listOf(".bak", ".new", "").associate { suffix ->
            "annotations.json$suffix" to checkedLibraryFile("annotations/${score.id}.json$suffix")
        } + ("score.pdf" to checkedLibraryFile("scores/${score.fileName}"))
    }

    private fun recoverDeletion(staging: File, score: Score?) {
        val files = staging.listFiles() ?: throw IOException("Could not read staged score deletion")
        if (files.any { it.name !in deletionFileNames || !it.isFile || it.canonicalFile != it }) {
            throw IOException("Staged deletion contains unknown data")
        }
        val destinations = score?.let(::deletionFiles)
        files.forEach { file ->
            val destination = destinations?.getValue(file.name)
            if (destination == null) {
                if (!file.delete()) throw IOException("Could not delete staged score file: ${file.name}")
            } else {
                destination.parentFile?.mkdirsOrThrow()
                if (destination.exists() || !file.renameTo(destination)) {
                    throw IOException("Could not restore staged score file: ${file.name}")
                }
            }
        }
        if (!staging.delete()) throw IOException("Could not remove staged score deletion")
    }

    private fun checkedLibraryFile(path: String): File {
        val file = File(root.canonicalFile, path)
        if (file.canonicalFile != file) throw IOException("Unsafe library path")
        return file
    }

    private fun writeCatalog(catalog: LibraryCatalog) {
        writeAtomic(catalogFile, CatalogJson.encode(catalog))
    }

    private fun cleanupNewScoreFiles(files: List<File>, error: Exception) {
        val referenced = try {
            loadCatalog().scores.mapTo(mutableSetOf(), Score::fileName)
        } catch (recoveryError: Exception) {
            error.addSuppressed(recoveryError)
            return
        }
        files.filter { it.name !in referenced }.forEach(File::delete)
    }

    private fun stageBackup(source: InputStream): File {
        val parent = root.parentFile ?: throw BackupException("Library has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw BackupException("Could not create backup directory")
        }
        val archive = backupArchive
        if (!archive.createNewFile()) throw BackupException("Could not create backup archive")
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
    ): LibraryCatalog = withStorageLock {
        transform(loadCatalog()).also(::writeCatalog)
    }

    private suspend fun <T> withStorageLock(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            synchronized(libraryFilesLock) {
                coroutineContext.ensureActive()
                block()
            }
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
        val incomplete = File("${file.path}.new")
        if (incomplete.exists() && (!incomplete.isFile || !incomplete.delete())) {
            throw IOException("Could not remove interrupted ${file.name}")
        }
        val atomicFile = AtomicFile(file)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
        val committed = openAtomicFile(file)?.use { it.readBytes() }
        if (
            committed == null || !committed.contentEquals(bytes) ||
            File("${file.path}.bak").exists() || File("${file.path}.new").exists()
        ) {
            throw IOException("Could not commit ${file.name}")
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

internal fun openAtomicFile(file: File): FileInputStream? = try {
    AtomicFile(file).openRead().also { input ->
        if (File("${file.path}.bak").exists()) {
            input.close()
            throw IOException("Could not recover ${file.name}")
        }
    }
} catch (error: FileNotFoundException) {
    if (file.exists() || File("${file.path}.bak").exists()) throw error
    null
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
