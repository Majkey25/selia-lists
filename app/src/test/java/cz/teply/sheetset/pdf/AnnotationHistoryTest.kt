package cz.teply.sheetset.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import cz.teply.sheetset.settings.AnnotationTextSize

class AnnotationHistoryTest {
    @Test
    fun aggregateTextBudgetRejectsAddUpdateAndDuplicateWithoutChangingHistory() {
        val labels = List(14) { index ->
            TextBoxAnnotation(
                "text-$index", NormalizedRect(0.1f, 0.1f, 0.5f, 0.3f),
                "a".repeat(MAX_TEXT_LENGTH), AnnotationTextSize.MEDIUM,
            )
        }
        val short = labels.first().copy(id = "short", text = "a")
        val ink = first.copy(points = List(MAX_POINTS_PER_INK) { NormalizedPoint(0.1f, 0.2f) })
        val history = AnnotationHistory(labels + short + ink)
        assertThrows(AnnotationLimitException::class.java) {
            history.add(short.copy(id = "new"))
        }
        assertThrows(AnnotationLimitException::class.java) {
            history.update(short.copy(text = "a".repeat(MAX_TEXT_LENGTH)))
        }
        assertThrows(AnnotationLimitException::class.java) {
            history.commit(history.annotations + labels.first().copy(id = "duplicate"))
        }
        assertEquals(labels + short + ink, history.annotations)
    }

    private val first = InkAnnotation(
        id = "first",
        kind = InkKind.PEN,
        width = 0.004f,
        points = listOf(NormalizedPoint(0.1f, 0.2f), NormalizedPoint(0.3f, 0.4f)),
    )
    private val second = InkAnnotation(
        id = "second",
        kind = InkKind.HIGHLIGHTER,
        width = 0.02f,
        points = listOf(NormalizedPoint(0.5f, 0.5f)),
    )

    @Test
    fun `annotation can be undone and redone`() {
        val added = AnnotationHistory().add(first)
        val undone = added.undo()

        assertTrue(undone.annotations.isEmpty())
        assertEquals(listOf(first), undone.redo().annotations)
    }

    @Test
    fun `new annotation clears redo history`() {
        val history = AnnotationHistory().add(first).undo().add(second)

        assertEquals(listOf(second), history.redo().annotations)
    }

    @Test
    fun `annotation JSON survives round trip`() {
        val annotations = DocumentAnnotations(mapOf(2 to listOf(first, second)))

        assertEquals(
            annotations,
            AnnotationJson.decode(AnnotationJson.encode(annotations)),
        )
    }

    @Test
    fun updateDeleteUndoAndRedoUseIds() {
        val text = TextBoxAnnotation(
            id = "text",
            bounds = NormalizedRect(0.1f, 0.1f, 0.5f, 0.3f),
            text = "Original",
            size = AnnotationTextSize.MEDIUM,
        )
        val changed = AnnotationHistory().add(text).update(text.copy(text = "Changed"))
        val deleted = changed.delete(text.id)

        assertEquals(
            "Changed",
            (deleted.undo().annotations.single() as TextBoxAnnotation).text,
        )
        assertTrue(deleted.redo().annotations.isEmpty())
    }

    @Test
    fun commitCreatesOneUndoStepForBatchChanges() {
        val history = AnnotationHistory().add(first).commit(listOf(first, second))

        assertEquals(listOf(first), history.undo().annotations)
        assertEquals(listOf(first, second), history.undo().redo().annotations)
    }

    @Test
    fun exactPageLimitRejectsAddAndOversizedDuplicateBeforeHistoryConstruction() {
        assertFalse(canAppendAnnotations(MAX_ANNOTATIONS_PER_PAGE, 1))
        assertTrue(canAppendAnnotations(MAX_ANNOTATIONS_PER_PAGE - 2, 2))
        assertFalse(canAppendAnnotations(MAX_ANNOTATIONS_PER_PAGE - 2, 3))
    }
}
