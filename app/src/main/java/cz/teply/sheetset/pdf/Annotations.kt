package cz.teply.sheetset.pdf

import cz.teply.sheetset.settings.AnnotationTextSize
import org.json.JSONArray
import org.json.JSONObject

const val MAX_ANNOTATIONS_PER_PAGE = 10_000
const val MAX_POINTS_PER_INK = 4_096
const val MAX_TEXT_LENGTH = 4_000
const val LEGACY_HIGHLIGHTER_OPACITY = 105
const val DEFAULT_ANNOTATION_OPACITY = 255
val SUPPORTED_SYMBOL_IDS = setOf(
    "sharp", "flat", "natural", "fermata", "accent", "breath",
    "crescendo", "decrescendo", "p", "mf", "f", "ff",
)
private const val MAX_MARKUP_RECTS = 512
private const val MAX_ID_LENGTH = 128
private const val MAX_HISTORY_STEPS = 100

fun canAppendAnnotations(currentCount: Int, additionalCount: Int): Boolean =
    currentCount in 0..MAX_ANNOTATIONS_PER_PAGE &&
        additionalCount >= 0 &&
        additionalCount <= MAX_ANNOTATIONS_PER_PAGE - currentCount

enum class InkKind { PEN, HIGHLIGHTER }
enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKE_THROUGH }
enum class ShapeKind { LINE, ARROW, RECTANGLE, ELLIPSE }

@JvmInline
value class AnnotationColor(val argb: Int) {
    init {
        require(argb ushr 24 == 0xFF) { "Annotation color must be opaque" }
    }

    fun encoded(): String = "#" + argb.toUInt().toString(16).uppercase().padStart(8, '0')

    companion object {
        val BLACK = AnnotationColor(0xFF111111.toInt())
        val RED = AnnotationColor(0xFFD32F2F.toInt())
        val ORANGE = AnnotationColor(0xFFF57C00.toInt())
        val YELLOW = AnnotationColor(0xFFFBC02D.toInt())
        val GREEN = AnnotationColor(0xFF388E3C.toInt())
        val BLUE = AnnotationColor(0xFF1976D2.toInt())
        val PURPLE = AnnotationColor(0xFF7B1FA2.toInt())
        val PINK = AnnotationColor(0xFFC2185B.toInt())

        fun decode(raw: String): AnnotationColor {
            require(raw.matches(Regex("#[0-9A-Fa-f]{8}"))) { "Invalid annotation color" }
            return AnnotationColor(raw.drop(1).toUInt(16).toInt())
        }
    }
}

enum class AnnotationTextAlignment { START, CENTER, END }

data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "Point must be normalized" }
    }
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Rectangle must be normalized"
        }
        require(left < right && top < bottom) { "Rectangle must have positive size" }
    }
}

data class PageBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    init {
        require(width > 0f && height > 0f) { "Page bounds must be positive" }
    }
}

fun normalizePoint(x: Float, y: Float, bounds: PageBounds): NormalizedPoint? {
    if (x !in bounds.left..(bounds.left + bounds.width)) return null
    if (y !in bounds.top..(bounds.top + bounds.height)) return null
    return NormalizedPoint(
        x = (x - bounds.left) / bounds.width,
        y = (y - bounds.top) / bounds.height,
    )
}

sealed interface PageAnnotation {
    val id: String
}

data class InkAnnotation(
    override val id: String,
    val kind: InkKind,
    val width: Float,
    val points: List<NormalizedPoint>,
    val color: AnnotationColor = AnnotationColor.BLACK,
    val opacity: Int = DEFAULT_ANNOTATION_OPACITY,
) : PageAnnotation {
    init {
        requireValidId(id)
        require(width > 0f && width <= 0.2f) { "Invalid ink width" }
        require(points.isNotEmpty() && points.size <= MAX_POINTS_PER_INK) {
            "Invalid ink point count"
        }
        require(opacity in 0..255) { "Invalid annotation opacity" }
    }
}

