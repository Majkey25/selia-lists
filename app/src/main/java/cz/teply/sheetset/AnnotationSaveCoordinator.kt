package cz.teply.sheetset

import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.PageAnnotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AnnotationSaveCoordinator(
    private val scope: CoroutineScope,
    private val persistenceMutex: Mutex,
    private val persist: suspend (String, DocumentAnnotations) -> Unit,
) {
    private data class ScoreState(
        val version: Long,
        val annotations: DocumentAnnotations,
        val latest: Deferred<Unit>?,
    )

    private val states = mutableMapOf<String, ScoreState>()

    fun enqueue(
        scoreId: String,
        pageIndex: Int,
        pageAnnotations: List<PageAnnotation>,
        baseAnnotations: DocumentAnnotations,
    ): Deferred<Unit> {
        lateinit var result: Deferred<Unit>
        synchronized(states) {
            val previous = states[scoreId]
            val version = (previous?.version ?: 0L) + 1L
            val annotations = (previous?.annotations ?: baseAnnotations)
                .withPage(pageIndex, pageAnnotations)
            result = scope.async(start = CoroutineStart.LAZY) {
                persistenceMutex.withLock {
                    val current = synchronized(states) {
                        states[scoreId]?.takeIf { it.version == version }?.annotations
                    } ?: return@withLock
                    persist(scoreId, current)
                }
            }
            states[scoreId] = ScoreState(version, annotations, result)
            result.invokeOnCompletion { error ->
                if (error == null) removeSuccessfulLatest(scoreId, result)
            }
        }
        result.start()
        return result
    }

    suspend fun awaitLatest() {
        val snapshot = synchronized(states) { states.values.mapNotNull(ScoreState::latest) }
        snapshot.awaitAll()
    }

    fun pendingAnnotations(scoreId: String): DocumentAnnotations? =
        synchronized(states) { states[scoreId]?.annotations }

    fun isLatest(scoreId: String, result: Deferred<Unit>): Boolean =
        synchronized(states) { states[scoreId]?.latest === result }

    suspend fun discard(scoreId: String) {
        while (true) {
            val tracked = synchronized(states) { states[scoreId]?.latest } ?: break
            tracked.join()
            synchronized(states) {
                val current = states[scoreId]
                if (current?.latest === tracked) {
                    states[scoreId] = current.copy(latest = null)
                }
            }
        }
        synchronized(states) {
            if (states[scoreId]?.latest == null) states.remove(scoreId)
        }
    }

    suspend fun discardAll() {
        val scoreIds = synchronized(states) { states.keys.toList() }
        scoreIds.forEach { discard(it) }
    }

    private fun removeSuccessfulLatest(scoreId: String, result: Deferred<Unit>) {
        synchronized(states) {
            val current = states[scoreId]
            if (current?.latest === result) {
                states.remove(scoreId)
            }
        }
    }
}

internal fun LibraryUiState.mergePageAnnotations(
    scoreId: String,
    pageIndex: Int,
    pageAnnotations: List<PageAnnotation>,
): LibraryUiState {
    val current = reader ?: return this
    if (current.score.id != scoreId) return this
    return copy(
        reader = current.copy(
            annotations = current.annotations.withPage(pageIndex, pageAnnotations),
        ),
    )
}
