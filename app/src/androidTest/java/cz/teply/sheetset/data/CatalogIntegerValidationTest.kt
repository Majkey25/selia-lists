package cz.teply.sheetset.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogIntegerValidationTest {
    @Test
    fun roundedDecimalTimestampIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogJson.decode(catalogWithTimestamp("9007199254740993.0"))
        }
    }

    @Test
    fun exactTimestampValuesRemainAccepted() {
        listOf(
            "0.0" to 0L,
            "9007199254740991.0" to 9_007_199_254_740_991L,
            Long.MAX_VALUE.toString() to Long.MAX_VALUE,
        ).forEach { (literal, expected) ->
            val catalog = CatalogJson.decode(catalogWithTimestamp(literal))
            assertEquals(expected, catalog.scores.single().importedAtEpochMs)
        }
    }

    private fun catalogWithTimestamp(literal: String): String = """
        {
          "version": 1,
          "scores": [{
            "id": "score-1",
            "title": "Song",
            "fileName": "score-1.pdf",
            "pageCount": 1,
            "importedAtEpochMs": $literal
          }],
          "setlists": []
        }
    """.trimIndent()
}
