package cz.teply.sheetset

import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.InkAnnotation
import cz.teply.sheetset.pdf.InkKind
import cz.teply.sheetset.pdf.NormalizedPoint
import cz.teply.sheetset.pdf.PageAnnotation
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AnnotationSaveVersionsTest {
    @Test
    fun rapidSamePagePersistsLatestSnapshot() = runCoordinatorTest { scope ->
        val persisted = ConcurrentHashMap<String, DocumentAnnotations>()
        val coordinator = coordinator(scope) { scoreId, annotations ->
            persisted[scoreId] = annotations
        }

        coordinator.enqueue("score-a", 0, listOf(ink("first")), DocumentAnnotations())
        coordinator.enqueue("score-a", 0, listOf(ink("second")), DocumentAnnotations())
        coordinator.awaitLatest()

        assertEquals(listOf("second"), persisted.getValue("score-a").pageIds(0))
    }

    @Test
    fun pageZeroThenPageOnePersistsBothPages() = runCoordinatorTest { scope ->
        val persisted = ConcurrentHashMap<String, DocumentAnnotations>()
        val coordinator = coordinator(scope) { scoreId, annotations ->
            persisted[scoreId] = annotations
        }
        val pageZero = DocumentAnnotations(mapOf(0 to listOf(ink("page-0"))))

        coordinator.enqueue("score-a", 0, listOf(ink("page-0")), DocumentAnnotations())
        coordinator.enqueue("score-a", 1, listOf(ink("page-1")), pageZero)
        coordinator.awaitLatest()

        assertEquals(listOf("page-0"), persisted.getValue("score-a").pageIds(0))
        assertEquals(listOf("page-1"), persisted.getValue("score-a").pageIds(1))
    }

    @Test
    fun delayedOldPageCallbackPreservesCurrentNavigationAndMetadata() {
        val original = readerState(
            scoreId = "score-a",
            pageIndex = 1,
            pagePart = 1,
            annotations = DocumentAnnotations(mapOf(1 to listOf(ink("page-1")))),
        )

        val updated = original.mergePageAnnotations("score-a", 0, listOf(ink("page-0")))
        val reader = requireNotNull(updated.reader)

        assertEquals(original.reader?.score, reader.score)
        assertEquals(1, reader.pageIndex)
        assertEquals(1, reader.pagePart)
        assertEquals(listOf("page-0"), reader.annotations.pageIds(0))
        assertEquals(listOf("page-1"), reader.annotations.pageIds(1))
    }

    @Test
    fun callbackForAnotherScoreDoesNotOverwriteActiveReader() {
        val original = readerState(
            scoreId = "score-b",
            pageIndex = 2,
            pagePart = 1,
            annotations = DocumentAnnotations(mapOf(2 to listOf(ink("score-b")))),
        )

        assertEquals(
            original,
            original.mergePageAnnotations("score-a", 0, listOf(ink("score-a"))),
        )
    }

    @Test
    fun independentScoresKeepIndependentLatestSnapshots() = runCoordinatorTest { scope ->
        val persisted = ConcurrentHashMap<String, DocumentAnnotations>()
        val coordinator = coordinator(scope) { scoreId, annotations ->
            persisted[scoreId] = annotations
        }

        coordinator.enqueue("score-a", 0, listOf(ink("a-old")), DocumentAnnotations())
        coordinator.enqueue("score-b", 0, listOf(ink("b")), DocumentAnnotations())
        coordinator.enqueue("score-a", 0, listOf(ink("a-new")), DocumentAnnotations())
        coordinator.awaitLatest()

        assertEquals(listOf("a-new"), persisted.getValue("score-a").pageIds(0))
        assertEquals(listOf("b"), persisted.getValue("score-b").pageIds(0))
    }

    @Test
    fun backupWaitsForLatestSuccessfulSave() = runCoordinatorTest { scope ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator(scope) { _, _ ->
            started.complete(Unit)
            release.await()
        }
        coordinator.enqueue("score-a", 0, listOf(ink("ink")), DocumentAnnotations())
        withTimeout(1_000) { started.await() }

        assertNull(withTimeoutOrNull(100) { coordinator.awaitLatest(); true })
        release.complete(Unit)
        withTimeout(1_000) { coordinator.awaitLatest() }
    }

    @Test
    fun latestFailureRemainsObservableToBackup() = runCoordinatorTest { scope ->
        val coordinator = coordinator(scope) { _, _ -> throw IOException("latest failed") }
        coordinator.enqueue("score-a", 0, listOf(ink("ink")), DocumentAnnotations()).join()

        val error = try {
            coordinator.awaitLatest()
            null
        } catch (error: IOException) {
            error
        }

        assertEquals("latest failed", error?.message)
    }

    @Test
    fun latestCancellationRemainsObservableToBackup() = runCoordinatorTest { scope ->
        val coordinator = coordinator(scope) { _, _ ->
            throw CancellationException("latest cancelled")
        }
        coordinator.enqueue("score-a", 0, listOf(ink("ink")), DocumentAnnotations()).join()

        val error = try {
            coordinator.awaitLatest()
            null
        } catch (error: CancellationException) {
            error
        }

        assertEquals("latest cancelled", error?.message)
    }

    @Test
    fun successfulLatestReleasesEntireScoreState() = runCoordinatorTest { scope ->
        val persisted = ConcurrentHashMap<String, DocumentAnnotations>()
        val coordinator = coordinator(scope) { scoreId, annotations ->
            persisted[scoreId] = annotations
        }
        coordinator.enqueue("score-a", 0, listOf(ink("durable")), DocumentAnnotations())
        coordinator.awaitLatest()
        assertNull(coordinator.pendingAnnotations("score-a"))

        val nextOrigin = DocumentAnnotations(mapOf(1 to listOf(ink("next"))))
        coordinator.enqueue("score-a", 1, listOf(ink("next")), nextOrigin)
        coordinator.awaitLatest()

        assertEquals(emptyList<String>(), persisted.getValue("score-a").pageIds(0))
        assertEquals(listOf("next"), persisted.getValue("score-a").pageIds(1))
    }

    @Test
    fun failedLatestRetainsAnnotationsForNewerRequest() = runCoordinatorTest { scope ->
        val calls = AtomicInteger()
        val persisted = ConcurrentHashMap<String, DocumentAnnotations>()
        val coordinator = coordinator(scope) { scoreId, annotations ->
            if (calls.incrementAndGet() == 1) throw IOException("failed")
            persisted[scoreId] = annotations
        }
        coordinator.enqueue("score-a", 0, listOf(ink("failed-page")), DocumentAnnotations()).join()
        assertEquals(listOf("failed-page"), coordinator.pendingAnnotations("score-a")?.pageIds(0))

        val nextOrigin = DocumentAnnotations(mapOf(1 to listOf(ink("next-page"))))
        coordinator.enqueue("score-a", 1, listOf(ink("next-page")), nextOrigin)
        coordinator.awaitLatest()

        assertEquals(listOf("failed-page"), persisted.getValue("score-a").pageIds(0))
        assertEquals(listOf("next-page"), persisted.getValue("score-a").pageIds(1))
        assertNull(coordinator.pendingAnnotations("score-a"))
    }

    @Test
    fun olderFailureIsSupersededByLatestSuccess() = runCoordinatorTest { scope ->
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val persisted = ConcurrentHashMap<String, DocumentAnnotations>()
        val coordinator = coordinator(scope) { scoreId, annotations ->
            if (calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                throw IOException("older failed")
            }
            persisted[scoreId] = annotations
        }

        coordinator.enqueue("score-a", 0, listOf(ink("old")), DocumentAnnotations())
        withTimeout(1_000) { firstStarted.await() }
        val latest = coordinator.enqueue(
            "score-a",
            0,
            listOf(ink("new")),
            DocumentAnnotations(),
        )
        releaseFirst.complete(Unit)
        withTimeout(1_000) { latest.join() }

        coordinator.awaitLatest()
        assertEquals(listOf("new"), persisted.getValue("score-a").pageIds(0))
    }

    @Test
    fun olderCompletionCannotRemoveNewerTracker() = runCoordinatorTest { scope ->
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = coordinator(scope) { _, _ ->
            if (calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                secondStarted.complete(Unit)
                releaseSecond.await()
            }
        }

        val older = coordinator.enqueue("score-a", 0, listOf(ink("old")), DocumentAnnotations())
        withTimeout(1_000) { firstStarted.await() }
        coordinator.enqueue("score-a", 0, listOf(ink("new")), DocumentAnnotations())
        releaseFirst.complete(Unit)
        withTimeout(1_000) { older.join() }
        withTimeout(1_000) { secondStarted.await() }

        assertNull(withTimeoutOrNull(100) { coordinator.awaitLatest(); true })
        releaseSecond.complete(Unit)
        withTimeout(1_000) { coordinator.awaitLatest() }
    }

    @Test
    fun scoreDeletionWaitsThenClearsCoordinatorState() = runCoordinatorTest { scope ->
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = ConcurrentHashMap<String, DocumentAnnotations>()
        val coordinator = coordinator(scope) { scoreId, annotations ->
            started.complete(Unit)
            release.await()
            persisted[scoreId] = annotations
        }
        coordinator.enqueue("score-a", 0, listOf(ink("old")), DocumentAnnotations())
        withTimeout(1_000) { started.await() }

        val deletion = scope.async { coordinator.discard("score-a") }
        assertFalse(deletion.isCompleted)
        release.complete(Unit)
        withTimeout(1_000) { deletion.await() }

        val fresh = DocumentAnnotations(mapOf(1 to listOf(ink("seed"))))
        coordinator.enqueue("score-a", 1, listOf(ink("seed")), fresh)
        coordinator.awaitLatest()
        assertEquals(emptyList<String>(), persisted.getValue("score-a").pageIds(0))
        assertEquals(listOf("seed"), persisted.getValue("score-a").pageIds(1))
    }

    private fun coordinator(
        scope: CoroutineScope,
        persist: suspend (String, DocumentAnnotations) -> Unit,
    ): AnnotationSaveCoordinator = AnnotationSaveCoordinator(scope, Mutex(), persist)

    private fun runCoordinatorTest(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(scope)
        } finally {
            scope.cancel()
        }
    }

    private fun readerState(
        scoreId: String,
        pageIndex: Int,
        pagePart: Int,
        annotations: DocumentAnnotations,
    ): LibraryUiState {
        val score = Score(
            id = scoreId,
            title = "Current title",
            fileName = "$scoreId.pdf",
            pageCount = 3,
            importedAtEpochMs = 1L,
            lastPageIndex = pageIndex,
            lastPagePart = pagePart,
            lastViewedAtEpochMs = 2L,
        )
        return LibraryUiState(
            catalog = LibraryCatalog(scores = listOf(score)),
            reader = ReaderUiState(
                score = score,
                file = File(score.fileName),
                scoreIds = listOf(score.id),
                scoreIndex = 0,
                pageIndex = pageIndex,
                pagePart = pagePart,
                annotations = annotations,
            ),
        )
    }

    private fun ink(id: String): InkAnnotation = InkAnnotation(
        id = id,
        kind = InkKind.PEN,
        width = 0.004f,
        points = listOf(NormalizedPoint(0.2f, 0.3f)),
        color = AnnotationColor.BLACK,
    )

    private fun DocumentAnnotations.pageIds(page: Int): List<String> =
        pages[page].orEmpty().map(PageAnnotation::id)
}
