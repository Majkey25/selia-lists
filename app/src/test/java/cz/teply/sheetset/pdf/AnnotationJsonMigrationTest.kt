package cz.teply.sheetset.pdf

import cz.teply.sheetset.settings.AnnotationTextSize
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationJsonMigrationTest {
    @Test
    fun duplicatePageIndexesAreRejectedInEveryVersion() {
        for (version in 1..3) {
            assertThrows(IllegalArgumentException::class.java) {
                AnnotationJson.decode("""{"version":$version,"pages":{"0":[],"00":[]}}""")
            }
        }
    }

    @Test
    fun fractionalAndOverflowingVersionsAreRejected() {
        for (version in listOf("3.5", "4294967299")) {
            assertThrows(IllegalArgumentException::class.java) {
                AnnotationJson.decode("""{"version":$version,"pages":{}}""")
            }
        }
    }

    @Test
    fun fractionalAndOverflowingOpacityIsRejected() {
        val source = JSONObject(
            AnnotationJson.encode(DocumentAnnotations(mapOf(0 to listOf(symbolAnnotation())))),
        )
        val symbol = source.getJSONObject("pages").getJSONArray("0").getJSONObject(0)

        for (opacity in listOf<Number>(12.5, 4_294_967_551L, -4_294_967_296L)) {
            symbol.put("opacity", opacity)
            assertThrows(IllegalArgumentException::class.java) {
                AnnotationJson.decode(source.toString())
            }
        }
    }

    @Test
    fun versionOneStrokesMigrateWithoutLoss() {
        val legacy = """
            {"version":1,"pages":{"0":[
              {"tool":"PEN","width":0.004,"points":[[0.1,0.2],[0.3,0.4]]},
              {"tool":"HIGHLIGHTER","width":0.02,"points":[[0.5,0.6]]}
            ]}}
        """.trimIndent()

        val decoded = AnnotationJson.decode(legacy)
        val page = decoded.pages.getValue(0)

        assertEquals(InkKind.PEN, (page[0] as InkAnnotation).kind)
        assertEquals(InkKind.HIGHLIGHTER, (page[1] as InkAnnotation).kind)
        assertEquals(AnnotationColor.YELLOW, (page[1] as InkAnnotation).color)
        assertEquals("legacy-0-0", page[0].id)
        assertEquals(decoded, AnnotationJson.decode(AnnotationJson.encode(decoded)))
    }

    @Test
    fun everyVersionTwoTypeSurvivesRoundTrip() {
        val annotations = DocumentAnnotations(
            mapOf(
                2 to listOf(
                    InkAnnotation(
                        id = "ink",
                        kind = InkKind.PEN,
                        width = 0.004f,
                        points = listOf(NormalizedPoint(0.1f, 0.2f)),
                    ),
                    MarkupAnnotation(
                        id = "highlight",
                        kind = MarkupKind.HIGHLIGHT,
                        bounds = listOf(NormalizedRect(0.1f, 0.2f, 0.4f, 0.3f)),
                    ),
                    MarkupAnnotation(
                        id = "underline",
                        kind = MarkupKind.UNDERLINE,
                        bounds = listOf(NormalizedRect(0.2f, 0.3f, 0.5f, 0.4f)),
                    ),
                    MarkupAnnotation(
                        id = "strike",
                        kind = MarkupKind.STRIKE_THROUGH,
                        bounds = listOf(NormalizedRect(0.3f, 0.4f, 0.6f, 0.5f)),
                    ),
                    TextBoxAnnotation(
                        id = "text",
                        bounds = NormalizedRect(0.2f, 0.2f, 0.6f, 0.4f),
                        text = "Allegro",
                        size = AnnotationTextSize.MEDIUM,
                    ),
                    ShapeAnnotation(
                        id = "line",
                        kind = ShapeKind.LINE,
                        start = NormalizedPoint(0.1f, 0.1f),
                        end = NormalizedPoint(0.8f, 0.8f),
                        width = 0.004f,
                    ),
                    ShapeAnnotation(
                        id = "arrow",
                        kind = ShapeKind.ARROW,
                        start = NormalizedPoint(0.2f, 0.1f),
                        end = NormalizedPoint(0.7f, 0.8f),
                        width = 0.004f,
                    ),
                    ShapeAnnotation(
                        id = "rectangle",
                        kind = ShapeKind.RECTANGLE,
                        start = NormalizedPoint(0.2f, 0.2f),
                        end = NormalizedPoint(0.7f, 0.7f),
                        width = 0.004f,
                    ),
                    ShapeAnnotation(
                        id = "ellipse",
                        kind = ShapeKind.ELLIPSE,
                        start = NormalizedPoint(0.3f, 0.3f),
                        end = NormalizedPoint(0.6f, 0.6f),
                        width = 0.004f,
                    ),
                ),
            ),
        )

        assertEquals(annotations, AnnotationJson.decode(AnnotationJson.encode(annotations)))
    }

    @Test
    fun widestEditorShapesRoundTripAndRejectLargerWidths() {
        val shapes = ShapeKind.entries.map { kind ->
            ShapeAnnotation(
                id = kind.name,
                kind = kind,
                start = NormalizedPoint(0.2f, 0.2f),
                end = NormalizedPoint(0.8f, 0.8f),
                width = 6.normalizedAnnotationWidth(),
            )
        }
        val source = DocumentAnnotations(mapOf(0 to shapes))

        assertEquals(source, AnnotationJson.decode(AnnotationJson.encode(source)))
        assertThrows(IllegalArgumentException::class.java) {
            shapes.first().copy(width = 0.2001f)
        }
    }

    @Test
    fun duplicateIdsAndBlankTextAreRejected() {
        val ink = InkAnnotation(
            id = "same",
            kind = InkKind.PEN,
            width = 0.004f,
            points = listOf(NormalizedPoint(0.1f, 0.1f)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DocumentAnnotations(mapOf(0 to listOf(ink, ink)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextBoxAnnotation(
                id = "text",
                bounds = NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f),
                text = " ",
                size = AnnotationTextSize.SMALL,
            )
        }
    }

    @Test
    fun versionTwoColorAndHighlighterOpacityMigrateToVersionThree() {
        val migrated = AnnotationJson.decode(
            """{"version":2,"pages":{"0":[{"id":"old","type":"ink","kind":"HIGHLIGHTER","color":"RED","width":0.01,"points":[[0.1,0.2],[0.3,0.4]]}]}}""",
        )

        val annotation = migrated.pages.getValue(0).single() as InkAnnotation
        assertEquals(AnnotationColor.RED, annotation.color)
        assertEquals(LEGACY_HIGHLIGHTER_OPACITY, annotation.opacity)
        assertTrue(AnnotationJson.encode(migrated).contains("\"version\":3"))
        assertTrue(AnnotationJson.encode(migrated).contains("#FFD32F2F"))
    }

    @Test
    fun textAppearanceSurvivesVersionThreeRoundTrip() {
        val source = DocumentAnnotations(
            mapOf(
                0 to listOf(
                    TextBoxAnnotation(
                        id = "text",
                        bounds = NormalizedRect(0.1f, 0.1f, 0.4f, 0.25f),
                        text = "rit.",
                        size = AnnotationTextSize.MEDIUM,
                        lineHeight = 1.3f,
                        alignment = AnnotationTextAlignment.CENTER,
                        color = AnnotationColor.BLACK,
                        opacity = 255,
                    ),
                ),
            ),
        )

        assertEquals(source, AnnotationJson.decode(AnnotationJson.encode(source)))
    }

    @Test
    fun symbolAppearanceSurvivesVersionThreeRoundTrip() {
        val source = DocumentAnnotations(mapOf(3 to listOf(symbolAnnotation())))

        assertEquals(source, AnnotationJson.decode(AnnotationJson.encode(source)))
    }

    @Test
    fun symbolRotationNormalizesNegativeDegrees() {
        assertEquals(315f, symbolAnnotation().rotated(-45f).rotationDegrees, 0f)
    }

    @Test
    fun invalidSymbolValuesAreRejected() {
        val valid = symbolAnnotation()

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(symbolId = "unsupported")
        }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(size = 0.009f) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(size = 0.501f) }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(rotationDegrees = -361f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(rotationDegrees = 361f)
        }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(opacity = -1) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(opacity = 256) }
    }

    private fun symbolAnnotation(): SymbolAnnotation = SymbolAnnotation(
        id = "symbol",
        symbolId = "fermata",
        center = NormalizedPoint(0.31f, 0.47f),
        size = 0.19f,
        rotationDegrees = -135f,
        color = AnnotationColor(0xFF123456.toInt()),
        opacity = 123,
    )
}
