package cz.teply.sheetset.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

const val MAX_TITLE_LENGTH = 120
const val MAX_BOOKMARKS = 1_000
const val MAX_LABELS = 20
const val MAX_LABEL_LENGTH = 40
const val MAX_SETLIST_ENTRIES = 10_000
const val MAX_CATALOG_SETLIST_ENTRIES = 100_000L
private const val MAX_SAFE_DOUBLE_INTEGER = 9_007_199_254_740_991.0

internal fun JSONObject.requiredLong(key: String): Long {
    val value = opt(key)
    require(value is Number) { "Invalid integer: $key" }
    // shortcut: Android JSON already rounds decimals; token-level validation needs a preserving parser.
    if (value is Double || value is Float) {
        require(value.toDouble() in -MAX_SAFE_DOUBLE_INTEGER..MAX_SAFE_DOUBLE_INTEGER) {
            "Invalid integer: $key"
        }
    }
    return try {
        BigDecimal(value.toString()).longValueExact()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("Invalid integer: $key", error)
    }
}

internal fun JSONObject.requiredInt(key: String): Int {
    val value = requiredLong(key)
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Invalid integer: $key" }
    return value.toInt()
}

private fun cleanTitle(value: String): String = value.trim().take(MAX_TITLE_LENGTH).also {
    require(it.isNotEmpty()) { "Title must not be blank" }
}

internal fun normalizeLabels(values: List<String>): List<String> = values
    .map { it.trim().take(MAX_LABEL_LENGTH).trimEnd() }
    .filter(String::isNotEmpty)
    .distinct()
    .take(MAX_LABELS)

data class Bookmark(
    val id: String,
    val title: String,
    val pageIndex: Int,
)

data class Score(
    val id: String,
    val title: String,
    val fileName: String,
    val pageCount: Int,
    val importedAtEpochMs: Long,
    val labels: List<String> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val lastPageIndex: Int = 0,
    val lastPagePart: Int = 0,
    val lastViewedAtEpochMs: Long = 0,
)

data class Setlist(
    val id: String,
    val name: String,
    val scoreIds: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val createdAtEpochMs: Long = 0,
) {
    init {
        require(scoreIds.size <= MAX_SETLIST_ENTRIES) { "Setlist has too many entries" }
    }
}

