package cz.teply.sheetset.data

import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.InkAnnotation
import cz.teply.sheetset.pdf.InkKind
import cz.teply.sheetset.pdf.NormalizedPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class LibraryRepositoryTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        root = File(context.cacheDir, "library-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun validPdfIsImportedAndPersists() = runBlocking {
        val source = createPdf("valid.pdf", pages = 2)
        val repository = LibraryRepository(root)

        val score = repository.importPdf(
            PdfImport(
                displayName = "Practice.pdf",
                mimeType = "application/pdf",
                declaredSize = source.length(),
                open = source::inputStream,
            ),
        )

        assertEquals("Practice", score.title)
        assertEquals(2, score.pageCount)
        assertEquals(
            File(root, "scores/${score.fileName}").canonicalPath,
            repository.pdfFile(score).canonicalPath,
        )
        assertEquals(listOf(score), LibraryRepository(root).load().scores)
    }

    @Test
    fun malformedPdfLeavesLibraryEmpty() {
        root.mkdirs()
        val source = File(root, "invalid-${UUID.randomUUID()}.pdf").apply {
            writeText("not a PDF")
        }
        val repository = LibraryRepository(root)

        assertThrows(PdfImportException::class.java) {
            runBlocking {
                repository.importPdf(
                    PdfImport(
                        displayName = "Broken.pdf",
                        mimeType = "application/pdf",
                        declaredSize = source.length(),
                        open = source::inputStream,
                    ),
                )
            }
        }
        assertEquals(LibraryCatalog(), runBlocking { repository.load() })
        source.delete()
    }

    @Test
    fun annotationsPersist() = runBlocking {
        val annotations = DocumentAnnotations(
            mapOf(
                0 to listOf(
                    InkAnnotation(
                        id = "ink",
                        kind = InkKind.PEN,
                        width = 0.004f,
                        points = listOf(NormalizedPoint(0.25f, 0.75f)),
                    ),
                ),
            ),
        )
        val repository = LibraryRepository(root)

        repository.saveAnnotations("score-1", annotations)

        assertEquals(annotations, LibraryRepository(root).loadAnnotations("score-1"))
    }

    @Test
    fun importCancellationPropagates() {
        val cancellation = CancellationException("cancel import")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                LibraryRepository(root).importPdf(
                    PdfImport("Cancelled.pdf", "application/pdf", 1L) { throw cancellation },
                )
            }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun setlistsPersistAndScoreDeletionCleansReferences() = runBlocking {
        val source = createPdf("song.pdf", pages = 1)
        val repository = LibraryRepository(root)
        val score = repository.importPdf(
            PdfImport("Song.pdf", "application/pdf", source.length(), source::inputStream),
        )
        repeat(4) { index -> repository.createSetlist("Set ${index + 1}") }
        val firstSetlist = repository.load().setlists.first()
        repository.addScoreToSetlist(firstSetlist.id, score.id)
        repository.saveAnnotations(score.id, DocumentAnnotations())

        repository.deleteScore(score.id)

        val reloaded = LibraryRepository(root).load()
        assertEquals(4, reloaded.setlists.size)
        assertTrue(reloaded.setlists.all { score.id !in it.scoreIds })
        assertTrue(reloaded.scores.isEmpty())
        assertTrue(!File(root, "scores/${score.fileName}").exists())
        assertTrue(!File(root, "annotations/${score.id}.json").exists())
    }

    @Test
    fun failedFileDeletionKeepsScoreVisibleAndCanBeRetried() = runBlocking {
        val source = createPdf("song.pdf", pages = 1)
        val repository = LibraryRepository(root)
        val score = repository.importPdf(
            PdfImport("Song.pdf", "application/pdf", source.length(), source::inputStream),
        )
        val setlist = repository.createSetlist("Show")
        repository.addScoreToSetlist(setlist.id, score.id)
        val blockedFile = File(root, "annotations/${score.id}.json").apply { mkdirs() }
        val blocker = File(blockedFile, "cannot-delete-directory").apply { writeText("private") }

        assertThrows(IOException::class.java) { runBlocking { repository.deleteScore(score.id) } }

        val retained = LibraryRepository(root).load()
        assertEquals(listOf(score), retained.scores)
        assertEquals(listOf(score.id), retained.setlists.single().scoreIds)
        assertTrue(repository.pdfFile(score).isFile)
        assertTrue(blocker.exists())

        assertTrue(blocker.delete())
        assertTrue(blockedFile.delete())
        repository.deleteScore(score.id)
        assertTrue(LibraryRepository(root).load().scores.isEmpty())
        assertTrue(!File(root, "scores/${score.fileName}").exists())
    }

    @Test
    fun scoreDeletionToleratesFilesAlreadyRemoved() = runBlocking {
        val source = createPdf("song.pdf", pages = 1)
        val repository = LibraryRepository(root)
        val score = repository.importPdf(
            PdfImport("Song.pdf", "application/pdf", source.length(), source::inputStream),
        )
        assertTrue(repository.pdfFile(score).delete())

        repository.deleteScore(score.id)
        repository.deleteScore(score.id)

        assertTrue(LibraryRepository(root).load().scores.isEmpty())
    }

    @Test
    fun reorderedSetlistPersistsWithDuplicateScores() = runBlocking {
        val source = createPdf("song.pdf", pages = 1)
        val repository = LibraryRepository(root)
        val first = repository.importPdf(
            PdfImport("First.pdf", "application/pdf", source.length(), source::inputStream),
        )
        val second = repository.importPdf(
            PdfImport("Second.pdf", "application/pdf", source.length(), source::inputStream),
        )
        val setlist = repository.createSetlist("Show")
        listOf(first.id, second.id, first.id).forEach { scoreId ->
            repository.addScoreToSetlist(setlist.id, scoreId)
        }

        repository.reorderScores(setlist.id, listOf(first.id, first.id, second.id))

        assertEquals(
            listOf(first.id, first.id, second.id),
            LibraryRepository(root).load().setlists.single().scoreIds,
        )
    }

    @Test
    fun scoreMetadataPersists() = runBlocking {
        val source = createPdf("metadata.pdf", pages = 3)
        val repository = LibraryRepository(root)
        val score = repository.importPdf(
            PdfImport("Metadata.pdf", "application/pdf", source.length(), source::inputStream),
        )
        val setlist = repository.createSetlist("Show")

        repository.addBookmark(score.id, Bookmark("bookmark-1", "Chorus", 2))
        repository.renameBookmark(score.id, "bookmark-1", "Finale")
        repository.updateScoreLabels(score.id, listOf("Band"))
        repository.updateSetlistLabels(setlist.id, listOf("Saturday"))
        repository.saveReaderPosition(score.id, page = 2, part = 1, viewedAt = 99L)

        val catalog = LibraryRepository(root).load()
        assertEquals(Bookmark("bookmark-1", "Finale", 2), catalog.scores.single().bookmarks.single())
        assertEquals(listOf("Band"), catalog.scores.single().labels)
        assertEquals(listOf("Saturday"), catalog.setlists.single().labels)
        assertEquals(2, catalog.scores.single().lastPageIndex)
        assertEquals(1, catalog.scores.single().lastPagePart)
        assertEquals(99L, catalog.scores.single().lastViewedAtEpochMs)
    }

    private fun createPdf(name: String, pages: Int): File {
        root.mkdirs()
        val file = File(root, "${UUID.randomUUID()}-$name")
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val info = PdfDocument.PageInfo.Builder(600, 800, index + 1).create()
                val page = document.startPage(info)
                page.canvas.drawText("SheetSet ${index + 1}", 40f, 60f, android.graphics.Paint())
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }
}
