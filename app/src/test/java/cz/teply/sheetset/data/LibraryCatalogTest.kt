package cz.teply.sheetset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogTest {
    @Test
    fun `blank setlist name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LibraryCatalog().createSetlist("   ", "set-1")
        }
    }

    @Test
    fun `setlists are unlimited`() {
        val catalog = (1..4).fold(LibraryCatalog()) { current, number ->
            current.createSetlist("Set $number", "set-$number")
        }

        assertEquals(4, catalog.setlists.size)
    }

    @Test
    fun `deleting score removes every setlist reference`() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val catalog = LibraryCatalog(
            scores = listOf(score),
            setlists = listOf(
                Setlist("set-1", "First", listOf(score.id)),
                Setlist("set-2", "Second", listOf(score.id, score.id)),
            ),
        )

        val updated = catalog.deleteScore(score.id)

        assertFalse(updated.scores.any { it.id == score.id })
        assertFalse(updated.setlists.any { score.id in it.scoreIds })
    }

    @Test
    fun `move score changes only valid positions`() {
        val original = LibraryCatalog(
            setlists = listOf(Setlist("set-1", "Show", listOf("a", "b", "c"))),
        )

        assertEquals(
            listOf("b", "a", "c"),
            original.moveScore("set-1", fromIndex = 0, toIndex = 1)
                .setlists.single().scoreIds,
        )
        assertEquals(original, original.moveScore("set-1", fromIndex = -1, toIndex = 0))
        assertEquals(original, original.moveScore("missing", fromIndex = 0, toIndex = 1))
    }

    @Test
    fun `reorder scores atomically preserves every occurrence`() {
        val original = LibraryCatalog(
            setlists = listOf(Setlist("set-1", "Show", listOf("a", "b", "a"))),
        )

        assertEquals(
            listOf("a", "a", "b"),
            original.reorderScores("set-1", listOf("a", "a", "b"))
                .setlists.single().scoreIds,
        )
        assertThrows(IllegalArgumentException::class.java) {
            original.reorderScores("set-1", listOf("a", "b", "b"))
        }
    }

    @Test
    fun `score can be renamed and added to setlist`() {
        val score = Score("score-1", "Old", "score-1.pdf", 1, 1L)
        val catalog = LibraryCatalog(
            scores = listOf(score),
            setlists = listOf(Setlist("set-1", "Show")),
        )

        val updated = catalog
            .renameScore(score.id, "  New title  ")
            .addScoreToSetlist("set-1", score.id)

        assertEquals("New title", updated.scores.single().title)
        assertEquals(listOf(score.id), updated.setlists.single().scoreIds)
    }

    @Test
    fun `setlist can be renamed and deleted`() {
        val catalog = LibraryCatalog().createSetlist("Show", "set-1")

        val renamed = catalog.renameSetlist("set-1", "  Encore  ")

        assertEquals("Encore", renamed.setlists.single().name)
        assertTrue(renamed.deleteSetlist("set-1").setlists.isEmpty())
    }

    @Test
    fun `score occurrence can be removed from setlist`() {
        val catalog = LibraryCatalog(
            setlists = listOf(Setlist("set-1", "Show", listOf("a", "b", "a"))),
        )

        assertEquals(
            listOf("a", "a"),
            catalog.removeScoreFromSetlist("set-1", 1).setlists.single().scoreIds,
        )
        assertEquals(catalog, catalog.removeScoreFromSetlist("set-1", 9))
    }

    @Test
    fun `bookmarks are validated renamed and deleted`() {
        val catalog = LibraryCatalog(
            scores = listOf(Score("score-1", "Song", "score-1.pdf", 3, 1L)),
        )

        val added = catalog.addBookmark(
            "score-1",
            Bookmark("bookmark-1", "  Chorus  ", 2),
        )
        assertEquals(Bookmark("bookmark-1", "Chorus", 2), added.scores.single().bookmarks.single())
        val renamed = added.renameBookmark("score-1", "bookmark-1", "  Finale  ")
        assertEquals("Finale", renamed.scores.single().bookmarks.single().title)
        assertTrue(renamed.deleteBookmark("score-1", "bookmark-1").scores.single().bookmarks.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            catalog.addBookmark("score-1", Bookmark("bad", "Bad", 3))
        }
    }

    @Test
    fun `labels are normalized and bounded`() {
        val score = Score("score-1", "Song", "score-1.pdf", 1, 1L)
        val setlist = Setlist("set-1", "Show")
        val catalog = LibraryCatalog(scores = listOf(score), setlists = listOf(setlist))
        val labels = listOf("  Band  ", "Band", "", "Encore") +
            (1..30).map { "Label $it" }

        val updated = catalog
            .updateScoreLabels(score.id, labels)
            .updateSetlistLabels(setlist.id, listOf("  Saturday  ", "Saturday"))

        assertEquals(listOf("Band", "Encore") + (1..18).map { "Label $it" }, updated.scores.single().labels)
        assertEquals(listOf("Saturday"), updated.setlists.single().labels)
    }

    @Test
    fun `labels truncated at whitespace survive catalog reload`() {
        val catalog = LibraryCatalog(
            scores = listOf(Score("score-1", "Song", "score-1.pdf", 1, 1L)),
            setlists = listOf(Setlist("set-1", "Show")),
        )
        val label = "a".repeat(MAX_LABEL_LENGTH - 1)
        val labels = listOf("$label b", label)

        val updated = catalog
            .updateScoreLabels("score-1", labels)
            .updateSetlistLabels("set-1", labels)

        assertEquals(listOf(label), updated.scores.single().labels)
        assertEquals(listOf(label), updated.setlists.single().labels)
        assertEquals(updated, CatalogJson.decode(CatalogJson.encode(updated)))
        assertEquals(
            updated,
            updated.updateScoreLabels("score-1", updated.scores.single().labels)
                .updateSetlistLabels("set-1", updated.setlists.single().labels),
        )
    }

    @Test
    fun `reader position stays inside score`() {
        val score = Score("score-1", "Song", "score-1.pdf", 4, 1L)
        val catalog = LibraryCatalog(scores = listOf(score))

        val updated = catalog.saveReaderPosition(score.id, page = 3, part = 1, viewedAt = 99L)

        assertEquals(3, updated.scores.single().lastPageIndex)
        assertEquals(1, updated.scores.single().lastPagePart)
        assertEquals(99L, updated.scores.single().lastViewedAtEpochMs)
        assertThrows(IllegalArgumentException::class.java) {
            catalog.saveReaderPosition(score.id, page = 4, part = 0, viewedAt = 99L)
        }
    }
}