data class LibraryCatalog(
    val scores: List<Score> = emptyList(),
    val setlists: List<Setlist> = emptyList(),
) {
    init {
        require(setlists.sumOf { it.scoreIds.size.toLong() } <= MAX_CATALOG_SETLIST_ENTRIES) {
            "Library has too many setlist entries"
        }
    }

    fun createSetlist(
        name: String,
        id: String,
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): LibraryCatalog {
        require(id.isNotBlank()) { "Setlist ID must not be blank" }
        require(createdAtEpochMs >= 0) { "Creation time must not be negative" }
        return copy(setlists = setlists + Setlist(id, cleanTitle(name), createdAtEpochMs = createdAtEpochMs))
    }

    fun renameScore(scoreId: String, title: String): LibraryCatalog = copy(
        scores = scores.map { score ->
            if (score.id == scoreId) score.copy(title = cleanTitle(title)) else score
        },
    )

    fun renameSetlist(setlistId: String, name: String): LibraryCatalog = copy(
        setlists = setlists.map { setlist ->
            if (setlist.id == setlistId) setlist.copy(name = cleanTitle(name)) else setlist
        },
    )

    fun deleteSetlist(setlistId: String): LibraryCatalog = copy(
        setlists = setlists.filterNot { it.id == setlistId },
    )

    fun addScoreToSetlist(setlistId: String, scoreId: String): LibraryCatalog {
        require(scores.any { it.id == scoreId }) { "Score does not exist" }
        require(setlists.any { it.id == setlistId }) { "Setlist does not exist" }
        return copy(setlists = setlists.map { setlist ->
            if (setlist.id == setlistId) setlist.copy(scoreIds = setlist.scoreIds + scoreId)
            else setlist
        })
    }

    fun removeScoreFromSetlist(setlistId: String, index: Int): LibraryCatalog {
        val setlist = setlists.firstOrNull { it.id == setlistId } ?: return this
        if (index !in setlist.scoreIds.indices) return this
        val remaining = setlist.scoreIds.toMutableList().apply { removeAt(index) }
        return copy(setlists = setlists.map { current ->
            if (current.id == setlistId) current.copy(scoreIds = remaining) else current
        })
    }

    fun deleteScore(scoreId: String): LibraryCatalog = copy(
        scores = scores.filterNot { it.id == scoreId },
        setlists = setlists.map { setlist ->
            setlist.copy(scoreIds = setlist.scoreIds.filterNot { it == scoreId })
        },
    )

    fun moveScore(setlistId: String, fromIndex: Int, toIndex: Int): LibraryCatalog {
        val setlist = setlists.firstOrNull { it.id == setlistId } ?: return this
        if (fromIndex !in setlist.scoreIds.indices || toIndex !in setlist.scoreIds.indices) {
            return this
        }
        val reordered = setlist.scoreIds.toMutableList()
        reordered.add(toIndex, reordered.removeAt(fromIndex))
        return copy(setlists = setlists.map { current ->
            if (current.id == setlistId) current.copy(scoreIds = reordered) else current
        })
    }

    fun reorderScores(setlistId: String, scoreIds: List<String>): LibraryCatalog {
        val setlist = setlists.firstOrNull { it.id == setlistId } ?: return this
        require(
            setlist.scoreIds.groupingBy { it }.eachCount() ==
                scoreIds.groupingBy { it }.eachCount(),
        ) { "Reordered scores must preserve every occurrence" }
        if (setlist.scoreIds == scoreIds) return this
        return copy(setlists = setlists.map { current ->
            if (current.id == setlistId) current.copy(scoreIds = scoreIds.toList()) else current
        })
    }

    fun addBookmark(scoreId: String, bookmark: Bookmark): LibraryCatalog {
        val score = scores.firstOrNull { it.id == scoreId }
            ?: throw IllegalArgumentException("Score does not exist")
        require(bookmark.id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid bookmark ID" }
        require(bookmark.pageIndex in 0 until score.pageCount) { "Bookmark page is invalid" }
        require(score.bookmarks.size < MAX_BOOKMARKS) { "Too many bookmarks" }
        require(score.bookmarks.none { it.id == bookmark.id }) { "Duplicate bookmark ID" }
        val stored = bookmark.copy(title = cleanTitle(bookmark.title))
        return copy(scores = scores.map { current ->
            if (current.id == scoreId) current.copy(bookmarks = current.bookmarks + stored) else current
        })
    }

    fun renameBookmark(scoreId: String, bookmarkId: String, title: String): LibraryCatalog {
        val score = scores.firstOrNull { it.id == scoreId }
            ?: throw IllegalArgumentException("Score does not exist")
        require(score.bookmarks.any { it.id == bookmarkId }) { "Bookmark does not exist" }
        return copy(scores = scores.map { current ->
            if (current.id == scoreId) {
                current.copy(bookmarks = current.bookmarks.map { bookmark ->
                    if (bookmark.id == bookmarkId) bookmark.copy(title = cleanTitle(title)) else bookmark
                })
            } else {
                current
            }
        })
    }

    fun deleteBookmark(scoreId: String, bookmarkId: String): LibraryCatalog = copy(
        scores = scores.map { score ->
            if (score.id == scoreId) {
                score.copy(bookmarks = score.bookmarks.filterNot { it.id == bookmarkId })
            } else {
                score
            }
        },
    )

    fun updateScoreLabels(scoreId: String, labels: List<String>): LibraryCatalog {
        require(scores.any { it.id == scoreId }) { "Score does not exist" }
        return copy(scores = scores.map { score ->
            if (score.id == scoreId) score.copy(labels = normalizeLabels(labels)) else score
        })
    }

    fun updateSetlistLabels(setlistId: String, labels: List<String>): LibraryCatalog {
        require(setlists.any { it.id == setlistId }) { "Setlist does not exist" }
        return copy(setlists = setlists.map { setlist ->
            if (setlist.id == setlistId) setlist.copy(labels = normalizeLabels(labels)) else setlist
        })
    }

    fun saveReaderPosition(
        scoreId: String,
        page: Int,
        part: Int,
        viewedAt: Long,
    ): LibraryCatalog {
        val score = scores.firstOrNull { it.id == scoreId }
            ?: throw IllegalArgumentException("Score does not exist")
        require(page in 0 until score.pageCount) { "Reader page is invalid" }
        require(part in 0..1) { "Reader page part is invalid" }
        require(viewedAt >= 0) { "Viewed time must not be negative" }
        return copy(scores = scores.map { current ->
            if (current.id == scoreId) {
                current.copy(
                    lastPageIndex = page,
                    lastPagePart = part,
                    lastViewedAtEpochMs = viewedAt,
                )
            } else {
                current
            }
        })
    }
}

object CatalogJson {
    private const val VERSION = 2

    fun encode(catalog: LibraryCatalog): String = JSONObject()
        .put("version", VERSION)
        .put(
            "scores",
            JSONArray().apply {
                catalog.scores.forEach { score ->
                    put(
                        JSONObject()
                            .put("id", score.id)
                            .put("title", score.title)
                            .put("fileName", score.fileName)
                            .put("pageCount", score.pageCount)
                            .put("importedAtEpochMs", score.importedAtEpochMs)
                            .put("labels", JSONArray(score.labels))
                            .put(
                                "bookmarks",
                                JSONArray().apply {
                                    score.bookmarks.forEach { bookmark ->
                                        put(
                                            JSONObject()
                                                .put("id", bookmark.id)
                                                .put("title", bookmark.title)
                                                .put("pageIndex", bookmark.pageIndex),
                                        )
                                    }
                                },
                            )
                            .put("lastPageIndex", score.lastPageIndex)
                            .put("lastPagePart", score.lastPagePart)
                            .put("lastViewedAtEpochMs", score.lastViewedAtEpochMs),
                    )
                }
            },
        )
        .put(
            "setlists",
            JSONArray().apply {
                catalog.setlists.forEach { setlist ->
                    put(
                        JSONObject()
                            .put("id", setlist.id)
                            .put("name", setlist.name)
                            .put("scoreIds", JSONArray(setlist.scoreIds))
                            .put("labels", JSONArray(setlist.labels))
                            .put("createdAtEpochMs", setlist.createdAtEpochMs),
                    )
                }
            },
        )
        .toString()

    fun decode(raw: String): LibraryCatalog {
        val root = JSONObject(raw)
        val version = root.requiredInt("version")
        require(version in 1..VERSION) { "Unsupported catalog version" }
        val scoresJson = root.getJSONArray("scores")
        val scores = List(scoresJson.length()) { index ->
            scoresJson.getJSONObject(index).toScore(version)
        }
        require(scores.map(Score::id).distinct().size == scores.size) {
            "Duplicate score ID"
        }
        require(scores.map(Score::fileName).distinct().size == scores.size) {
            "Duplicate score file name"
        }
        val setlistsJson = root.getJSONArray("setlists")
        var totalEntries = 0L
        repeat(setlistsJson.length()) { index ->
            val count = setlistsJson.getJSONObject(index).getJSONArray("scoreIds").length()
            require(count <= MAX_SETLIST_ENTRIES) { "Setlist has too many entries" }
            totalEntries += count
            require(totalEntries <= MAX_CATALOG_SETLIST_ENTRIES) {
                "Library has too many setlist entries"
            }
        }
        val setlists = List(setlistsJson.length()) { index ->
            setlistsJson.getJSONObject(index).toSetlist(version)
        }
        require(setlists.map(Setlist::id).distinct().size == setlists.size) {
            "Duplicate setlist ID"
        }
        val scoreIds = scores.mapTo(mutableSetOf(), Score::id)
        require(setlists.all { setlist -> setlist.scoreIds.all(scoreIds::contains) }) {
            "Setlist references a missing score"
        }
        return LibraryCatalog(scores, setlists)
    }

    private fun JSONObject.toScore(version: Int): Score {
        val id = getString("id")
        val title = getString("title")
        val fileName = getString("fileName")
        val pageCount = requiredInt("pageCount")
        val importedAt = requiredLong("importedAtEpochMs")
        require(id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid score ID" }
        require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) { "Invalid score title" }
        require(fileName.matches(Regex("[A-Za-z0-9-]+\\.pdf"))) { "Invalid score file name" }
        require(pageCount > 0) { "Page count must be positive" }
        require(importedAt >= 0) { "Import time must not be negative" }
        val labels = if (version >= 2) getJSONArray("labels").toLabels("Invalid score labels") else emptyList()
        val bookmarks = if (version >= 2) {
            val values = getJSONArray("bookmarks")
            List(values.length()) { index -> values.getJSONObject(index).toBookmark(pageCount) }
        } else {
            emptyList()
        }
        require(bookmarks.size <= MAX_BOOKMARKS) { "Too many bookmarks" }
        require(bookmarks.map(Bookmark::id).distinct().size == bookmarks.size) {
            "Duplicate bookmark ID"
        }
        val lastPageIndex = if (version >= 2) requiredInt("lastPageIndex") else 0
        val lastPagePart = if (version >= 2) requiredInt("lastPagePart") else 0
        val lastViewedAt = if (version >= 2) requiredLong("lastViewedAtEpochMs") else 0L
        require(lastPageIndex in 0 until pageCount) { "Invalid saved page" }
        require(lastPagePart in 0..1) { "Invalid saved page part" }
        require(lastViewedAt >= 0) { "Invalid last viewed time" }
        return Score(
            id,
            title,
            fileName,
            pageCount,
            importedAt,
            labels,
            bookmarks,
            lastPageIndex,
            lastPagePart,
            lastViewedAt,
        )
    }

    private fun JSONObject.toSetlist(version: Int): Setlist {
        val id = getString("id")
        val name = getString("name")
        val scoreIdsJson = getJSONArray("scoreIds")
        require(id.isNotBlank()) { "Setlist ID must not be blank" }
        require(name.isNotBlank() && name.length <= MAX_TITLE_LENGTH) { "Invalid setlist name" }
        val labels = if (version >= 2) getJSONArray("labels").toLabels("Invalid setlist labels") else emptyList()
        val createdAt = if (version >= 2) requiredLong("createdAtEpochMs") else 0L
        require(createdAt >= 0) { "Invalid setlist creation time" }
        return Setlist(
            id = id,
            name = name,
            scoreIds = List(scoreIdsJson.length(), scoreIdsJson::getString),
            labels = labels,
            createdAtEpochMs = createdAt,
        )
    }

    private fun JSONObject.toBookmark(pageCount: Int): Bookmark {
        val id = getString("id")
        val title = getString("title")
        val pageIndex = requiredInt("pageIndex")
        require(id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid bookmark ID" }
        require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) { "Invalid bookmark title" }
        require(pageIndex in 0 until pageCount) { "Invalid bookmark page" }
        return Bookmark(id, title, pageIndex)
    }

    private fun JSONArray.toLabels(errorMessage: String): List<String> {
        val labels = List(length(), ::getString)
        require(labels.size <= MAX_LABELS && labels.distinct().size == labels.size) { errorMessage }
        // Older versions could leave trailing whitespace at the truncation limit.
        val recovered = labels.map { if (it.length == MAX_LABEL_LENGTH) it.trimEnd() else it }
        require(recovered.all { it.isNotEmpty() && it == it.trim().take(MAX_LABEL_LENGTH) }) { errorMessage }
        return recovered.distinct()
    }
}
