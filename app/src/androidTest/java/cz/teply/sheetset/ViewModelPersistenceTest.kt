package cz.teply.sheetset

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.teply.sheetset.data.LibraryRepository
import cz.teply.sheetset.data.PdfImport
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.InkAnnotation
import cz.teply.sheetset.pdf.InkKind
import cz.teply.sheetset.pdf.NormalizedPoint
import cz.teply.sheetset.settings.AppLanguages
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.ReaderLayout
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewModelPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var application: IsolatedApplication
    private lateinit var viewModel: SheetSetViewModel
    private lateinit var repository: LibraryRepository
    private lateinit var score: Score
    private val original = DocumentAnnotations(mapOf(0 to listOf(ink("original"))))

    @Before
    fun setUp(): Unit = runBlocking {
        root = File(instrumentation.targetContext.cacheDir, "shared-backups/persistence-${UUID.randomUUID()}")
        check(root.mkdirs())
        application = IsolatedApplication(instrumentation.targetContext, root)
        val initialRepository = LibraryRepository(File(application.filesDir, "library"))
        val pdf = File(root, "source.pdf")
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        score = initialRepository.importPdf(
            PdfImport("Source.pdf", "application/pdf", pdf.length(), pdf::inputStream),
        )
        initialRepository.saveAnnotations(score.id, original)
        instrumentation.runOnMainSync { viewModel = SheetSetViewModel(application) }
        repository = SheetSetViewModel::class.java.getDeclaredField("repository")
            .apply { isAccessible = true }.get(viewModel) as LibraryRepository
        awaitState { !it.loading }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        if (::viewModel.isInitialized) {
            val job = viewModel.viewModelScope.coroutineContext[Job]
            instrumentation.runOnMainSync { viewModel.viewModelScope.cancel() }
            job?.join()
        }
        if (::application.isInitialized) {
            instrumentation.targetContext.deleteSharedPreferences("${root.name}-sheetset-settings")
        }
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun reopeningFailedSaveRetainsBothStrokesAfterRetry() = runBlocking {
        instrumentation.runOnMainSync { viewModel.openScore(score) }
        awaitState { it.reader != null }
        val annotationsDirectory = File(application.filesDir, "library/annotations")
        val savedDirectory = File(application.filesDir, "library/annotations-before-failure")
        check(annotationsDirectory.renameTo(savedDirectory))
        annotationsDirectory.writeText("Block annotation writes with a regular file")
        val firstEdit = original.withPage(0, original.pages.getValue(0) + ink("first"))
        try {
            instrumentation.runOnMainSync { viewModel.saveAnnotations(score.id, 0, firstEdit) }
            awaitState { it.error }
        } finally {
            check(annotationsDirectory.delete())
            check(savedDirectory.renameTo(annotationsDirectory))
        }

        instrumentation.runOnMainSync {
            viewModel.closeReader()
            viewModel.openScore(score)
        }
        val reopened = requireNotNull(awaitState { it.reader != null && !it.loading }.reader)
        assertEquals(firstEdit, reopened.annotations)
        val secondEdit = reopened.annotations.withPage(0, reopened.annotations.pages.getValue(0) + ink("second"))
        instrumentation.runOnMainSync { viewModel.saveAnnotations(score.id, 0, secondEdit) }
        withTimeout(5_000) {
            while (repository.loadAnnotations(score.id) != secondEdit) delay(10)
        }
        assertEquals(
            listOf("original", "first", "second"),
            repository.loadAnnotations(score.id).pages.getValue(0).map { it.id },
        )
    }

    @Test
    fun closeAndSupersedingOpenCancelPendingReaderLoads() = runBlocking {
        val mutex = SheetSetViewModel::class.java.getDeclaredField("annotationSaveMutex")
            .apply { isAccessible = true }.get(viewModel) as Mutex
        mutex.lock()
        val closed: Job
        try {
            instrumentation.runOnMainSync { viewModel.openScore(score) }
            closed = readerOpenJob()
            instrumentation.runOnMainSync { viewModel.closeReader() }
        } finally {
            mutex.unlock()
        }
        withTimeout(5_000) { closed.join() }
        assertNull(viewModel.state.value.reader)
        assertFalse(viewModel.state.value.loading)

        mutex.lock()
        val superseded: Job
        val latest: Job
        try {
            instrumentation.runOnMainSync { viewModel.openScoreAt(score, 0) }
            superseded = readerOpenJob()
            instrumentation.runOnMainSync { viewModel.openScoreAt(score, 1) }
            latest = readerOpenJob()
        } finally {
            mutex.unlock()
        }
        withTimeout(5_000) {
            latest.join()
            superseded.join()
        }
        assertEquals(1, viewModel.state.value.reader?.pageIndex)
    }

    @Test
    fun delayedBookmarkRefreshPreservesLatestPositionAfterReaderCloses() = runBlocking {
        instrumentation.runOnMainSync { viewModel.openScore(score) }
        awaitState { it.reader != null && !it.loading }
        val mutex = repositoryMutex()
        mutex.lock()
        try {
            instrumentation.runOnMainSync {
                viewModel.addBookmark("Keep this passage")
                viewModel.nextPage(ReaderLayout.SINGLE)
                viewModel.closeReader()
            }
        } finally {
            mutex.unlock()
        }
        val mutationMutex = SheetSetViewModel::class.java.getDeclaredField("libraryMutationMutex")
            .apply { isAccessible = true }.get(viewModel) as Mutex
        withTimeout(5_000) { mutationMutex.lock() }
        mutationMutex.unlock()

        val savedScore = repository.load().scores.single()
        assertEquals(1, savedScore.lastPageIndex)
        assertEquals(1, savedScore.bookmarks.size)
        val visibleScore = viewModel.state.value.catalog.scores.single()
        assertEquals(savedScore, visibleScore)
        instrumentation.runOnMainSync { viewModel.openScore(visibleScore) }
        assertEquals(1, awaitState { it.reader != null && !it.loading }.reader?.pageIndex)
    }

    @Test
    fun repeatedScoreOpenCannotOvertakeQueuedPositionSave() = runBlocking {
        instrumentation.runOnMainSync { viewModel.createSetlist("Repeated score position") }
        val setlistId = awaitState { !it.loading && it.catalog.setlists.size == 1 }.catalog.setlists.single().id
        instrumentation.runOnMainSync { viewModel.addScores(setlistId, listOf(score.id, score.id)) }
        awaitState { !it.loading && it.catalog.setlists.single().scoreIds.size == 2 }
        instrumentation.runOnMainSync { viewModel.openSetlistScore(setlistId, 0) }
        awaitState { it.reader != null && !it.loading }
        val field = SheetSetViewModel::class.java.getDeclaredField("libraryMutationMutex")
            .apply { isAccessible = true }
        val mutex = field.get(viewModel) as Mutex
        val lockCalls = AtomicInteger()
        val openingQueued = CompletableDeferred<Unit>()
        field.set(viewModel, object : Mutex by mutex {
            override suspend fun lock(owner: Any?) {
                if (lockCalls.incrementAndGet() == 2) openingQueued.complete(Unit)
                mutex.lock(owner)
            }
        })
        mutex.lock()
        try {
            val opening: Job
            try {
                instrumentation.runOnMainSync {
                    viewModel.nextPage(ReaderLayout.SINGLE)
                    viewModel.nextPage(ReaderLayout.SINGLE)
                }
                opening = readerOpenJob()
                withTimeout(5_000) {
                    // Release when opening queues correctly or incorrectly bypasses the pending save.
                    select<Unit> {
                        openingQueued.onAwait { }
                        opening.onJoin { }
                    }
                }
            } finally {
                mutex.unlock()
            }
            withTimeout(5_000) {
                opening.join()
                mutex.lock()
            }
            mutex.unlock()
            val reader = requireNotNull(viewModel.state.value.reader)
            assertEquals(1, reader.scoreIndex)
            assertEquals(0, reader.pageIndex)
            assertEquals(0, repository.load().scores.single().lastPageIndex)
            assertEquals(0, viewModel.state.value.catalog.scores.single().lastPageIndex)
            assertFalse(viewModel.state.value.error)
        } finally {
            field.set(viewModel, mutex)
        }
    }

    @Test
    fun failedSecondImportStillPublishesFirstCommittedScore() = runBlocking {
        val valid = File(root, "source.pdf")
        val malformed = File(root, "malformed.pdf").apply { writeText("Not a PDF") }
        instrumentation.runOnMainSync {
            viewModel.importPdfs(listOf(Uri.fromFile(valid), Uri.fromFile(malformed)))
        }
        val failed = awaitState { it.error && !it.loading }
        val committed = repository.load()
        assertEquals(2, committed.scores.size)
        assertEquals(committed, failed.catalog)
        assertTrue(committed.scores.all { repository.pdfFile(it).isFile })
    }

    @Test
    fun repeatedScoreTransitionPreservesStrokeSavedAfterLoadingAnnotations() = runBlocking {
        instrumentation.runOnMainSync { viewModel.createSetlist("Repeated score") }
        val setlistId = awaitState { !it.loading && it.catalog.setlists.size == 1 }.catalog.setlists.single().id
        instrumentation.runOnMainSync { viewModel.addScores(setlistId, listOf(score.id, score.id)) }
        awaitState { !it.loading && it.catalog.setlists.single().scoreIds.size == 2 }
        instrumentation.runOnMainSync { viewModel.openSetlistScore(setlistId, 0) }
        awaitState { it.reader != null && !it.loading }
        instrumentation.runOnMainSync { viewModel.jumpToPage(1) }
        val mutationMutex = SheetSetViewModel::class.java.getDeclaredField("libraryMutationMutex")
            .apply { isAccessible = true }.get(viewModel) as Mutex
        mutationMutex.lock()
        mutationMutex.unlock()

        val mutexField = LibraryRepository::class.java.getDeclaredField("mutex").apply { isAccessible = true }
        val mutex = repositoryMutex()
        val lockCalls = AtomicInteger()
        val annotationsLoaded = CompletableDeferred<Unit>()
        val continueOpening = CompletableDeferred<Unit>()
        val saves = SheetSetViewModel::class.java.getDeclaredField("annotationSaves")
            .apply { isAccessible = true }.get(viewModel) as AnnotationSaveCoordinator
        mutexField.set(repository, object : Mutex by mutex {
            override suspend fun lock(owner: Any?) {
                // Pause the position write after the first lock loaded the annotation snapshot.
                if (lockCalls.incrementAndGet() == 2) {
                    annotationsLoaded.complete(Unit)
                    continueOpening.await()
                }
                mutex.lock(owner)
            }
        })
        try {
            instrumentation.runOnMainSync { viewModel.nextPage(ReaderLayout.SINGLE) }
            withTimeout(5_000) { annotationsLoaded.await() }
            val previousReader = requireNotNull(viewModel.state.value.reader)
            assertEquals(1, previousReader.pageIndex)
            val edited = previousReader.annotations.withPage(1, listOf(ink("last-page-during-open")))
            instrumentation.runOnMainSync { viewModel.saveAnnotations(score.id, 1, edited) }
            withTimeout(5_000) { saves.awaitLatest() }
            assertNull(saves.pendingAnnotations(score.id))
            assertEquals(edited, repository.loadAnnotations(score.id))

            continueOpening.complete(Unit)
            withTimeout(5_000) { readerOpenJob().join() }
            val opened = requireNotNull(viewModel.state.value.reader)
            assertEquals(1, opened.scoreIndex)
            assertEquals(0, opened.pageIndex)
            val nextEdit = opened.annotations.withPage(0, opened.annotations.pages.getValue(0) + ink("first-page-after-open"))
            instrumentation.runOnMainSync { viewModel.saveAnnotations(score.id, 0, nextEdit) }
            withTimeout(5_000) { saves.awaitLatest() }
            val persisted = repository.loadAnnotations(score.id)
            assertEquals(listOf("last-page-during-open"), persisted.pages.getValue(1).map { it.id })
            assertEquals(listOf("original", "first-page-after-open"), persisted.pages.getValue(0).map { it.id })
        } finally {
            continueOpening.complete(Unit)
            mutexField.set(repository, mutex)
        }
    }

    @Test
    fun restoreRejectsReaderOpensAndMutationsUntilReplacementCompletes() = runBlocking {
        val backup = File(root, "restore.zip")
        backup.outputStream().use { repository.createBackup(it, AppSettings(), AppLanguages.currentTag(application)) }
        val newer = DocumentAnnotations(mapOf(0 to listOf(ink("current"))))
        repository.saveAnnotations(score.id, newer)
        val mutex = repositoryMutex()
        mutex.lock()
        val oldOpen: Job
        try {
            instrumentation.runOnMainSync { viewModel.openScore(score) }
            oldOpen = readerOpenJob()
            instrumentation.runOnMainSync {
                viewModel.restoreBackup(Uri.fromFile(backup))
                viewModel.openScore(score)
                viewModel.createSetlist("Must not be created")
                viewModel.renameScore(score.id, "Must not be renamed")
                viewModel.saveAnnotations(score.id, 0, newer)
                viewModel.updateSettings(AppSettings(keepScreenAwake = false))
            }
            assertNull(viewModel.state.value.reader)
            assertTrue(viewModel.state.value.loading)
        } finally {
            mutex.unlock()
        }

        withTimeout(5_000) { oldOpen.join() }
        val restored = awaitState { !it.loading }
        assertFalse(restored.error)
        assertNull(restored.reader)
        assertTrue(restored.catalog.setlists.isEmpty())
        assertEquals(score.title, restored.catalog.scores.single().title)
        assertEquals(AppSettings(), restored.settings)
        assertEquals(original, repository.loadAnnotations(score.id))
        instrumentation.runOnMainSync { viewModel.openScore(score) }
        assertEquals(original, awaitState { it.reader != null }.reader?.annotations)
    }

    @Test
    fun overlappingSharesPublishDistinctCompleteArchives() = runBlocking {
        val first = CompletableDeferred<Uri>()
        val second = CompletableDeferred<Uri>()
        val mutex = repositoryMutex()
        mutex.lock()
        try {
            instrumentation.runOnMainSync { viewModel.createSharedBackup { first.complete(it) } }
            withTimeout(5_000) {
                while (sharedDirectory().listFiles().orEmpty().none { it.extension == "tmp" }) delay(10)
            }
            instrumentation.runOnMainSync { viewModel.createSharedBackup { second.complete(it) } }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
        } finally {
            mutex.unlock()
        }

        val uris = withTimeout(5_000) { listOf(first.await(), second.await()) }
        assertNotEquals(uris[0], uris[1])
        uris.forEachIndexed { index, uri ->
            val restored = LibraryRepository(File(root, "shared-restore-$index"))
            requireNotNull(application.contentResolver.openInputStream(uri)).use { restored.restoreBackup(it) }
            assertEquals(score.id, restored.load().scores.single().id)
            assertEquals(original, restored.loadAnnotations(score.id))
        }
        assertTrue(sharedDirectory().listFiles().orEmpty().none { it.extension == "tmp" })
    }

    @Test
    fun shareCacheKeepsThreeRecentArchivesAndRemovesAbandonedTemporaryFiles() = runBlocking {
        val directory = sharedDirectory().apply { check(mkdirs()) }
        val now = System.currentTimeMillis()
        val recent = (1..4).map { index ->
            File(directory, "SheetSet-Backup-$index.zip").apply {
                writeText("Old cached export")
                check(setLastModified(now - index * 1_000L))
            }
        }
        val expired = File(directory, "SheetSet-Backup-expired.zip").apply {
            writeText("Expired cached export")
            check(setLastModified(now - 25L * 60L * 60L * 1_000L))
        }
        val abandoned = File(directory, ".SheetSet-Backup-abandoned.tmp").apply { writeText("Interrupted export") }
        val ready = CompletableDeferred<Uri>()
        instrumentation.runOnMainSync { viewModel.createSharedBackup { ready.complete(it) } }
        withTimeout(5_000) { ready.await() }

        assertEquals(3, directory.listFiles().orEmpty().count { it.extension == "zip" })
        assertTrue(recent.take(2).all(File::exists))
        assertTrue(recent.drop(2).none(File::exists))
        assertFalse(expired.exists())
        assertFalse(abandoned.exists())
    }

    private suspend fun awaitState(predicate: (LibraryUiState) -> Boolean): LibraryUiState =
        withTimeout(5_000) { viewModel.state.first(predicate) }

    private fun readerOpenJob(): Job = SheetSetViewModel::class.java.getDeclaredField("readerOpenJob")
        .apply { isAccessible = true }.get(viewModel) as Job

    private fun repositoryMutex(): Mutex = LibraryRepository::class.java.getDeclaredField("mutex")
        .apply { isAccessible = true }.get(repository) as Mutex

    private fun sharedDirectory(): File = File(application.cacheDir, "shared-backups")

    private class IsolatedApplication(context: Context, private val root: File) : Application() {
        init {
            attachBaseContext(context)
        }

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("${root.name}-$name", mode)
    }

    private companion object {
        fun ink(id: String): InkAnnotation = InkAnnotation(
            id = id,
            kind = InkKind.PEN,
            width = 0.004f,
            points = listOf(NormalizedPoint(0.2f, 0.3f)),
        )
    }
}