data class MarkupAnnotation(
    override val id: String,
    val kind: MarkupKind,
    val bounds: List<NormalizedRect>,
    val color: AnnotationColor = AnnotationColor.BLACK,
    val opacity: Int = DEFAULT_ANNOTATION_OPACITY,
) : PageAnnotation {
    init {
        requireValidId(id)
        require(bounds.isNotEmpty() && bounds.size <= MAX_MARKUP_RECTS) {
            "Invalid markup bounds"
        }
        require(opacity in 0..255) { "Invalid annotation opacity" }
    }
}

data class TextBoxAnnotation(
    override val id: String,
    val bounds: NormalizedRect,
    val text: String,
    val size: AnnotationTextSize,
    val lineHeight: Float = 1f,
    val alignment: AnnotationTextAlignment = AnnotationTextAlignment.START,
    val color: AnnotationColor = AnnotationColor.BLACK,
    val opacity: Int = DEFAULT_ANNOTATION_OPACITY,
) : PageAnnotation {
    init {
        requireValidId(id)
        require(text.isNotBlank() && text.length <= MAX_TEXT_LENGTH) { "Invalid annotation text" }
        require(lineHeight in 0.8f..2f) { "Invalid annotation line height" }
        require(opacity in 0..255) { "Invalid annotation opacity" }
    }
}

data class ShapeAnnotation(
    override val id: String,
    val kind: ShapeKind,
    val start: NormalizedPoint,
    val end: NormalizedPoint,
    val width: Float,
    val color: AnnotationColor = AnnotationColor.BLACK,
    val opacity: Int = DEFAULT_ANNOTATION_OPACITY,
) : PageAnnotation {
    init {
        requireValidId(id)
        require(width > 0f && width <= 0.2f) { "Invalid shape width" }
        require(opacity in 0..255) { "Invalid annotation opacity" }
    }
}

data class SymbolAnnotation(
    override val id: String,
    val symbolId: String,
    val center: NormalizedPoint,
    val size: Float,
    val rotationDegrees: Float,
    val color: AnnotationColor,
    val opacity: Int,
) : PageAnnotation {
    init {
        requireValidId(id)
        require(symbolId in SUPPORTED_SYMBOL_IDS) { "Unsupported symbol ID" }
        require(size in 0.01f..0.5f) { "Invalid symbol size" }
        require(rotationDegrees in -360f..360f) { "Invalid symbol rotation" }
        require(opacity in 0..255) { "Invalid annotation opacity" }
    }
}

fun SymbolAnnotation.rotated(degrees: Float): SymbolAnnotation = copy(
    rotationDegrees = ((degrees % 360f) + 360f) % 360f,
)

data class DocumentAnnotations(
    val pages: Map<Int, List<PageAnnotation>> = emptyMap(),
) {
    init {
        require(pages.all { (page, annotations) ->
            page >= 0 && annotations.size <= MAX_ANNOTATIONS_PER_PAGE
        }) { "Invalid page annotations" }
        val ids = pages.values.flatten().map(PageAnnotation::id)
        require(ids.size == ids.distinct().size) { "Duplicate annotation ID" }
    }

    fun withPage(page: Int, annotations: List<PageAnnotation>): DocumentAnnotations {
        require(page >= 0) { "Page index must not be negative" }
        val next = pages.toMutableMap()
        if (annotations.isEmpty()) next.remove(page) else next[page] = annotations
        return DocumentAnnotations(next)
    }
}

