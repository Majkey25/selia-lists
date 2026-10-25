package cz.teply.sheetset.pdf

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_EDITOR_JSON_BYTES = 16 * 1024
private const val EDITOR_JSON_VERSION = 3
internal const val MIN_ANNOTATION_WIDTH = 1
internal const val MAX_ANNOTATION_WIDTH = 6
private val ANNOTATION_WIDTH_STOPS = floatArrayOf(0.002f, 0.005f, 0.013f, 0.032f, 0.08f, 0.2f)

val DEFAULT_DRAWING_PRESET_IDS = listOf("pen-1", "pen-2", "marker", "highlighter")

private val LEGACY_OBJECT_TOOL_ORDER = listOf(
    "select",
    "eraser",
    "underline",
    "strike-through",
    "text-box",
    "line",
    "arrow",
    "rectangle",
    "ellipse",
)

enum class AnnotationToolGroup { DRAW, OBJECTS }

enum class DrawingPresetKind { PEN, MARKER, HIGHLIGHTER }

data class DrawingPreset(
    val id: String,
    val kind: DrawingPresetKind,
    val color: AnnotationColor,
    val width: Int,
    val opacity: Int,
    val visible: Boolean = true,
)

data class AnnotationEditorSettings(
    val presets: List<DrawingPreset>,
    val drawOrder: List<String>,
    val objectOrder: List<String>,
    val visibleObjectTools: Set<String> = objectOrder.toSet(),
    val quickColors: List<AnnotationColor>,
    val recentColors: List<AnnotationColor> = emptyList(),
    val palmRejection: Boolean = false,
) {
    init {
        require(
            presets.size == DEFAULT_DRAWING_PRESET_IDS.size &&
                presets.map(DrawingPreset::id).toSet() == DEFAULT_DRAWING_PRESET_IDS.toSet(),
        ) {
            "Every known drawing preset is required exactly once"
        }
        require(
            presets.all {
                it.id.isNotBlank() &&
                    it.width in MIN_ANNOTATION_WIDTH..MAX_ANNOTATION_WIDTH &&
                    it.opacity in 0..255
            },
        ) {
            "Invalid drawing preset"
        }
        require(drawOrder.size == presets.size && drawOrder.toSet() == presets.map(DrawingPreset::id).toSet()) {
            "Drawing order must contain every preset exactly once"
        }
        require(presets.any(DrawingPreset::visible)) { "At least one drawing tool must remain visible" }
        require(
            objectOrder.size == DEFAULT_OBJECT_TOOL_ORDER.size &&
                objectOrder.toSet() == DEFAULT_OBJECT_TOOL_ORDER.toSet(),
        ) {
            "Object order must contain every object tool exactly once"
        }
        require(visibleObjectTools.isNotEmpty() && visibleObjectTools.all(objectOrder::contains)) {
            "At least one known object tool must remain visible"
        }
        require(quickColors.isNotEmpty() && quickColors.size <= 8 && quickColors.distinct() == quickColors) {
            "Quick colors must contain one to eight unique colors"
        }
        require(recentColors.size <= 4) { "Too many recent colors" }
        require(AnnotationEditorSettingsJson.encodedByteSize(this) <= MAX_EDITOR_JSON_BYTES) {
            "Editor settings are too large"
        }
    }

    companion object {
        fun defaults(): AnnotationEditorSettings = AnnotationEditorSettings(
            presets = listOf(
                DrawingPreset("pen-1", DrawingPresetKind.PEN, AnnotationColor.BLACK, 2, 255),
                DrawingPreset("pen-2", DrawingPresetKind.PEN, AnnotationColor.BLUE, 2, 255, false),
                DrawingPreset("marker", DrawingPresetKind.MARKER, AnnotationColor.GREEN, 3, 255, false),
                DrawingPreset("highlighter", DrawingPresetKind.HIGHLIGHTER, AnnotationColor.YELLOW, 5, 105),
            ),
            drawOrder = listOf("pen-1", "pen-2", "marker", "highlighter"),
            objectOrder = DEFAULT_OBJECT_TOOL_ORDER,
            visibleObjectTools = DEFAULT_OBJECT_TOOL_ORDER.toSet() - "lasso",
            quickColors = listOf(
                AnnotationColor.BLACK,
                AnnotationColor.RED,
                AnnotationColor.ORANGE,
                AnnotationColor.YELLOW,
                AnnotationColor.GREEN,
                AnnotationColor.BLUE,
                AnnotationColor.PURPLE,
                AnnotationColor.PINK,
            ),
        )
    }

    fun preset(id: String): DrawingPreset = presets.single { it.id == id }
}

