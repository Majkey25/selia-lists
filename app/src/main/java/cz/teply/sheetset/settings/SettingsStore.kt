package cz.teply.sheetset.settings

import android.content.SharedPreferences
import cz.teply.sheetset.pdf.AnnotationEditorSettings
import cz.teply.sheetset.pdf.AnnotationEditorSettingsJson
import cz.teply.sheetset.pdf.DrawingPresetKind

private const val EDITOR_JSON_KEY = "annotation_editor_json"

class SettingsStore(private val preferences: SharedPreferences) {
    fun load(): AppSettings {
        val defaults = AppSettings()
        val editorRaw = preferences.getString(EDITOR_JSON_KEY, null)
        val legacyEditorUi = editorRaw?.let { raw ->
            runCatching { AnnotationEditorSettingsJson.isLegacy(raw) }.getOrDefault(false)
        } ?: false
        val editor = if (preferences.contains(EDITOR_JSON_KEY)) {
            editorRaw?.let { raw ->
                runCatching { AnnotationEditorSettingsJson.decode(raw) }.getOrNull()
            } ?: AnnotationEditorSettings.defaults()
        } else {
            legacyEditor(
                preferences.enum("pen_width", ToolSize.MEDIUM),
                preferences.enum("highlighter_strength", HighlightStrength.MEDIUM),
            )
        }
        return AppSettings(
            keepScreenAwake = preferences.getBoolean("keep_screen_awake", defaults.keepScreenAwake),
            pageFit = preferences.enum("page_fit", defaults.pageFit),
            pageTurnTaps = preferences.getBoolean("page_turn_taps", defaults.pageTurnTaps),
            pageTurnSwipes = preferences.getBoolean("page_turn_swipes", defaults.pageTurnSwipes),
            autoHideControls = preferences.getBoolean("auto_hide_controls", defaults.autoHideControls),
            defaultTool = preferences.enum("default_tool", defaults.defaultTool).let { tool ->
                if (legacyEditorUi && tool == ReaderDefaultTool.VIEW) ReaderDefaultTool.PEN else tool
            },
            textSize = preferences.enum("text_size", defaults.textSize),
            readerLayout = preferences.enum("reader_layout", defaults.readerLayout),
            themeMode = preferences.enum("theme_mode", defaults.themeMode),
            themePalette = preferences.enum("theme_palette", defaults.themePalette),
            editor = editor,
        )
    }

    fun save(settings: AppSettings) {
        preferences.edit()
            .putBoolean("keep_screen_awake", settings.keepScreenAwake)
            .putString("page_fit", settings.pageFit.name)
            .putBoolean("page_turn_taps", settings.pageTurnTaps)
            .putBoolean("page_turn_swipes", settings.pageTurnSwipes)
            .putBoolean("auto_hide_controls", settings.autoHideControls)
            .putString("default_tool", settings.defaultTool.name)
            .remove("pen_width")
            .remove("highlighter_strength")
            .putString("text_size", settings.textSize.name)
            .putString("reader_layout", settings.readerLayout.name)
            .putString("theme_mode", settings.themeMode.name)
            .putString("theme_palette", settings.themePalette.name)
            .putString(EDITOR_JSON_KEY, AnnotationEditorSettingsJson.encode(settings.editor))
            .apply()
    }
}

private fun legacyEditor(
    penWidth: ToolSize,
    highlighterStrength: HighlightStrength,
): AnnotationEditorSettings {
    val width = when (penWidth) {
        ToolSize.THIN -> 1
        ToolSize.MEDIUM -> 2
        ToolSize.THICK -> 4
    }
    val opacity = when (highlighterStrength) {
        HighlightStrength.LIGHT -> 70
        HighlightStrength.MEDIUM -> 105
        HighlightStrength.STRONG -> 150
    }
    val defaults = AnnotationEditorSettings.defaults()
    return defaults.copy(
        presets = defaults.presets.map { preset ->
            if (preset.kind == DrawingPresetKind.HIGHLIGHTER) {
                preset.copy(width = 5, opacity = opacity)
            } else if (preset.kind == DrawingPresetKind.PEN) {
                preset.copy(width = width)
            } else {
                preset
            }
        },
    )
}

private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, fallback: T): T =
    getString(key, null)?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } } ?: fallback
