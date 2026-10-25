package cz.teply.sheetset.data

import android.content.Context
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.AnnotationEditorSettings
import cz.teply.sheetset.pdf.AnnotationJson
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.NormalizedPoint
import cz.teply.sheetset.pdf.SymbolAnnotation
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.PageFit
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LibraryBackupTest {
    private lateinit var sourceRoot: File
    private lateinit var restoreRoot: File

    @Before
    fun setUp() {
        val cache = ApplicationProvider.getApplicationContext<Context>().cacheDir
        sourceRoot = File(cache, "backup-source-${UUID.randomUUID()}")
        restoreRoot = File(cache, "backup-restore-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        sourceRoot.deleteRecursively()
        restoreRoot.deleteRecursively()
        File(restoreRoot.parentFile, ".${restoreRoot.name}-previous").deleteRecursively()
        File(restoreRoot.parentFile, ".${restoreRoot.name}-restore").deleteRecursively()
    }

    @Test
    fun backupRestoresLibraryAnnotationsAndPreferences() {
        runBlocking {
            val source = LibraryRepository(sourceRoot)
            val pdf = createPdf(pages = 2)
            val score = source.importPdf(
                PdfImport("Score.pdf", "application/pdf", pdf.length(), pdf::inputStream),
            )
            val setlist = source.createSetlist("Show")
            source.addScoreToSetlist(setlist.id, score.id)
            val annotations = DocumentAnnotations(
                mapOf(
                    score.pageCount - 1 to listOf(
                        SymbolAnnotation(
                            id = "symbol",
                            symbolId = "sharp",
                            center = NormalizedPoint(0.2f, 0.3f),
                            size = 0.08f,
                            rotationDegrees = 15f,
                            color = AnnotationColor.RED,
                            opacity = 180,
                        ),
                    ),
                ),
            )
            source.saveAnnotations(score.id, annotations)
            val expectedEditor = AnnotationEditorSettings.defaults().copy(
                palmRejection = true,
                quickColors = listOf(AnnotationColor.RED, AnnotationColor.BLUE),
            )
            val settings = AppSettings(
                pageFit = PageFit.WIDTH,
                pageTurnTaps = false,
                readerLayout = ReaderLayout.HALF,
                themeMode = ThemeMode.DARK,
                editor = expectedEditor,
            )
            val archive = ByteArrayOutputStream()
            source.createBackup(archive, settings, "cs")

            val restored = LibraryRepository(restoreRoot)
            val metadata = requireNotNull(
                restored.restoreBackup(ByteArrayInputStream(archive.toByteArray())),
            )

            assertEquals(source.load(), restored.load())
            assertEquals(annotations, restored.loadAnnotations(score.id))
            assertEquals(expectedEditor, metadata.settings.editor)
            assertTrue(restored.loadAnnotations(score.id).pages.getValue(score.pageCount - 1).single() is SymbolAnnotation)
            assertEquals(settings, metadata.settings)
            assertEquals("cs", metadata.languageTag)
            assertEquals(score.pageCount, restored.load().scores.single().pageCount)
            pdf.delete()
        }
    }

    @Test
    fun backupRejectsAnnotationsBeyondLastPdfPageBeforeWriting() = runBlocking {
        val repository = LibraryRepository(sourceRoot)
        val pdf = createPdf(pages = 2)
        try {
            val score = repository.importPdf(PdfImport("Score.pdf", "application/pdf", pdf.length(), pdf::inputStream))
            repository.saveAnnotations(score.id, DocumentAnnotations(mapOf(score.pageCount to listOf(SymbolAnnotation(
                "symbol", "sharp", NormalizedPoint(0.2f, 0.3f), 0.08f, 15f, AnnotationColor.RED, 180,
            )))))
            val archive = ByteArrayOutputStream()

            assertThrows(BackupException::class.java) {
                runBlocking { repository.createBackup(archive, AppSettings(), null) }
            }

            assertEquals(0, archive.size())
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun annotationsBeyondLastPdfPageCannotReplaceExistingLibrary() = runBlocking {
        val repository = LibraryRepository(restoreRoot)
        val source = LibraryRepository(sourceRoot)
        val pdf = createPdf(pages = 2)
        try {
            val existing = repository.importPdf(PdfImport("Keep.pdf", "application/pdf", pdf.length(), pdf::inputStream))
            val annotations = DocumentAnnotations(mapOf(0 to listOf(SymbolAnnotation(
                "symbol", "sharp", NormalizedPoint(0.2f, 0.3f), 0.08f, 15f, AnnotationColor.RED, 180,
            ))))
            repository.saveAnnotations(existing.id, annotations)
            repository.createSetlist("Keep")
            val original = repository.load()
            val incoming = source.importPdf(PdfImport("Incoming.pdf", "application/pdf", pdf.length(), pdf::inputStream))
            val validArchive = ByteArrayOutputStream()
            source.createBackup(validArchive, AppSettings(), null)
            val archive = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("annotations/${incoming.id}.json"))
                    val invalidAnnotations = DocumentAnnotations(mapOf(incoming.pageCount to annotations.pages.getValue(0)))
                    zip.write(AnnotationJson.encode(invalidAnnotations).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    ZipInputStream(ByteArrayInputStream(validArchive.toByteArray())).use { input ->
                        while (true) {
                            val entry = input.nextEntry ?: break
                            zip.putNextEntry(ZipEntry(entry.name))
                            input.copyTo(zip)
                            zip.closeEntry()
                        }
                    }
                }
            }

            assertThrows(BackupException::class.java) {
                runBlocking { repository.restoreBackup(ByteArrayInputStream(archive.toByteArray())) }
            }

            val reopened = LibraryRepository(restoreRoot)
            assertEquals(original, reopened.load())
            assertTrue(pdf.readBytes().contentEquals(reopened.pdfFile(existing).readBytes()))
            assertEquals(annotations, reopened.loadAnnotations(existing.id))
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun fractionalManifestVersionCannotReplaceExistingLibrary() = runBlocking {
        val repository = LibraryRepository(restoreRoot)
        repository.createSetlist("Keep")
        val original = repository.load()
        val validArchive = ByteArrayOutputStream()
        LibraryRepository(sourceRoot).createBackup(validArchive, AppSettings(), null)
        val archive = ByteArrayOutputStream().also { output ->
            ZipInputStream(ByteArrayInputStream(validArchive.toByteArray())).use { input ->
                ZipOutputStream(output).use { zip ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        zip.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "manifest.json") {
                            val manifest = JSONObject(input.readBytes().toString(Charsets.UTF_8)).put("version", 2.5)
                            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                        } else {
                            input.copyTo(zip)
                        }
                        zip.closeEntry()
                    }
                }
            }
        }

        assertThrows(BackupException::class.java) {
            runBlocking { repository.restoreBackup(ByteArrayInputStream(archive.toByteArray())) }
        }

        assertEquals(original, LibraryRepository(restoreRoot).load())
    }

    @Test
    fun invalidArchiveCannotReplaceExistingLibrary() {
        runBlocking {
            val repository = LibraryRepository(restoreRoot)
            val pdf = createPdf(pages = 1)
            val score = repository.importPdf(
                PdfImport("Existing.pdf", "application/pdf", pdf.length(), pdf::inputStream),
            )
            val malicious = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("../escape"))
                    zip.write(byteArrayOf(1))
                    zip.closeEntry()
                }
            }.toByteArray()

            assertThrows(BackupException::class.java) {
                runBlocking { repository.restoreBackup(ByteArrayInputStream(malicious)) }
            }

            assertEquals(listOf(score), repository.load().scores)
            assertFalse(File(restoreRoot.parentFile, "escape").exists())
            pdf.delete()
        }
    }

    @Test
    fun interruptedRestoreRecoversCatalogPdfAndAnnotationsBeforeWriting() = runBlocking {
        val repository = LibraryRepository(restoreRoot)
        val pdf = createPdf(pages = 1)
        try {
            val score = repository.importPdf(PdfImport("Saved.pdf", "application/pdf", pdf.length(), pdf::inputStream))
            val annotations = DocumentAnnotations(mapOf(0 to listOf(SymbolAnnotation(
                "symbol", "sharp", NormalizedPoint(0.2f, 0.3f), 0.08f, 15f, AnnotationColor.RED, 180,
            ))))
            repository.saveAnnotations(score.id, annotations)
            val previous = File(restoreRoot.parentFile, ".${restoreRoot.name}-previous")
            val staging = File(restoreRoot.parentFile, ".${restoreRoot.name}-restore")
            assertTrue(staging.mkdirs())
            File(staging, "catalog.json").writeText("{")
            assertTrue(restoreRoot.renameTo(previous))

            val reopened = LibraryRepository(restoreRoot)
            reopened.createSetlist("After restart")

            assertEquals(listOf(score), reopened.load().scores)
            assertEquals("After restart", reopened.load().setlists.single().name)
            assertTrue(pdf.readBytes().contentEquals(reopened.pdfFile(score).readBytes()))
            assertEquals(annotations, reopened.loadAnnotations(score.id))
            assertFalse(previous.exists())
            assertFalse(staging.exists())
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun committedRestoreKeepsNewLibraryWhenPreviousStillExists() = runBlocking {
        LibraryRepository(restoreRoot).createSetlist("Old")
        val previous = File(restoreRoot.parentFile, ".${restoreRoot.name}-previous")
        assertTrue(restoreRoot.renameTo(previous))
        assertTrue(restoreRoot.mkdirs())
        val committed = LibraryCatalog(setlists = listOf(Setlist("new", "Restored")))
        File(restoreRoot, "catalog.json").writeText(CatalogJson.encode(committed))

        assertEquals(committed, LibraryRepository(restoreRoot).load())
        assertFalse(previous.exists())
    }

    @Test
    fun directoryPayloadCannotReplaceExistingLibrary() = runBlocking {
        val repository = LibraryRepository(restoreRoot)
        repository.createSetlist("Keep")
        val original = repository.load()
        val emptyBackup = ByteArrayOutputStream()
        LibraryRepository(sourceRoot).createBackup(emptyBackup, AppSettings(), null)
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("scores/"))
                zip.write(1)
                zip.closeEntry()
                ZipInputStream(ByteArrayInputStream(emptyBackup.toByteArray())).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        zip.putNextEntry(ZipEntry(entry.name))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
        }

        val error = assertThrows(BackupException::class.java) {
            runBlocking { repository.restoreBackup(ByteArrayInputStream(archive.toByteArray())) }
        }

        assertEquals("Backup directory contains data", error.message)
        assertEquals(original, repository.load())
    }

    @Test
    fun backupRejectsOversizedLibraryBeforeWriting() = runBlocking {
        val repository = LibraryRepository(sourceRoot)
        val pdf = createPdf(pages = 1)
        try {
            repeat(5) {
                val score = repository.importPdf(PdfImport("Score.pdf", "application/pdf", pdf.length(), pdf::inputStream))
                // Sparse files exercise the total limit without allocating a GiB.
                RandomAccessFile(repository.pdfFile(score), "rw").use { it.setLength(220L * 1024L * 1024L) }
            }
            val archive = ByteArrayOutputStream()

            val error = assertThrows(BackupException::class.java) {
                runBlocking { repository.createBackup(archive, AppSettings(), null) }
            }

            assertEquals("Backup is too large", error.message)
            assertEquals(0, archive.size())
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun backupRejectsOversizedPdfAndAnnotationsBeforeWriting() = runBlocking {
        val repository = LibraryRepository(sourceRoot)
        val pdf = createPdf(pages = 1)
        try {
            val score = repository.importPdf(PdfImport("Score.pdf", "application/pdf", pdf.length(), pdf::inputStream))
            repository.saveAnnotations(score.id, DocumentAnnotations())
            listOf(
                repository.pdfFile(score) to MAX_PDF_BYTES,
                File(sourceRoot, "annotations/${score.id}.json") to MAX_JSON_BYTES,
            ).forEach { (file, limit) ->
                val originalSize = file.length()
                RandomAccessFile(file, "rw").use { it.setLength(limit + 1) }
                val archive = ByteArrayOutputStream()

                val error = assertThrows(BackupException::class.java) {
                    runBlocking { repository.createBackup(archive, AppSettings(), null) }
                }

                assertEquals("Backup entry is too large", error.message)
                assertEquals(0, archive.size())
                RandomAccessFile(file, "rw").use { it.setLength(originalSize) }
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun backupRejectsTooManyEntriesBeforeWriting() {
        val scores = List(MAX_BACKUP_ENTRIES - 1) { index ->
            Score("score-$index", "Score", "score-$index.pdf", 1, 0)
        }
        val archive = ByteArrayOutputStream()

        val error = assertThrows(BackupException::class.java) {
            writeLibraryBackup(sourceRoot, LibraryCatalog(scores), archive, AppSettings(), null)
        }

        assertEquals("Backup has too many entries", error.message)
        assertEquals(0, archive.size())
    }

    @Test
    fun versionOneBackupUsesNewReaderDefaults() {
        runBlocking {
            val source = LibraryRepository(sourceRoot)
            val pdf = createPdf(pages = 1)
            source.importPdf(PdfImport("Score.pdf", "application/pdf", pdf.length(), pdf::inputStream))
            val archive = ByteArrayOutputStream()
            source.createBackup(
                archive,
                AppSettings(readerLayout = ReaderLayout.HALF),
                null,
            )

            val restored = LibraryRepository(restoreRoot).restoreBackup(
                ByteArrayInputStream(asVersionOne(archive.toByteArray())),
            )

            val settings = requireNotNull(restored).settings
            assertEquals(ReaderLayout.SINGLE, settings.readerLayout)
            assertEquals(ThemeMode.LIGHT, settings.themeMode)
            assertEquals(AnnotationEditorSettings.defaults(), settings.editor)
            pdf.delete()
        }
    }

    private fun asVersionOne(archive: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            ZipOutputStream(output).use { zip ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    zip.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "manifest.json") {
                        val manifest = JSONObject(input.readBytes().toString(Charsets.UTF_8))
                        manifest.put("version", 1)
                        manifest.getJSONObject("settings")
                            .remove("readerLayout")
                        manifest.getJSONObject("settings")
                            .remove("editor")
                        manifest.getJSONObject("settings")
                            .remove("themeMode")
                        zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    } else {
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                    input.closeEntry()
                }
            }
        }
    }.toByteArray()

    private fun createPdf(pages: Int): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "backup-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(300, 400, index + 1).create(),
                )
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }
}