object AnnotationEditorSettingsJson {
    fun encode(settings: AnnotationEditorSettings): String = settings.toJson().toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_EDITOR_JSON_BYTES) {
            "Editor settings are too large"
        }
    }

    fun decode(raw: String): AnnotationEditorSettings {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_EDITOR_JSON_BYTES) {
            "Editor settings are too large"
        }
        val root = JSONObject(raw)
        val version = root.editorVersion()
        val objectOrder = root.getJSONArray("objectOrder").strings().migrateObjectOrder()
        val visibleObjectTools = root.optJSONArray("visibleObjectTools")
            ?.strings()
            ?.toSet()
            ?: objectOrder.toSet()
        return AnnotationEditorSettings(
            presets = root.getJSONArray("presets").presets(version),
            drawOrder = root.getJSONArray("drawOrder").strings(),
            objectOrder = objectOrder,
            visibleObjectTools = if (version == 1 && visibleObjectTools.size > 1) {
                visibleObjectTools - "lasso"
            } else {
                visibleObjectTools
            },
            quickColors = root.getJSONArray("quickColors").colors(),
            recentColors = root.getJSONArray("recentColors").colors(),
            palmRejection = root.getBoolean("palmRejection"),
        )
    }

    internal fun isLegacy(raw: String): Boolean = JSONObject(raw).editorVersion() == 1

    internal fun encodedByteSize(settings: AnnotationEditorSettings): Int =
        settings.toJson().toString().toByteArray(Charsets.UTF_8).size

    private fun AnnotationEditorSettings.toJson(): JSONObject = JSONObject()
        .put("version", EDITOR_JSON_VERSION)
        .put("presets", JSONArray().apply { presets.forEach { put(it.toJson()) } })
        .put("drawOrder", JSONArray().apply { drawOrder.forEach(::put) })
        .put("objectOrder", JSONArray().apply { objectOrder.forEach(::put) })
        .put(
            "visibleObjectTools",
            JSONArray().apply { objectOrder.filter(visibleObjectTools::contains).forEach(::put) },
        )
        .put("quickColors", JSONArray().apply { quickColors.forEach { put(it.encoded()) } })
        .put("recentColors", JSONArray().apply { recentColors.forEach { put(it.encoded()) } })
        .put("palmRejection", palmRejection)

    private fun DrawingPreset.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("color", color.encoded())
        .put("width", width)
        .put("opacity", opacity)
        .put("visible", visible)

    private fun JSONArray.presets(version: Int): List<DrawingPreset> {
        val presets = List(length()) { index ->
            getJSONObject(index).let { preset ->
                val kind = DrawingPresetKind.valueOf(preset.getString("kind"))
                DrawingPreset(
                    id = preset.getString("id"),
                    kind = kind,
                    color = AnnotationColor.decode(preset.getString("color")),
                    width = preset.getInt("width").migrateWidth(version, kind),
                    opacity = preset.getInt("opacity"),
                    visible = preset.getBoolean("visible"),
                )
            }
        }
        val duplicateIds = setOf("pen-2", "marker")
        return if (version == 1 && presets.any { it.visible && it.id !in duplicateIds }) {
            presets.map { if (it.id in duplicateIds) it.copy(visible = false) else it }
        } else {
            presets
        }
    }

    private fun JSONObject.editorVersion(): Int = optInt("version", 1).also { version ->
        require(version in 1..EDITOR_JSON_VERSION) { "Unsupported editor settings version" }
    }

    private fun Int.migrateWidth(version: Int, kind: DrawingPresetKind): Int {
        if (version >= EDITOR_JSON_VERSION) return this
        val oldLevel = if (version == 1) {
            require(this in 1..40) { "Invalid legacy drawing preset" }
            (this / 10f).roundToInt().coerceIn(1, 10).let { level ->
                if (kind == DrawingPresetKind.HIGHLIGHTER) level.coerceAtLeast(5) else level
            }
        } else {
            require(this in 1..10) { "Invalid drawing preset width" }
            this
        }
        val oldWidth = if (kind == DrawingPresetKind.HIGHLIGHTER) {
            oldLevel * 0.02f
        } else {
            oldLevel / 500f
        }
        return oldWidth.sharedWidthLevel()
    }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    private fun List<String>.migrateObjectOrder(): List<String> = when (this) {
        LEGACY_OBJECT_TOOL_ORDER -> filterNot { it == "eraser" } + listOf("lasso", "symbol")
        else -> this
    }

    private fun JSONArray.colors(): List<AnnotationColor> = List(length()) {
        AnnotationColor.decode(getString(it))
    }
}

internal fun Int.normalizedAnnotationWidth(): Float =
    ANNOTATION_WIDTH_STOPS[coerceIn(MIN_ANNOTATION_WIDTH, MAX_ANNOTATION_WIDTH) - 1]

internal fun Float.annotationWidthLevel(): Int = sharedWidthLevel()

internal fun Int.normalizedHighlighterWidth(): Float =
    normalizedAnnotationWidth()

internal fun Float.highlighterWidthLevel(): Int =
    sharedWidthLevel()

private fun Float.sharedWidthLevel(): Int = ANNOTATION_WIDTH_STOPS.indices
    .minBy { index -> abs(this - ANNOTATION_WIDTH_STOPS[index]) } + 1
