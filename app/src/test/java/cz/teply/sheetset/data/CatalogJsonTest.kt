package cz.teply.sheetset.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogJsonTest {
    @Test
    fun `literal catalog JSON is decoded`() {
        val json = """
            {
              "version": 1,
              "scores": [{
                "id": "score-1",
                "title": "Etude",
                "fileName": "score-1.pdf",
                "pageCount": 3,
                "importedAtEpochMs": 42
              }],
              "setlists": [{
                "id": "set-1",
                "name": "Concert",
                "scoreIds": ["score-1"]
              }]
            }
        """.trimIndent()

        assertEquals(
            LibraryCatalog(
                scores = listOf(Score("score-1", "Etude", "score-1.pdf", 3, 42L)),
                setlists = listOf(Setlist("set-1", "Concert", listOf("score-1"))),
            ),
            CatalogJson.decode(json),
        )
    }

    @Test
    fun `catalog survives JSON round trip`() {
        val catalog = LibraryCatalog(
            scores = listOf(Score("score-1", "Song", "score-1.pdf", 2, 7L)),
            setlists = listOf(Setlist("set-1", "Show", listOf("score-1", "score-1"))),
        )

        assertEquals(catalog, CatalogJson.decode(CatalogJson.encode(catalog)))
    }

    @Test
    fun `catalog version must be a JSON number`() {
        for (version in listOf("null", "true", "\"2\"")) {
            assertThrows(IllegalArgumentException::class.java) {
                CatalogJson.decode("""{"version":$version,"scores":[],"setlists":[]}""")
            }
        }
    }

    @Test
    fun `catalog integer fields reject fractions and wrapped values`() {
        val catalog = LibraryCatalog(scores = listOf(
            Score(
                "score-1", "Song", "score-1.pdf", 3, 7L,
                bookmarks = listOf(Bookmark("bookmark-1", "Chorus", 1)),
                lastPageIndex = 2,
                lastPagePart = 1,
            ),
        ))
        val json = JSONObject(CatalogJson.encode(catalog))
        val score = json.getJSONArray("scores").getJSONObject(0)
        val bookmark = score.getJSONArray("bookmarks").getJSONObject(0)
        val fields = listOf(
            json to "version",
            score to "pageCount",
            score to "lastPageIndex",
            score to "lastPagePart",
            bookmark to "pageIndex",
        )

        assertEquals(catalog, CatalogJson.decode(json.toString()))
        fields.forEach { (target, key) ->
            val original = target.getLong(key)
            listOf<Number>(original + 0.5, original + 4_294_967_296L).forEach { invalid ->
                target.put(key, invalid)
                assertThrows("$key=$invalid", IllegalArgumentException::class.java) {
                    CatalogJson.decode(json.toString())
                }
            }
            target.put(key, original)
        }
    }

    @Test
    fun `catalog timestamps reject negative fractions and preserve maximum long`() {
        val catalog = LibraryCatalog(
            scores = listOf(Score(
                "score-1", "Song", "score-1.pdf", 1, Long.MAX_VALUE,
                lastViewedAtEpochMs = Long.MAX_VALUE,
            )),
            setlists = listOf(Setlist("set-1", "Show", createdAtEpochMs = Long.MAX_VALUE)),
        )
        val json = JSONObject(CatalogJson.encode(catalog))
        val score = json.getJSONArray("scores").getJSONObject(0)
        val setlist = json.getJSONArray("setlists").getJSONObject(0)

        assertEquals(catalog, CatalogJson.decode(json.toString()))
        listOf(
            score to "importedAtEpochMs",
            score to "lastViewedAtEpochMs",
            setlist to "createdAtEpochMs",
        ).forEach { (target, key) ->
            target.put(key, -0.5)
            assertThrows(key, IllegalArgumentException::class.java) {
                CatalogJson.decode(json.toString())
            }
            target.put(key, Long.MAX_VALUE)
        }
    }

    @Test
    fun `legacy labels truncated at whitespace are recovered`() {
        val label = "a".repeat(MAX_LABEL_LENGTH - 1)
        val labels = listOf("$label ", label, "Band")
        val score = Score("score-1", "Song", "score-1.pdf", 1, 1L, labels = labels)
        val setlist = Setlist("set-1", "Show", labels = labels)

        val decoded = CatalogJson.decode(CatalogJson.encode(LibraryCatalog(listOf(score), listOf(setlist))))

        assertEquals(listOf(label, "Band"), decoded.scores.single().labels)
        assertEquals(listOf(label, "Band"), decoded.setlists.single().labels)
        assertEquals(decoded, CatalogJson.decode(CatalogJson.encode(decoded)))
    }

    @Test
    fun `legacy label recovery preserves validation of malformed labels`() {
        val score = Score("score-1", "Song", "score-1.pdf", 1, 1L)
        val setlist = Setlist("set-1", "Show")
        val legacyLabel = "a".repeat(MAX_LABEL_LENGTH - 1) + " "
        val invalidLabels = listOf(
            listOf(""),
            listOf(" leading"),
            listOf("trailing "),
            listOf("a".repeat(MAX_LABEL_LENGTH) + " "),
            listOf("a".repeat(MAX_LABEL_LENGTH + 1)),
            listOf(" ".repeat(MAX_LABEL_LENGTH)),
            listOf(" " + "a".repeat(MAX_LABEL_LENGTH - 2) + " "),
            listOf("duplicate", "duplicate"),
            listOf(legacyLabel, legacyLabel),
            listOf(legacyLabel, " leading"),
            (1..(MAX_LABELS + 1)).map { "Label $it" },
        )

        invalidLabels.forEach { labels ->
            val catalogs = listOf(
                LibraryCatalog(scores = listOf(score.copy(labels = labels))),
                LibraryCatalog(setlists = listOf(setlist.copy(labels = labels))),
            )
            catalogs.forEach { catalog ->
                assertThrows(IllegalArgumentException::class.java) {
                    CatalogJson.decode(CatalogJson.encode(catalog))
                }
            }
        }
    }

    @Test
    fun `scores cannot share a PDF file`() {
        val first = Score("score-1", "First", "shared.pdf", 1, 1L)
        val second = first.copy(id = "score-2", title = "Second")

        assertThrows(IllegalArgumentException::class.java) {
            CatalogJson.decode(CatalogJson.encode(LibraryCatalog(scores = listOf(first, second))))
        }
    }

    @Test
    fun `score IDs must be safe annotation file names`() {
        listOf("", " ", "bad_id", "bad/id", "bad\\id", "..", "žluťoučký").forEach { id ->
            val score = Score(id, "Song", "score-1.pdf", 1, 1L)

            assertThrows(IllegalArgumentException::class.java) {
                CatalogJson.decode(CatalogJson.encode(LibraryCatalog(scores = listOf(score))))
            }
        }
    }

    @Test
    fun `version two metadata survives literal JSON decode`() {
        val json = """
            {
              "version": 2,
              "scores": [{
                "id": "score-1",
                "title": "Etude",
                "fileName": "score-1.pdf",
                "pageCount": 4,
                "importedAtEpochMs": 42,
                "labels": ["Band", "Encore"],
                "bookmarks": [{"id":"bookmark-1","title":"Chorus","pageIndex":2}],
                "lastPageIndex": 3,
                "lastPagePart": 1,
                "lastViewedAtEpochMs": 99
              }],
              "setlists": [{
                "id": "set-1",
                "name": "Concert",
                "scoreIds": ["score-1"],
                "labels": ["Saturday"],
                "createdAtEpochMs": 77
              }]
            }
        """.trimIndent()

        assertEquals(
            LibraryCatalog(
                scores = listOf(
                    Score(
                        id = "score-1",
                        title = "Etude",
                        fileName = "score-1.pdf",
                        pageCount = 4,
                        importedAtEpochMs = 42L,
                        labels = listOf("Band", "Encore"),
                        bookmarks = listOf(Bookmark("bookmark-1", "Chorus", 2)),
                        lastPageIndex = 3,
                        lastPagePart = 1,
                        lastViewedAtEpochMs = 99L,
                    ),
                ),
                setlists = listOf(
                    Setlist(
                        id = "set-1",
                        name = "Concert",
                        scoreIds = listOf("score-1"),
                        labels = listOf("Saturday"),
                        createdAtEpochMs = 77L,
                    ),
                ),
            ),
            CatalogJson.decode(json),
        )
    }
}
