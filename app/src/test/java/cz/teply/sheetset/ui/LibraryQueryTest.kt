package cz.teply.sheetset.ui

import cz.teply.sheetset.data.Bookmark
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.data.Setlist
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {
    private val first = Score(
        id = "first",
        title = "Zebra",
        fileName = "first.pdf",
        pageCount = 4,
        importedAtEpochMs = 10,
        labels = listOf("Band"),
        bookmarks = listOf(Bookmark("chorus", "Chorus", 2)),
        lastViewedAtEpochMs = 30,
    )
    private val second = Score(
        id = "second",
        title = "Alpha",
        fileName = "second.pdf",
        pageCount = 2,
        importedAtEpochMs = 20,
        labels = listOf("Solo"),
        lastViewedAtEpochMs = 5,
    )
    private val catalog = LibraryCatalog(scores = listOf(first, second))

    @Test
    fun `bookmark match keeps direct page target`() {
        assertEquals(
            listOf(LibraryResult.BookmarkResult(first, first.bookmarks.single())),
            queryScores(catalog, "chorus", LibrarySort.TITLE, SortDirection.ASCENDING),
        )
    }

    @Test
    fun `title and label matches return score rows`() {
        assertEquals(
            listOf(LibraryResult.ScoreResult(first)),
            queryScores(catalog, "band", LibrarySort.TITLE, SortDirection.ASCENDING),
        )
        assertEquals(
            listOf(LibraryResult.ScoreResult(second)),
            queryScores(catalog, "alpha", LibrarySort.TITLE, SortDirection.ASCENDING),
        )
    }

    @Test
    fun `title match also returns matching bookmark page`() {
        val score = first.copy(title = "Chorus collection")
        assertEquals(
            listOf(
                LibraryResult.ScoreResult(score),
                LibraryResult.BookmarkResult(score, score.bookmarks.single()),
            ),
            queryScores(
                LibraryCatalog(scores = listOf(score)),
                "  cHoRuS  ",
                LibrarySort.TITLE,
                SortDirection.ASCENDING,
            ),
        )
    }

    @Test
    fun `label match also returns matching bookmark page`() {
        val score = first.copy(labels = listOf("Chorus"))
        assertEquals(
            listOf(
                LibraryResult.ScoreResult(score),
                LibraryResult.BookmarkResult(score, score.bookmarks.single()),
            ),
            queryScores(
                LibraryCatalog(scores = listOf(score)),
                "chorus",
                LibrarySort.IMPORTED,
                SortDirection.DESCENDING,
            ),
        )
    }

    @Test
    fun `scores sort by each supported field and direction`() {
        assertEquals(
            listOf(second, first),
            queryScores(catalog, "", LibrarySort.TITLE, SortDirection.ASCENDING)
                .map(LibraryResult::score),
        )
        assertEquals(
            listOf(second, first),
            queryScores(catalog, "", LibrarySort.IMPORTED, SortDirection.DESCENDING)
                .map(LibraryResult::score),
        )
        assertEquals(
            listOf(second, first),
            queryScores(catalog, "", LibrarySort.LAST_VIEWED, SortDirection.ASCENDING)
                .map(LibraryResult::score),
        )
    }

    @Test
    fun `setlists sort by title and creation time`() {
        val firstSet = Setlist("first", "Zebra", createdAtEpochMs = 10)
        val secondSet = Setlist("second", "Alpha", createdAtEpochMs = 20)
        val values = listOf(firstSet, secondSet)

        assertEquals(
            listOf(secondSet, firstSet),
            sortSetlists(values, SetlistSort.TITLE, SortDirection.ASCENDING),
        )
        assertEquals(
            listOf(secondSet, firstSet),
            sortSetlists(values, SetlistSort.CREATED, SortDirection.DESCENDING),
        )
    }
}