data class AnnotationHistory(
    val annotations: List<PageAnnotation> = emptyList(),
    private val undoStates: List<List<PageAnnotation>> = emptyList(),
    private val redoStates: List<List<PageAnnotation>> = emptyList(),
) {
    val canUndo: Boolean get() = undoStates.isNotEmpty()
    val canRedo: Boolean get() = redoStates.isNotEmpty()

    init {
        require(annotations.size <= MAX_ANNOTATIONS_PER_PAGE) { "Page annotation limit reached" }
        require(annotations.map(PageAnnotation::id).distinct().size == annotations.size) {
            "Duplicate annotation ID"
        }
    }

    fun add(annotation: PageAnnotation): AnnotationHistory {
        require(annotations.size < MAX_ANNOTATIONS_PER_PAGE) { "Page annotation limit reached" }
        require(annotations.none { it.id == annotation.id }) { "Duplicate annotation ID" }
        return replace(annotations + annotation)
    }

    fun update(annotation: PageAnnotation): AnnotationHistory {
        val index = annotations.indexOfFirst { it.id == annotation.id }
        if (index < 0 || annotations[index] == annotation) return this
        return replace(annotations.toMutableList().apply { this[index] = annotation })
    }

    fun delete(id: String): AnnotationHistory {
        val remaining = annotations.filterNot { it.id == id }
        return if (remaining.size == annotations.size) this else replace(remaining)
    }

    fun commit(next: List<PageAnnotation>): AnnotationHistory =
        if (next == annotations) this else replace(next)

    fun undo(): AnnotationHistory {
        if (undoStates.isEmpty()) return this
        return AnnotationHistory(
            annotations = undoStates.last(),
            undoStates = undoStates.dropLast(1),
            redoStates = (redoStates + listOf(annotations)).takeLast(MAX_HISTORY_STEPS),
        )
    }

    fun redo(): AnnotationHistory {
        if (redoStates.isEmpty()) return this
        return AnnotationHistory(
            annotations = redoStates.last(),
            undoStates = (undoStates + listOf(annotations)).takeLast(MAX_HISTORY_STEPS),
            redoStates = redoStates.dropLast(1),
        )
    }

    fun erase(point: NormalizedPoint, radius: Float): AnnotationHistory {
        require(radius > 0f) { "Eraser radius must be positive" }
        val remaining = annotations.filterNot { annotation -> annotation.hitTest(point, radius) }
        return if (remaining.size == annotations.size) this else replace(remaining)
    }

    private fun replace(next: List<PageAnnotation>): AnnotationHistory = AnnotationHistory(
        annotations = next,
        undoStates = (undoStates + listOf(annotations)).takeLast(MAX_HISTORY_STEPS),
    )
}

private fun requireValidId(id: String) {
    require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "Invalid annotation ID" }
}

object AnnotationJson {
    private const val VERSION = 3

    fun encode(annotations: DocumentAnnotations): String = JSONObject()
        .put("version", VERSION)
        .put(
            "pages",
            JSONObject().apply {
                annotations.pages.toSortedMap().forEach { (page, pageAnnotations) ->
                    put(
                        page.toString(),
                        JSONArray().apply {
                            pageAnnotations.forEach { annotation -> put(annotation.toJson()) }
                        },
                    )
                }
            },
        )
        .toString()

    fun decode(raw: String): DocumentAnnotations {
        val root = JSONObject(raw)
        val pages = root.getJSONObject("pages")
        require(pages.keys().asSequence().map(String::toInt).toSet().size == pages.length()) {
            "Duplicate page index"
        }
        return when (root.exactInt("version")) {
            1 -> decodeVersionOne(root)
            2 -> decodeVersionTwo(root)
            VERSION -> decodeVersionThree(root)
            else -> throw IllegalArgumentException("Unsupported annotation version")
        }
    }

    private fun JSONObject.exactInt(key: String): Int {
        val value = getDouble(key)
        require(value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() && value % 1.0 == 0.0) {
            "Invalid annotation integer: $key"
        }
        return value.toInt()
    }

    private fun decodeVersionOne(root: JSONObject): DocumentAnnotations {
        val pagesJson = root.getJSONObject("pages")
        val pages = pagesJson.keys().asSequence().associate { pageKey ->
            val page = pageKey.toInt()
            val strokes = pagesJson.getJSONArray(pageKey)
            page to List(strokes.length()) { index ->
                strokes.getJSONObject(index).toLegacyInk("legacy-$page-$index")
            }
        }
        return DocumentAnnotations(pages)
    }

    private fun decodeVersionTwo(root: JSONObject): DocumentAnnotations {
        val pagesJson = root.getJSONObject("pages")
        val pages = pagesJson.keys().asSequence().associate { pageKey ->
            val page = pageKey.toInt()
            val annotations = pagesJson.getJSONArray(pageKey)
            page to List(annotations.length()) { index ->
                annotations.getJSONObject(index).toVersionTwoAnnotation()
            }
        }
        return DocumentAnnotations(pages)
    }

    private fun decodeVersionThree(root: JSONObject): DocumentAnnotations {
        val pagesJson = root.getJSONObject("pages")
        val pages = pagesJson.keys().asSequence().associate { pageKey ->
            val page = pageKey.toInt()
            val annotations = pagesJson.getJSONArray(pageKey)
            page to List(annotations.length()) { index ->
                annotations.getJSONObject(index).toVersionThreeAnnotation()
            }
        }
        return DocumentAnnotations(pages)
    }

