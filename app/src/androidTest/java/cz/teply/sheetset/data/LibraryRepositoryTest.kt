package cz.teply.sheetset.data

import android.graphics.pdf.PdfDocument
import android.system.Os
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.InkAnnotation
import cz.teply.sheetset.pdf.InkKind
import cz.teply.sheetset.pdf.NormalizedPoint
import cz.teply.sheetset.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

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
    fun failedAnnotationCommitReportsFailureAndAllowsRetry() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val original = repository.loadAnnotations(score.id)
        val changed = DocumentAnnotations()
        val directory = File(root, "annotations")
        val annotations = File(directory, "${score.id}.json")
        val incomplete = File("${annotations.path}.new")
        if (incomplete.exists()) assertTrue(incomplete.delete())
        val atomicFile = AtomicFile(annotations)
        val pending = atomicFile.startWrite()
        val usesNewFile = incomplete.exists()
        atomicFile.failWrite(pending)
        annotations.copyTo(File("${annotations.path}.bak"))
        // Keep the pending file writable while preventing directory changes.
        if (usesNewFile) incomplete.writeText("")
        assertTrue(directory.setWritable(false, false))
        try {
            assertThrows(IOException::class.java) {
                runBlocking { repository.saveAnnotations(score.id, changed) }
            }
        } finally {
            assertTrue(directory.setWritable(true, true))
        }

        assertEquals(original, repository.loadAnnotations(score.id))
        repository.saveAnnotations(score.id, changed)
        assertEquals(changed, LibraryRepository(root).loadAnnotations(score.id))
    }

    @Test
    fun staleNewFilesAllowSingleSuccessfulWriteAndDirectoryBlockersPreserveCatalog() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val initial = repository.load()
        val pendingCatalog = File(root, "catalog.json.new").apply { writeText("interrupted catalog") }

        val created = repository.createSetlist("Encore")

        assertEquals(initial.copy(setlists = initial.setlists + created), repository.load())
        assertFalse(pendingCatalog.exists())
        val pendingAnnotations = File(root, "annotations/${score.id}.json.new")
            .apply { writeText("interrupted annotations") }
        repository.saveAnnotations(score.id, DocumentAnnotations())
        assertEquals(DocumentAnnotations(), repository.loadAnnotations(score.id))
        assertFalse(pendingAnnotations.exists())

        val before = repository.load()
        assertTrue(pendingCatalog.mkdir())
        val blocker = File(pendingCatalog, "keep").apply { writeText("unknown data") }
        assertThrows(IOException::class.java) { runBlocking { repository.createSetlist("Blocked") } }
        assertEquals(before, repository.load())
        assertEquals("unknown data", blocker.readText())
    }

    @Test
    fun interruptedAtomicWritesRecoverCatalogAndAnnotations() = runBlocking {
        val repository = LibraryRepository(root)
        val annotations = DocumentAnnotations(
            mapOf(0 to listOf(InkAnnotation(
                id = "ink",
                kind = InkKind.PEN,
                width = 0.004f,
                points = listOf(NormalizedPoint(0.25f, 0.75f)),
            ))),
        )
        assertEquals(LibraryCatalog(), repository.load())
        assertEquals(DocumentAnnotations(), repository.loadAnnotations("score-1"))
        repository.createSetlist("Saved")
        repository.saveAnnotations("score-1", annotations)
        val catalog = repository.load()
        val files = listOf(File(root, "catalog.json"), File(root, "annotations/score-1.json"))

        listOf(false, true).forEach { leaveBrokenBase ->
            files.forEach { file ->
                assertTrue(file.renameTo(File("${file.path}.bak")))
                if (leaveBrokenBase) file.writeText("{")
            }
            val reopened = LibraryRepository(root)
            assertEquals(catalog, reopened.load())
            assertEquals(annotations, reopened.loadAnnotations("score-1"))
            files.forEach { assertFalse(File("${it.path}.bak").exists()) }
        }
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
        val annotations = File(root, "annotations/${score.id}.json")
        listOf(".bak", ".new").forEach { suffix ->
            annotations.copyTo(File("${annotations.path}$suffix"))
        }

        repository.deleteScore(score.id)

        val reloaded = LibraryRepository(root).load()
        assertEquals(4, reloaded.setlists.size)
        assertTrue(reloaded.setlists.all { score.id !in it.scoreIds })
        assertTrue(reloaded.scores.isEmpty())
        assertTrue(!File(root, "scores/${score.fileName}").exists())
        listOf("", ".bak", ".new").forEach { suffix ->
            assertFalse(File("${annotations.path}$suffix").exists())
        }
    }

    @Test
    fun failedFileDeletionKeepsScoreVisibleAndCanBeRetried() = runBlocking {
        val source = createPdf("song.pdf", pages = 1)
        val repository = LibraryRepository(root)
        val setlist = repository.createSetlist("Show")
        listOf("", ".bak", ".new").forEach { suffix ->
            val score = repository.importPdf(
                PdfImport("Song.pdf", "application/pdf", source.length(), source::inputStream),
            )
            repository.addScoreToSetlist(setlist.id, score.id)
            repository.saveAnnotations(score.id, DocumentAnnotations())
            val annotations = File(root, "annotations/${score.id}.json")
            val blockedFile = File("${annotations.path}$suffix")
            if (blockedFile.exists()) assertTrue(blockedFile.delete())
            assertTrue(blockedFile.mkdirs())
            val blocker = File(blockedFile, "cannot-delete-directory").apply { writeText("private") }

            assertThrows(IOException::class.java) { runBlocking { repository.deleteScore(score.id) } }

            val retained = LibraryRepository(root).load()
            assertEquals(listOf(score), retained.scores)
            assertEquals(listOf(score.id), retained.setlists.single().scoreIds)
            assertTrue(repository.pdfFile(score).isFile)
            assertTrue(blocker.exists())
            if (suffix.isNotEmpty()) assertTrue(annotations.isFile)

            assertTrue(blocker.delete())
            assertTrue(blockedFile.delete())
            repository.deleteScore(score.id)
            val deleted = LibraryRepository(root).load()
            assertTrue(deleted.scores.isEmpty())
            assertTrue(deleted.setlists.single().scoreIds.isEmpty())
            assertFalse(File(root, "scores/${score.fileName}").exists())
        }
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
    fun failedPdfMoveRestoresAnnotationsAndAllowsRetry() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val catalog = repository.load()
        val originals = scoreFiles(score).values.associateWith(File::readBytes)
        val scores = File(root, "scores")
        assertTrue(scores.setWritable(false, false))
        try {
            assertThrows(IOException::class.java) { runBlocking { repository.deleteScore(score.id) } }
        } finally {
            assertTrue(scores.setWritable(true, true))
        }

        assertEquals(catalog, LibraryRepository(root).load())
        originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertFalse(File(root, ".deletions/${score.id}").exists())
        repository.deleteScore(score.id)
        assertTrue(repository.load().scores.isEmpty())
        originals.keys.forEach { assertFalse(it.exists()) }
    }

    @Test
    fun failedCatalogWriteRestoresAllScoreFilesAndAllowsRetry() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val catalog = repository.load()
        val originals = scoreFiles(score).values.associateWith(File::readBytes)
        val catalogFile = File(root, "catalog.json")
        assertTrue(File(root, ".deletions").mkdirs())
        assertTrue(catalogFile.setWritable(false, false))
        try {
            assertTrue(root.setWritable(false, false))
            assertThrows(IOException::class.java) { runBlocking { repository.deleteScore(score.id) } }
        } finally {
            assertTrue(root.setWritable(true, true))
            assertTrue(catalogFile.setWritable(true, true))
        }

        assertEquals(catalog, LibraryRepository(root).load())
        originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertFalse(File(root, ".deletions/${score.id}").exists())
        repository.deleteScore(score.id)
        assertEquals(catalog.deleteScore(score.id), repository.load())
        originals.keys.forEach { assertFalse(it.exists()) }
    }

    @Test
    fun failedCatalogCommitCannotDeleteStagedFilesBeforeRollback() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val catalog = repository.load()
        val originals = scoreFiles(score).mapValues { it.value.readBytes() }
        val staging = stageDeletion(score)
        val catalogFile = File(root, "catalog.json")
        val atomicFile = AtomicFile(catalogFile)
        val output = atomicFile.startWrite()
        try {
            assertTrue(root.setWritable(false, false))
            output.write(CatalogJson.encode(catalog.deleteScore(score.id)).toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)

            if (File("${catalogFile.path}.bak").exists()) {
                assertThrows(IOException::class.java) { runBlocking { repository.load() } }
                originals.forEach { (name, bytes) ->
                    assertArrayEquals(bytes, File(staging, name).readBytes())
                }
            } else {
                // Newer AtomicFile keeps the old base when its .new rename fails.
                assertEquals(catalog, repository.load())
            }
        } finally {
            assertTrue(root.setWritable(true, true))
            output.close()
        }

        assertEquals(catalog, LibraryRepository(root).load())
        originals.forEach { (name, bytes) ->
            assertArrayEquals(bytes, scoreFiles(score).getValue(name).readBytes())
        }
        assertFalse(staging.exists())
        repository.deleteScore(score.id)
        assertTrue(repository.load().scores.isEmpty())
        scoreFiles(score).values.forEach { assertFalse(it.exists()) }
    }

    @Test
    fun interruptedDeletionUsesRecoveredCatalogToRestoreAllFiles() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val catalog = repository.load()
        val originals = scoreFiles(score).values.associateWith(File::readBytes)
        val staging = stageDeletion(score)
        val catalogFile = File(root, "catalog.json")
        assertTrue(catalogFile.renameTo(File(root, "catalog.json.bak")))
        catalogFile.writeText(CatalogJson.encode(catalog.deleteScore(score.id)))

        assertEquals(catalog, LibraryRepository(root).load())

        originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertFalse(staging.exists())
        assertFalse(File(root, "catalog.json.bak").exists())
        assertEquals(catalog, LibraryRepository(root).load())
    }

    @Test
    fun interruptedDeletionAfterCatalogCommitFinishesCleanup() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val updated = repository.load().deleteScore(score.id)
        val staging = stageDeletion(score)
        File(root, "catalog.json").writeText(CatalogJson.encode(updated))

        assertEquals(updated, LibraryRepository(root).load())

        assertFalse(staging.exists())
        scoreFiles(score).values.forEach { assertFalse(it.exists()) }
        assertEquals(updated, LibraryRepository(root).load())
    }

    @Test
    fun stagedDeletionWithoutCatalogOrWithUnknownDataPreservesFiles() = runBlocking {
        val staging = File(root, ".deletions/score-1").apply { assertTrue(mkdirs()) }
        val pdf = File(staging, "score.pdf").apply { writeText("private score") }
        assertThrows(IOException::class.java) { runBlocking { LibraryRepository(root).load() } }
        assertEquals("private score", pdf.readText())

        File(root, "catalog.json").writeText(CatalogJson.encode(LibraryCatalog()))
        val unknown = File(staging, "unknown.txt").apply { writeText("keep unknown data") }
        assertThrows(IOException::class.java) { runBlocking { LibraryRepository(root).load() } }
        assertEquals("private score", pdf.readText())
        assertEquals("keep unknown data", unknown.readText())
    }

    @Test
    fun stagedDeletionRejectsInvalidIdsAndSymlinks() = runBlocking {
        root.mkdirs()
        File(root, "catalog.json").writeText(CatalogJson.encode(LibraryCatalog()))
        val invalid = File(root, ".deletions/invalid_id").apply { assertTrue(mkdirs()) }
        val pdf = File(invalid, "score.pdf").apply { writeText("private score") }
        assertThrows(IOException::class.java) { runBlocking { LibraryRepository(root).load() } }
        assertEquals("private score", pdf.readText())

        val preserved = File(root, "preserved")
        assertTrue(invalid.renameTo(preserved))
        val link = File(root, ".deletions/score-1")
        Os.symlink(preserved.path, link.path)
        assertThrows(IOException::class.java) { runBlocking { LibraryRepository(root).load() } }
        assertEquals("private score", File(preserved, "score.pdf").readText())
        assertTrue(link.delete())
    }

    @Test
    fun interruptedDeletionDoesNotOverwriteConflictingPdf() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val original = scoreFiles(score).getValue("score.pdf").readBytes()
        val staging = stageDeletion(score)
        val destination = File(root, "scores/${score.fileName}").apply { writeText("new data") }

        assertThrows(IOException::class.java) { runBlocking { LibraryRepository(root).load() } }

        assertEquals("new data", destination.readText())
        assertArrayEquals(original, File(staging, "score.pdf").readBytes())
        assertTrue(destination.delete())
        assertEquals(listOf(score), LibraryRepository(root).load().scores)
        assertArrayEquals(original, destination.readBytes())
        assertFalse(staging.exists())
    }

    @Test
    fun anotherRepositoryWaitsForInProgressFileOperation() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val source = createPdf("second.pdf", pages = 1)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val importing = workers.submit<Score> {
                runBlocking {
                    repository.importPdf(PdfImport("Second.pdf", "application/pdf", source.length()) {
                        opened.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Import was not released" }
                        source.inputStream()
                    })
                }
            }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            val reader = workers.submit<File> {
                reading.countDown()
                LibraryRepository(root).pdfFile(score)
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { reader.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertTrue(importing.get(5, TimeUnit.SECONDS).id != score.id)
            assertTrue(reader.get(5, TimeUnit.SECONDS).isFile)
            assertEquals(2, LibraryRepository(root).load().scores.size)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun queuedSaveCanBeCancelledBeforeStorageLockIsReleased() = runBlocking {
        val repository = LibraryRepository(root)
        val mutex = LibraryRepository::class.java.getDeclaredField("mutex")
            .apply { isAccessible = true }.get(repository) as Mutex
        mutex.lock()
        try {
            // Enter the IO context immediately and suspend on the held production mutex.
            val saving = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                repository.saveAnnotations("score-1", DocumentAnnotations())
            }
            withTimeout(1_000) { saving.cancelAndJoin() }
            assertTrue(saving.isCancelled)
            assertTrue(mutex.isLocked)
            assertFalse(File(root, "annotations/score-1.json").exists())
        } finally {
            mutex.unlock()
        }
        repository.saveAnnotations("score-1", DocumentAnnotations())
        assertTrue(File(root, "annotations/score-1.json").isFile)
    }

    @Test
    fun cancelledSaveWaitingOnAnotherRepositoryDoesNotOverwriteAnnotations() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val original = repository.loadAnnotations(score.id)
        val source = createPdf("second.pdf", pages = 1)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val importing = workers.submit<Score> {
                runBlocking {
                    repository.importPdf(PdfImport("Second.pdf", "application/pdf", source.length()) {
                        opened.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Import was not released" }
                        source.inputStream()
                    })
                }
            }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            val waitingThread = AtomicReference<Thread>()
            val saving = launch(Dispatchers.IO) {
                waitingThread.set(Thread.currentThread())
                LibraryRepository(root).saveAnnotations(score.id, DocumentAnnotations())
            }
            withTimeout(5_000) {
                while (waitingThread.get()?.state != Thread.State.BLOCKED) delay(10)
            }
            saving.cancel()
            release.countDown()
            withTimeout(5_000) { saving.join() }
            assertTrue(saving.isCancelled)
            importing.get(5, TimeUnit.SECONDS)

            assertEquals(original, LibraryRepository(root).loadAnnotations(score.id))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun loadRemovesOnlyThisLibraryInterruptedBackupArchive() = runBlocking {
        val repository = LibraryRepository(root)
        repository.createSetlist("Keep")
        val catalog = repository.load()
        val interrupted = File(root.parentFile, ".${root.name}-backup.zip")
        val foreign = File(root.parentFile, ".${root.name}-other-backup.zip")
        val legacy = File(root.parentFile, "sheetset-backup-${UUID.randomUUID()}.zip")
        try {
            interrupted.writeText("interrupted archive")
            foreign.writeText("another library")
            legacy.writeText("legacy archive")

            assertEquals(catalog, LibraryRepository(root).load())

            assertFalse(interrupted.exists())
            assertEquals("another library", foreign.readText())
            assertEquals("legacy archive", legacy.readText())
        } finally {
            listOf(interrupted, foreign, legacy).forEach(File::delete)
        }
    }

    @Test
    fun nativeBackupCanReplaceCorruptCatalog() = runBlocking {
        val repository = LibraryRepository(root)
        val score = createAnnotatedScore(repository)
        val catalog = repository.load()
        val annotations = repository.loadAnnotations(score.id)
        val archive = ByteArrayOutputStream()
        repository.createBackup(archive, AppSettings(), null)
        File(root, "catalog.json").writeText("{")

        repository.restoreBackup(archive.toByteArray().inputStream())

        assertEquals(catalog, repository.load())
        assertEquals(annotations, repository.loadAnnotations(score.id))
        assertFalse(File(root.parentFile, ".${root.name}-backup.zip").exists())
    }

    @Test
    fun bulkAddPreservesOrderAndDuplicatesWithoutPartialUpdates() = runBlocking {
        val source = createPdf("song.pdf", pages = 1)
        val repository = LibraryRepository(root)
        val first = repository.importPdf(
            PdfImport("First.pdf", "application/pdf", source.length(), source::inputStream),
        )
        val second = repository.importPdf(
            PdfImport("Second.pdf", "application/pdf", source.length(), source::inputStream),
        )
        val setlist = repository.createSetlist("Show")
        repository.addScoreToSetlist(setlist.id, first.id)
        val before = repository.load()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addScoresToSetlist(setlist.id, listOf(second.id, "missing", first.id))
            }
        }
        assertEquals(before, LibraryRepository(root).load())
        repository.addScoresToSetlist(setlist.id, listOf(second.id, first.id, first.id))

        assertEquals(
            listOf(first.id, second.id, first.id, first.id),
            LibraryRepository(root).load().setlists.single().scoreIds,
        )
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

    private suspend fun createAnnotatedScore(repository: LibraryRepository): Score {
        val source = createPdf("private.pdf", pages = 1)
        val score = repository.importPdf(
            PdfImport("Private.pdf", "application/pdf", source.length(), source::inputStream),
        )
        val setlist = repository.createSetlist("Show")
        repository.addScoreToSetlist(setlist.id, score.id)
        repository.saveAnnotations(score.id, DocumentAnnotations(
            mapOf(0 to listOf(InkAnnotation(
                id = "private-ink",
                kind = InkKind.PEN,
                width = 0.004f,
                points = listOf(NormalizedPoint(0.25f, 0.75f)),
            ))),
        ))
        val annotations = File(root, "annotations/${score.id}.json")
        listOf(".bak", ".new").forEach { annotations.copyTo(File("${annotations.path}$it")) }
        return score
    }

    private fun scoreFiles(score: Score): Map<String, File> = mapOf(
        "annotations.json.bak" to File(root, "annotations/${score.id}.json.bak"),
        "annotations.json.new" to File(root, "annotations/${score.id}.json.new"),
        "annotations.json" to File(root, "annotations/${score.id}.json"),
        "score.pdf" to File(root, "scores/${score.fileName}"),
    )

    private fun stageDeletion(score: Score): File {
        val staging = File(root, ".deletions/${score.id}").apply { assertTrue(mkdirs()) }
        scoreFiles(score).forEach { (name, file) -> assertTrue(file.renameTo(File(staging, name))) }
        return staging
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
