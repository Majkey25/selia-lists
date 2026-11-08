package cz.teply.sheetset.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.AnnotationEditorSettings
import cz.teply.sheetset.pdf.AnnotationEditorSettingsJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONObject

class SettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("settings-test", Context.MODE_PRIVATE)

    @After
    fun cleanUp() {
        preferences.edit().clear().commit()
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        preferences.edit()
            .putString("page_fit", "BROKEN")
            .putString("pen_width", "MISSING")
            .putString("reader_layout", "UNKNOWN")
            .putString("theme_palette", "UNKNOWN")
            .putBoolean("keep_screen_awake", false)
            .commit()

        assertEquals(
            AppSettings(keepScreenAwake = false),
            SettingsStore(preferences).load(),
        )
    }

    @Test
    fun settingsRoundTrip() {
        val expected = AppSettings(
            pageFit = PageFit.WIDTH,
            pageTurnTaps = false,
            pageTurnSwipes = false,
            autoHideControls = false,
            defaultTool = ReaderDefaultTool.PEN,
            textSize = AnnotationTextSize.LARGE,
            readerLayout = ReaderLayout.HALF,
            themeMode = ThemeMode.SYSTEM,
            themePalette = ThemePalette.IRIS,
        )

        val store = SettingsStore(preferences)
        store.save(expected)

        assertEquals(expected, store.load())
        assertFalse(preferences.contains("pen_width"))
        assertFalse(preferences.contains("highlighter_strength"))
    }

    @Test
    fun existingDarkPreferenceKeepsMonochrome() {
        preferences.edit().putString("theme_mode", "DARK").commit()

        val settings = SettingsStore(preferences).load()
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(ThemePalette.MONOCHROME, settings.themePalette)
    }

    @Test
    fun missingEditorJsonUsesLegacyPenAndHighlighterValues() {
        preferences.edit()
            .putString("pen_width", ToolSize.THICK.name)
            .putString("highlighter_strength", HighlightStrength.LIGHT.name)
            .apply()

        val editor = SettingsStore(preferences).load().editor

        assertEquals(4, editor.preset("pen-1").width)
        assertEquals(70, editor.preset("highlighter").opacity)
    }

    @Test
    fun legacyEditorUiStartsWithPenInsteadOfSelectionCursor() {
        val legacyEditor = JSONObject(
            AnnotationEditorSettingsJson.encode(AnnotationEditorSettings.defaults()),
        ).apply { remove("version") }.toString()
        preferences.edit()
            .putString("annotation_editor_json", legacyEditor)
            .putString("default_tool", ReaderDefaultTool.VIEW.name)
            .commit()

        val settings = SettingsStore(preferences).load()

        assertEquals(ReaderDefaultTool.PEN, settings.defaultTool)
    }

    @Test
    fun malformedPresentEditorJsonDoesNotReactivateLegacyValues() {
        preferences.edit()
            .putString("annotation_editor_json", "{broken")
            .putString("pen_width", ToolSize.THICK.name)
            .putString("highlighter_strength", HighlightStrength.STRONG.name)
            .commit()

        assertEquals(
            AnnotationEditorSettings.defaults(),
            SettingsStore(preferences).load().editor,
        )
    }

    @Test
    fun customEditorOrderAndVisibilityRoundTripThroughStore() {
        val defaults = AnnotationEditorSettings.defaults()
        val editor = defaults.copy(
            drawOrder = defaults.drawOrder.reversed(),
            objectOrder = defaults.objectOrder.reversed(),
            visibleObjectTools = setOf("ellipse", "lasso"),
            quickColors = listOf(AnnotationColor.PINK, AnnotationColor.BLUE, AnnotationColor.BLACK),
            presets = defaults.presets.map { preset ->
                preset.copy(visible = preset.id != "highlighter")
            },
        )
        val expected = AppSettings(editor = editor)
        val store = SettingsStore(preferences)

        store.save(expected)

        assertEquals(expected, store.load())
    }
}
