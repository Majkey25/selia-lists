package cz.teply.sheetset.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import org.json.JSONArray
import org.json.JSONObject

class AnnotationEditorSettingsTest {
    @Test
    fun presetsRoundTripAndRejectDuplicateIds() {
        val settings = AnnotationEditorSettings.defaults().copy(
            quickColors = listOf(AnnotationColor.RED, AnnotationColor.BLUE),
            palmRejection = true,
        )

        assertEquals(settings, AnnotationEditorSettingsJson.decode(AnnotationEditorSettingsJson.encode(settings)))

        val duplicate = settings.presets.first()
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(presets = listOf(duplicate, duplicate))
        }
    }

    @Test
    fun invalidPresetBoundsAreRejected() {
        val settings = AnnotationEditorSettings.defaults()

        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(presets = settings.presets.map { it.copy(width = 0) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(presets = settings.presets.map { it.copy(width = 7) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(quickColors = List(9) { AnnotationColor.BLACK })
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(drawOrder = listOf("pen-1", "pen-1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(
                presets = settings.presets.map {
                    if (it.id == "pen-1") it.copy(id = "unknown") else it
                },
                drawOrder = settings.drawOrder.map { if (it == "pen-1") "unknown" else it },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(objectOrder = settings.objectOrder + "select")
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(presets = settings.presets.map { it.copy(visible = false) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(visibleObjectTools = emptySet())
        }
    }

    @Test
    fun defaultsExposeOnlyEssentialDrawingTools() {
        val settings = AnnotationEditorSettings.defaults()

        assertEquals(
            listOf("pen-1", "highlighter"),
            settings.drawOrder.filter { settings.preset(it).visible },
        )
        assertEquals(2, settings.preset("pen-1").width)
        assertEquals(5, settings.preset("highlighter").width)
        assertFalse("lasso" in settings.visibleObjectTools)
    }

    @Test
    fun legacyEditorJsonMigratesWidthAndDuplicateTools() {
        val root = JSONObject(AnnotationEditorSettingsJson.encode(AnnotationEditorSettings.defaults()))
        root.remove("version")
        val oldWidths = listOf(20, 20, 30, 40)
        val presets = root.getJSONArray("presets")
        repeat(presets.length()) { index ->
            presets.getJSONObject(index)
                .put("width", oldWidths[index])
                .put("visible", true)
        }
        root.put("visibleObjectTools", JSONArray(DEFAULT_OBJECT_TOOL_ORDER))

        val migrated = AnnotationEditorSettingsJson.decode(root.toString())

        assertEquals(listOf(2, 2, 2, 5), migrated.presets.map(DrawingPreset::width))
        assertEquals(
            listOf("pen-1", "highlighter"),
            migrated.drawOrder.filter { migrated.preset(it).visible },
        )
        assertFalse("lasso" in migrated.visibleObjectTools)
    }

    @Test
    fun versionTwoWidthsMigrateToTheNearestSharedStop() {
        val root = JSONObject(AnnotationEditorSettingsJson.encode(AnnotationEditorSettings.defaults()))
        root.put("version", 2)
        val widths = listOf(10, 1, 4, 5)
        val presets = root.getJSONArray("presets")
        repeat(presets.length()) { index ->
            presets.getJSONObject(index).put("width", widths[index])
        }

        val migrated = AnnotationEditorSettingsJson.decode(root.toString())

        assertFalse(AnnotationEditorSettingsJson.isLegacy(root.toString()))
        assertEquals(listOf(3, 1, 2, 5), migrated.presets.map(DrawingPreset::width))
    }

    @Test
    fun legacyMigrationKeepsSoleCustomizedDrawingAndObjectTools() {
        for (version in 1..2) {
            for (visiblePreset in listOf("pen-2", "marker")) {
                val expected = AnnotationEditorSettings.defaults().copy(
                    presets = AnnotationEditorSettings.defaults().presets.map {
                        it.copy(visible = it.id == visiblePreset)
                    },
                    visibleObjectTools = setOf("lasso"),
                    quickColors = listOf(AnnotationColor.RED, AnnotationColor.BLUE),
                    palmRejection = true,
                )
                val raw = JSONObject(AnnotationEditorSettingsJson.encode(expected))
                    .put("version", version).toString()

                val migrated = AnnotationEditorSettingsJson.decode(raw)

                assertEquals(listOf(visiblePreset), migrated.presets.filter(DrawingPreset::visible).map { it.id })
                assertEquals(expected.visibleObjectTools, migrated.visibleObjectTools)
                assertEquals(expected.quickColors, migrated.quickColors)
                assertTrue(migrated.palmRejection)
            }
        }
    }

    @Test
    fun versionTwoMigrationPreservesCustomizedVisibility() {
        val defaults = AnnotationEditorSettings.defaults()
        val expected = defaults.copy(
            presets = defaults.presets.map { it.copy(visible = true) },
            visibleObjectTools = setOf("lasso", "text-box"),
        )
        val raw = JSONObject(AnnotationEditorSettingsJson.encode(expected))
            .put("version", 2).toString()

        val migrated = AnnotationEditorSettingsJson.decode(raw)

        assertEquals(expected.presets.map { it.id to it.visible }, migrated.presets.map { it.id to it.visible })
        assertEquals(expected.visibleObjectTools, migrated.visibleObjectTools)
    }

    @Test
    fun sixWidthLevelsShareThinAndWideBounds() {
        assertEquals(0.002f, 1.normalizedAnnotationWidth(), 0f)
        assertEquals(0.2f, 6.normalizedAnnotationWidth(), 0f)
        assertEquals(1, 0.002f.annotationWidthLevel())
        assertEquals(6, 0.2f.annotationWidthLevel())
        assertEquals(0.002f, 1.normalizedHighlighterWidth(), 0.0001f)
        assertEquals(0.2f, 6.normalizedHighlighterWidth(), 0.0001f)
        assertEquals(1, 0.002f.highlighterWidthLevel())
        assertEquals(6, 0.2f.highlighterWidthLevel())

        val widestHighlight = InkAnnotation(
            id = "wide-highlight",
            kind = InkKind.HIGHLIGHTER,
            width = 6.normalizedHighlighterWidth(),
            points = listOf(NormalizedPoint(0.1f, 0.5f), NormalizedPoint(0.9f, 0.5f)),
        )
        assertEquals(0.2f, widestHighlight.width, 0.0001f)
        assertThrows(IllegalArgumentException::class.java) {
            widestHighlight.copy(width = 0.2001f)
        }
    }

    @Test
    fun orderAndVisibilityRoundTripExactly() {
        val defaults = AnnotationEditorSettings.defaults()
        val expected = defaults.copy(
            drawOrder = defaults.drawOrder.reversed(),
            objectOrder = defaults.objectOrder.reversed(),
            visibleObjectTools = setOf("select", "text-box"),
            quickColors = defaults.quickColors.reversed(),
        )

        assertEquals(expected, AnnotationEditorSettingsJson.decode(AnnotationEditorSettingsJson.encode(expected)))
    }

    @Test
    fun legacyObjectOrderKeepsOldOrderAndAddsNewTools() {
        val root = JSONObject(AnnotationEditorSettingsJson.encode(AnnotationEditorSettings.defaults()))
        root.put(
                "objectOrder",
                JSONArray(
                    listOf(
                        "select",
                        "eraser",
                        "underline",
                        "strike-through",
                        "text-box",
                        "line",
                        "arrow",
                        "rectangle",
                        "ellipse",
                    ),
                ),
            )
        root.remove("visibleObjectTools")
        val raw = root.toString()

        val migrated = AnnotationEditorSettingsJson.decode(raw)

        assertEquals(
            listOf(
                "select",
                "underline",
                "strike-through",
                "text-box",
                "line",
                "arrow",
                "rectangle",
                "ellipse",
                "lasso",
                "symbol",
            ),
            migrated.objectOrder,
        )
        assertEquals(migrated.objectOrder.toSet(), migrated.visibleObjectTools)
    }

    @Test
    fun multiByteJsonOverByteLimitIsRejected() {
        val multiByteEntry = "á".repeat(8_000)
        val raw = JSONObject(AnnotationEditorSettingsJson.encode(AnnotationEditorSettings.defaults()))
            .put("objectOrder", JSONArray().put(multiByteEntry))
            .toString()

        assertTrue(raw.length < 16 * 1024)
        assertTrue(raw.toByteArray(UTF_8).size > 16 * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            AnnotationEditorSettingsJson.decode(raw)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnnotationEditorSettings.defaults().copy(objectOrder = listOf(multiByteEntry))
        }
    }
}