    private fun PageAnnotation.toJson(): JSONObject = when (this) {
        is InkAnnotation -> JSONObject()
            .put("id", id)
            .put("type", "ink")
            .put("kind", kind.name)
            .put("color", color.encoded())
            .put("opacity", opacity)
            .put("width", width.toDouble())
            .put("points", JSONArray().apply { points.forEach { put(it.toJson()) } })

        is MarkupAnnotation -> JSONObject()
            .put("id", id)
            .put("type", "markup")
            .put("kind", kind.name)
            .put("color", color.encoded())
            .put("opacity", opacity)
            .put("bounds", JSONArray().apply { bounds.forEach { put(it.toJson()) } })

        is TextBoxAnnotation -> JSONObject()
            .put("id", id)
            .put("type", "text")
            .put("bounds", bounds.toJson())
            .put("text", text)
            .put("size", size.name)
            .put("lineHeight", lineHeight.toDouble())
            .put("alignment", alignment.name)
            .put("color", color.encoded())
            .put("opacity", opacity)

        is ShapeAnnotation -> JSONObject()
            .put("id", id)
            .put("type", "shape")
            .put("kind", kind.name)
            .put("start", start.toJson())
            .put("end", end.toJson())
            .put("width", width.toDouble())
            .put("color", color.encoded())
            .put("opacity", opacity)

        is SymbolAnnotation -> JSONObject()
            .put("id", id)
            .put("type", "symbol")
            .put("symbolId", symbolId)
            .put("center", center.toJson())
            .put("size", size.toDouble())
            .put("rotationDegrees", rotationDegrees.toDouble())
            .put("color", color.encoded())
            .put("opacity", opacity)
    }

    private fun JSONObject.toVersionTwoAnnotation(): PageAnnotation = when (getString("type")) {
        "ink" -> {
            val kind = InkKind.valueOf(getString("kind"))
            InkAnnotation(
                id = getString("id"),
                kind = kind,
                width = getDouble("width").toFloat(),
                points = getJSONArray("points").toPoints(),
                color = annotationColor(
                    if (kind == InkKind.HIGHLIGHTER) {
                        AnnotationColor.YELLOW
                    } else {
                        AnnotationColor.BLACK
                    },
                ),
                opacity = if (kind == InkKind.HIGHLIGHTER) {
                    LEGACY_HIGHLIGHTER_OPACITY
                } else {
                    DEFAULT_ANNOTATION_OPACITY
                },
            )
        }

        "markup" -> {
            val kind = MarkupKind.valueOf(getString("kind"))
            MarkupAnnotation(
                id = getString("id"),
                kind = kind,
                bounds = getJSONArray("bounds").toRects(),
                color = annotationColor(
                    if (kind == MarkupKind.HIGHLIGHT) {
                        AnnotationColor.YELLOW
                    } else {
                        AnnotationColor.BLACK
                    },
                ),
                opacity = if (kind == MarkupKind.HIGHLIGHT) {
                    LEGACY_HIGHLIGHTER_OPACITY
                } else {
                    DEFAULT_ANNOTATION_OPACITY
                },
            )
        }

        "text" -> TextBoxAnnotation(
            id = getString("id"),
            bounds = getJSONArray("bounds").toRect(),
            text = getString("text"),
            size = AnnotationTextSize.valueOf(getString("size")),
            lineHeight = 1f,
            alignment = AnnotationTextAlignment.START,
            color = annotationColor(AnnotationColor.BLACK),
            opacity = DEFAULT_ANNOTATION_OPACITY,
        )

        "shape" -> ShapeAnnotation(
            id = getString("id"),
            kind = ShapeKind.valueOf(getString("kind")),
            start = getJSONArray("start").toPoint(),
            end = getJSONArray("end").toPoint(),
            width = getDouble("width").toFloat(),
            color = annotationColor(AnnotationColor.BLACK),
            opacity = DEFAULT_ANNOTATION_OPACITY,
        )

        else -> throw IllegalArgumentException("Unsupported annotation type")
    }

