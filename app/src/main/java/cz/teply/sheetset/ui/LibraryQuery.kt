package cz.teply.sheetset.ui

import cz.teply.sheetset.data.Bookmark
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.data.Setlist
import java.util.Locale

enum class LibrarySort { TITLE, IMPORTED, LAST_VIEWED }
enum class SetlistSort { TITLE, CREATED }
enum class SortDirection { ASCENDING, DESCENDING }

sealed interface LibraryResult {
    val score: Score

    data class ScoreResult(override val score: Score) : LibraryResult

    data class BookmarkResult(
        override val score: Score,
        val bookmark: Bookmark,
    ) : LibraryResult
}

fun queryScores(
    catalog: LibraryCatalog,
    query: String,
    sort: LibrarySort,
    direction: SortDirection,
): List<LibraryResult> {
    val scores = catalog.scores.sortedWith(sort.comparator(direction))
    val needle = query.trim()
    if (needle.isEmpty()) return scores.map(LibraryResult::ScoreResult)
    return scores.flatMap { score ->
        buildList<LibraryResult> {
            if (
                score.title.contains(needle, ignoreCase = true) ||
                score.labels.any { it.contains(needle, ignoreCase = true) }
            ) {
                add(LibraryResult.ScoreResult(score))
            }
            score.bookmarks.filter { it.title.contains(needle, ignoreCase = true) }
                .forEach { add(LibraryResult.BookmarkResult(score, it)) }
        }
    }
}

fun sortSetlists(
    setlists: List<Setlist>,
    sort: SetlistSort,
    direction: SortDirection,
): List<Setlist> = setlists.sortedWith(sort.comparator(direction))

private fun LibrarySort.comparator(direction: SortDirection): Comparator<Score> {
    val comparator = when (this) {
        LibrarySort.TITLE -> compareBy { it.title.lowercase(Locale.ROOT) }
        LibrarySort.IMPORTED -> compareBy(Score::importedAtEpochMs)
        LibrarySort.LAST_VIEWED -> compareBy(Score::lastViewedAtEpochMs)
    }
    return if (direction == SortDirection.ASCENDING) comparator else comparator.reversed()
}

private fun SetlistSort.comparator(direction: SortDirection): Comparator<Setlist> {
    val comparator = when (this) {
        SetlistSort.TITLE -> compareBy { it.name.lowercase(Locale.ROOT) }
        SetlistSort.CREATED -> compareBy(Setlist::createdAtEpochMs)
    }
    return if (direction == SortDirection.ASCENDING) comparator else comparator.reversed()
}