    private fun JSONObject.toVersionThreeAnnotation(): PageAnnotation = when (getString("type")) {
        "ink" -> InkAnnotation(
            id = getString("id"),
            kind = InkKind.valueOf(getString("kind")),
            width = getDouble("width").toFloat(),
            points = getJSONArray("points").toPoints(),
            color = AnnotationColor.decode(getString("color")),
            opacity = exactInt("opacity"),
        )

        "markup" -> MarkupAnnotation(
            id = getString("id"),
            kind = MarkupKind.valueOf(getString("kind")),
            bounds = getJSONArray("bounds").toRects(),
            color = AnnotationColor.decode(getString("color")),
            opacity = exactInt("opacity"),
        )

        "text" -> TextBoxAnnotation(
            id = getString("id"),
            bounds = getJSONArray("bounds").toRect(),
            text = getString("text"),
            size = AnnotationTextSize.valueOf(getString("size")),
            lineHeight = getDouble("lineHeight").toFloat(),
            alignment = AnnotationTextAlignment.valueOf(getString("alignment")),
            color = AnnotationColor.decode(getString("color")),
            opacity = exactInt("opacity"),
        )

        "shape" -> ShapeAnnotation(
            id = getString("id"),
            kind = ShapeKind.valueOf(getString("kind")),
            start = getJSONArray("start").toPoint(),
            end = getJSONArray("end").toPoint(),
            width = getDouble("width").toFloat(),
            color = AnnotationColor.decode(getString("color")),
            opacity = exactInt("opacity"),
        )

        "symbol" -> SymbolAnnotation(
            id = getString("id"),
            symbolId = getString("symbolId"),
            center = getJSONArray("center").toPoint(),
            size = getDouble("size").toFloat(),
            rotationDegrees = getDouble("rotationDegrees").toFloat(),
            color = AnnotationColor.decode(getString("color")),
            opacity = exactInt("opacity"),
        )

        else -> throw IllegalArgumentException("Unsupported annotation type")
    }

    private fun JSONObject.toLegacyInk(id: String): InkAnnotation = InkAnnotation(
        id = id,
        kind = InkKind.valueOf(getString("tool")),
        width = getDouble("width").toFloat(),
        points = getJSONArray("points").toPoints(),
        color = if (getString("tool") == InkKind.HIGHLIGHTER.name) {
            AnnotationColor.YELLOW
        } else {
            AnnotationColor.BLACK
        },
        opacity = if (getString("tool") == InkKind.HIGHLIGHTER.name) {
            LEGACY_HIGHLIGHTER_OPACITY
        } else {
            DEFAULT_ANNOTATION_OPACITY
        },
    )

    private fun JSONObject.annotationColor(default: AnnotationColor): AnnotationColor =
        optString("color", "").takeIf(String::isNotBlank)?.let { color ->
            when (color) {
                "BLACK" -> AnnotationColor.BLACK
                "RED" -> AnnotationColor.RED
                "ORANGE" -> AnnotationColor.ORANGE
                "YELLOW" -> AnnotationColor.YELLOW
                "GREEN" -> AnnotationColor.GREEN
                "BLUE" -> AnnotationColor.BLUE
                "PURPLE" -> AnnotationColor.PURPLE
                "PINK" -> AnnotationColor.PINK
                else -> throw IllegalArgumentException("Invalid annotation color")
            }
        } ?: default

    private fun NormalizedPoint.toJson(): JSONArray = JSONArray(listOf(x, y))

    private fun NormalizedRect.toJson(): JSONArray = JSONArray(listOf(left, top, right, bottom))

    private fun JSONArray.toPoints(): List<NormalizedPoint> =
        List(length()) { index -> getJSONArray(index).toPoint() }

    private fun JSONArray.toRects(): List<NormalizedRect> =
        List(length()) { index -> getJSONArray(index).toRect() }

    private fun JSONArray.toPoint(): NormalizedPoint = NormalizedPoint(
        getDouble(0).toFloat(),
        getDouble(1).toFloat(),
    )

    private fun JSONArray.toRect(): NormalizedRect = NormalizedRect(
        getDouble(0).toFloat(),
        getDouble(1).toFloat(),
        getDouble(2).toFloat(),
        getDouble(3).toFloat(),
    )
}
