package cz.teply.sheetset.pdf

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val MIN_ANNOTATION_SIZE = 0.01f
private const val DUPLICATE_OFFSET = 0.01f

enum class AnnotationHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT, START, END, ROTATION }

fun List<PageAnnotation>.topmostHit(
    point: NormalizedPoint,
    radius: Float,
    pageWidth: Float = 1f,
    pageHeight: Float = 1f,
): PageAnnotation? = asReversed().firstOrNull { it.hitTest(point, radius, pageWidth, pageHeight) }

fun PageAnnotation.hitTest(
    point: NormalizedPoint,
    radius: Float,
    pageWidth: Float = 1f,
    pageHeight: Float = 1f,
): Boolean {
    require(radius.isFinite() && radius >= 0f) { "Hit radius must be finite and not negative" }
    requirePageSize(pageWidth, pageHeight)
    val position = point.onPage(pageWidth, pageHeight)
    val hitRadius = radius.toDouble() * min(pageWidth, pageHeight)
    return when (this) {
        is InkAnnotation -> when {
            kind == InkKind.HIGHLIGHTER -> highlighterPolygons(pageWidth, pageHeight)
                .any { it.hit(position, hitRadius) }
            else -> {
                val strokeRadius = width.toDouble() * min(pageWidth, pageHeight) / 2 + hitRadius
                val path = points.map { it.onPage(pageWidth, pageHeight) }
                if (path.size == 1) {
                    distanceToSegment(position, path.single(), path.single()) <= strokeRadius
                } else {
                    path.zipWithNext().any { (start, end) ->
                        distanceToSegment(position, start, end) <= strokeRadius
                    }
                }
            }
        }

        is MarkupAnnotation -> bounds.any { it.contains(point, radius) }
        is TextBoxAnnotation -> bounds.contains(point, radius)
        is SymbolAnnotation -> normalizedBounds().contains(point, radius)
        is ShapeAnnotation -> when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> {
                val from = start.onPage(pageWidth, pageHeight)
                val to = end.onPage(pageWidth, pageHeight)
                val strokeRadius = hitRadius + width.toDouble() * min(pageWidth, pageHeight) / 2
                if (distanceToSegment(position, from, to) <= strokeRadius) {
                    true
                } else if (kind == ShapeKind.LINE) {
                    false
                } else {
                    // Match the two arrowhead segments in AnnotationRenderer.drawShape.
                    val angle = atan2(to.y - from.y, to.x - from.x)
                    val length = hypot(to.x - from.x, to.y - from.y)
                    val head = min(length * 0.25f, min(pageWidth, pageHeight).toDouble() * 0.04f)
                    val spread = 0.55f
                    listOf(angle - spread, angle + spread).any { direction ->
                        val headEnd = MetricPoint(to.x - cos(direction) * head, to.y - sin(direction) * head)
                        distanceToSegment(position, to, headEnd) <= strokeRadius
                    }
                }
            }

            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE ->
                closedShapeContains(position, hitRadius, pageWidth, pageHeight)
        }
    }
}

fun PageAnnotation.translated(dx: Float, dy: Float): PageAnnotation {
    val bounds = normalizedBounds()
    val safeDx = dx.coerceIn(-bounds.left, 1f - bounds.right)
    val safeDy = dy.coerceIn(-bounds.top, 1f - bounds.bottom)
    if (safeDx == 0f && safeDy == 0f) return this
    return when (this) {
        is InkAnnotation -> copy(points = points.map { it.translated(safeDx, safeDy) })
        is MarkupAnnotation -> copy(bounds = this.bounds.map { it.translated(safeDx, safeDy) })
        is TextBoxAnnotation -> copy(bounds = this.bounds.translated(safeDx, safeDy))
        is SymbolAnnotation -> copy(center = center.translated(safeDx, safeDy))
        is ShapeAnnotation -> copy(
            start = start.translated(safeDx, safeDy),
            end = end.translated(safeDx, safeDy),
        )
    }
}

fun List<PageAnnotation>.lassoSelection(
    bounds: NormalizedRect,
    pageWidth: Float = 1f,
    pageHeight: Float = 1f,
): Set<String> {
    requirePageSize(pageWidth, pageHeight)
    return filter {
        if (it is InkAnnotation) {
            it.intersectsLasso(bounds, pageWidth, pageHeight)
        } else {
            it.normalizedBounds().intersects(bounds)
        }
    }.mapTo(mutableSetOf(), PageAnnotation::id)
}

fun List<PageAnnotation>.translateSelection(
    ids: Set<String>,
    dx: Float,
    dy: Float,
): List<PageAnnotation> {
    val selected = filter { it.id in ids }
    if (selected.isEmpty()) return this
    val bounds = selected.map(PageAnnotation::normalizedBounds).unionBounds()
    val safeDx = dx.coerceIn(-bounds.left, 1f - bounds.right)
    val safeDy = dy.coerceIn(-bounds.top, 1f - bounds.bottom)
    return map { annotation ->
        if (annotation.id in ids) annotation.translated(safeDx, safeDy) else annotation
    }
}

fun List<PageAnnotation>.duplicateSelection(
    ids: Set<String>,
    idFactory: () -> String,
): List<PageAnnotation> {
    val selected = filter { it.id in ids }
    if (selected.isEmpty()) return this
    val bounds = selected.map(PageAnnotation::normalizedBounds).unionBounds()
    val offset = if (bounds.right <= 1f - DUPLICATE_OFFSET && bounds.bottom <= 1f - DUPLICATE_OFFSET) {
        DUPLICATE_OFFSET
    } else {
        0f
    }
    return this + selected.map { it.withId(idFactory()).translated(offset, offset) }
}

fun PageAnnotation.resized(start: NormalizedPoint, end: NormalizedPoint): PageAnnotation {
    if (this is InkAnnotation) return this
    val target = minimumRect(start, end)
    return when (this) {
        is InkAnnotation -> this
        is MarkupAnnotation -> {
            val source = normalizedBounds()
            copy(bounds = bounds.map { it.scaled(source, target) })
        }
        is TextBoxAnnotation -> copy(bounds = target)
        is SymbolAnnotation -> copy(
            center = NormalizedPoint(
                (target.left + target.right) / 2f,
                (target.top + target.bottom) / 2f,
            ),
            size = max(target.width(), target.height()).coerceIn(MIN_ANNOTATION_SIZE, 0.5f),
        )
        is ShapeAnnotation -> when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> {
                val line = minimumLine(start, end)
                copy(start = line.first, end = line.second)
            }
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> copy(
                start = NormalizedPoint(target.left, target.top),
                end = NormalizedPoint(target.right, target.bottom),
            )
        }
    }
}

fun PageAnnotation.resizeHandles(
    pageWidth: Float = 1f,
    pageHeight: Float = 1f,
): Map<AnnotationHandle, NormalizedPoint> = when (this) {
    is InkAnnotation -> emptyMap()
    is ShapeAnnotation -> when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW -> mapOf(
            AnnotationHandle.START to start,
            AnnotationHandle.END to end,
        )
        ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> normalizedBounds().cornerHandles()
    }
    is MarkupAnnotation, is TextBoxAnnotation ->
        normalizedBounds().cornerHandles()
    is SymbolAnnotation -> normalizedBounds().cornerHandles() +
        (AnnotationHandle.ROTATION to rotationHandle(pageWidth, pageHeight))
}

fun PageAnnotation.resized(
    handle: AnnotationHandle,
    point: NormalizedPoint,
    pageWidth: Float = 1f,
    pageHeight: Float = 1f,
): PageAnnotation {
    if (this is InkAnnotation) return this
    if (this is SymbolAnnotation && handle == AnnotationHandle.ROTATION) {
        requirePageSize(pageWidth, pageHeight)
        val dx = (point.x.toDouble() - center.x) * pageWidth
        val dy = (point.y.toDouble() - center.y) * pageHeight
        return if (dx == 0.0 && dy == 0.0) {
            this
        } else {
            rotated(Math.toDegrees(atan2(dy, dx)).toFloat() + 90f)
        }
    }
    if (this is ShapeAnnotation && kind in setOf(ShapeKind.LINE, ShapeKind.ARROW)) {
        return when (handle) {
            AnnotationHandle.START -> {
                val line = minimumLine(point, end)
                copy(start = line.first, end = line.second)
            }
            AnnotationHandle.END -> {
                val line = minimumLine(start, point)
                copy(start = line.first, end = line.second)
            }
            else -> this
        }
    }
    val bounds = normalizedBounds()
    val topLeft = NormalizedPoint(bounds.left, bounds.top)
    val topRight = NormalizedPoint(bounds.right, bounds.top)
    val bottomRight = NormalizedPoint(bounds.right, bounds.bottom)
    val bottomLeft = NormalizedPoint(bounds.left, bounds.bottom)
    return when (handle) {
        AnnotationHandle.TOP_LEFT -> resized(point, bottomRight)
        AnnotationHandle.TOP_RIGHT -> resized(bottomLeft, point)
        AnnotationHandle.BOTTOM_RIGHT -> resized(topLeft, point)
        AnnotationHandle.BOTTOM_LEFT -> resized(topRight, point)
        AnnotationHandle.START, AnnotationHandle.END, AnnotationHandle.ROTATION -> this
    }
}

fun manualMarkup(start: NormalizedPoint, end: NormalizedPoint): List<NormalizedRect> =
    listOf(minimumRect(start, end))

fun strokePoints(
    points: List<NormalizedPoint>,
    straightLine: Boolean,
): List<NormalizedPoint> = if (straightLine && points.size > 1) {
    listOf(points.first(), points.last())
} else {
    points
}

private fun NormalizedRect.cornerHandles(): Map<AnnotationHandle, NormalizedPoint> = mapOf(
    AnnotationHandle.TOP_LEFT to NormalizedPoint(left, top),
    AnnotationHandle.TOP_RIGHT to NormalizedPoint(right, top),
    AnnotationHandle.BOTTOM_RIGHT to NormalizedPoint(right, bottom),
    AnnotationHandle.BOTTOM_LEFT to NormalizedPoint(left, bottom),
)

private fun SymbolAnnotation.rotationHandle(pageWidth: Float, pageHeight: Float): NormalizedPoint {
    requirePageSize(pageWidth, pageHeight)
    val angle = Math.toRadians((rotationDegrees - 90f).toDouble())
    val radius = size.toDouble() * min(pageWidth, pageHeight) / 2
    // Exact cardinal directions avoid collapsing a handle tangent to the page edge.
    val dx = if (rotationDegrees % 180f == 0f) 0.0 else cos(angle) * radius / pageWidth
    val dy = if ((rotationDegrees - 90f) % 180f == 0f) 0.0 else sin(angle) * radius / pageHeight
    var scale = 1.0
    if (dx != 0.0) scale = min(scale, (if (dx > 0.0) 1.0 - center.x else -center.x.toDouble()) / dx)
    if (dy != 0.0) scale = min(scale, (if (dy > 0.0) 1.0 - center.y else -center.y.toDouble()) / dy)
    return NormalizedPoint(
        x = (center.x + dx * scale).toFloat().coerceIn(0f, 1f),
        y = (center.y + dy * scale).toFloat().coerceIn(0f, 1f),
    )
}

fun PageAnnotation.normalizedBounds(): NormalizedRect = when (this) {
    is InkAnnotation -> points.bounds()
    is MarkupAnnotation -> NormalizedRect(
        left = bounds.minOf(NormalizedRect::left),
        top = bounds.minOf(NormalizedRect::top),
        right = bounds.maxOf(NormalizedRect::right),
        bottom = bounds.maxOf(NormalizedRect::bottom),
    )
    is TextBoxAnnotation -> bounds
    is SymbolAnnotation -> minimumRect(
        NormalizedPoint(
            (center.x - size / 2f).coerceIn(0f, 1f),
            (center.y - size / 2f).coerceIn(0f, 1f),
        ),
        NormalizedPoint(
            (center.x + size / 2f).coerceIn(0f, 1f),
            (center.y + size / 2f).coerceIn(0f, 1f),
        ),
    )
    is ShapeAnnotation -> minimumRect(
        NormalizedPoint(min(start.x, end.x), min(start.y, end.y)),
        NormalizedPoint(max(start.x, end.x), max(start.y, end.y)),
    )
}

private fun List<NormalizedPoint>.bounds(): NormalizedRect = minimumRect(
    NormalizedPoint(minOf(NormalizedPoint::x), minOf(NormalizedPoint::y)),
    NormalizedPoint(maxOf(NormalizedPoint::x), maxOf(NormalizedPoint::y)),
)

private fun NormalizedRect.contains(point: NormalizedPoint, radius: Float): Boolean =
    point.x in (left - radius).coerceAtLeast(0f)..(right + radius).coerceAtMost(1f) &&
        point.y in (top - radius).coerceAtLeast(0f)..(bottom + radius).coerceAtMost(1f)

private fun NormalizedRect.intersects(other: NormalizedRect): Boolean =
    left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

private fun List<NormalizedRect>.unionBounds(): NormalizedRect = NormalizedRect(
    left = minOf(NormalizedRect::left),
    top = minOf(NormalizedRect::top),
    right = maxOf(NormalizedRect::right),
    bottom = maxOf(NormalizedRect::bottom),
)

private fun PageAnnotation.withId(id: String): PageAnnotation = when (this) {
    is InkAnnotation -> copy(id = id)
    is MarkupAnnotation -> copy(id = id)
    is TextBoxAnnotation -> copy(id = id)
    is SymbolAnnotation -> copy(id = id)
    is ShapeAnnotation -> copy(id = id)
}

private fun ShapeAnnotation.closedShapeContains(
    point: MetricPoint,
    radius: Double,
    pageWidth: Float,
    pageHeight: Float,
): Boolean {
    val from = start.onPage(pageWidth, pageHeight)
    val to = end.onPage(pageWidth, pageHeight)
    val radiusX = abs(to.x - from.x) / 2
    val radiusY = abs(to.y - from.y) / 2
    val x = abs(point.x - (from.x + to.x) / 2)
    val y = abs(point.y - (from.y + to.y) / 2)
    val tolerance = radius + width.toDouble() * min(pageWidth, pageHeight) / 2
    if (hypot((x - radiusX).coerceAtLeast(0.0), (y - radiusY).coerceAtLeast(0.0)) > tolerance) {
        return false
    }
    if (kind == ShapeKind.RECTANGLE) return true
    if (radiusX == 0.0 || radiusY == 0.0) return false
    if ((x / radiusX).pow(2) + (y / radiusY).pow(2) <= 1.0) return true

    val squaredX = radiusX * radiusX
    val squaredY = radiusY * radiusY
    var lower = 0.0
    var upper = hypot(radiusX * x, radiusY * y)
    // Solve the nearest ellipse point; expanding its axes does not preserve stroke distance.
    repeat(48) {
        val value = (lower + upper) / 2
        val ellipseX = radiusX * x / (value + squaredX)
        val ellipseY = radiusY * y / (value + squaredY)
        if (ellipseX * ellipseX + ellipseY * ellipseY > 1.0) lower = value else upper = value
    }
    val value = (lower + upper) / 2
    return hypot(
        x - squaredX * x / (value + squaredX),
        y - squaredY * y / (value + squaredY),
    ) <= tolerance
}

private fun NormalizedPoint.translated(dx: Float, dy: Float): NormalizedPoint = NormalizedPoint(
    x = (x + dx).coerceIn(0f, 1f),
    y = (y + dy).coerceIn(0f, 1f),
)

private fun NormalizedRect.translated(dx: Float, dy: Float): NormalizedRect = NormalizedRect(
    left = (left + dx).coerceIn(0f, 1f),
    top = (top + dy).coerceIn(0f, 1f),
    right = (right + dx).coerceIn(0f, 1f),
    bottom = (bottom + dy).coerceIn(0f, 1f),
)

private fun NormalizedRect.scaled(source: NormalizedRect, target: NormalizedRect): NormalizedRect {
    val scaleX = target.width().toDouble() / source.width()
    val scaleY = target.height().toDouble() / source.height()
    return NormalizedRect(
        left = (target.left + (left - source.left) * scaleX).toFloat().coerceIn(target.left, target.right),
        top = (target.top + (top - source.top) * scaleY).toFloat().coerceIn(target.top, target.bottom),
        right = (target.left + (right - source.left) * scaleX).toFloat().coerceIn(target.left, target.right),
        bottom = (target.top + (bottom - source.top) * scaleY).toFloat().coerceIn(target.top, target.bottom),
    )
}

private fun NormalizedRect.width(): Float = right - left

private fun NormalizedRect.height(): Float = bottom - top

private fun minimumRect(start: NormalizedPoint, end: NormalizedPoint): NormalizedRect {
    val rawLeft = min(start.x, end.x)
    val rawTop = min(start.y, end.y)
    val rawRight = max(start.x, end.x)
    val rawBottom = max(start.y, end.y)
    val left = if (rawRight - rawLeft < MIN_ANNOTATION_SIZE) {
        ((rawLeft + rawRight) / 2f - MIN_ANNOTATION_SIZE / 2f)
            .coerceIn(0f, 1f - MIN_ANNOTATION_SIZE)
    } else {
        rawLeft
    }
    val top = if (rawBottom - rawTop < MIN_ANNOTATION_SIZE) {
        ((rawTop + rawBottom) / 2f - MIN_ANNOTATION_SIZE / 2f)
            .coerceIn(0f, 1f - MIN_ANNOTATION_SIZE)
    } else {
        rawTop
    }
    return NormalizedRect(
        left = left,
        top = top,
        right = if (rawRight - rawLeft < MIN_ANNOTATION_SIZE) left + MIN_ANNOTATION_SIZE else rawRight,
        bottom = if (rawBottom - rawTop < MIN_ANNOTATION_SIZE) top + MIN_ANNOTATION_SIZE else rawBottom,
    )
}

private fun minimumLine(
    start: NormalizedPoint,
    end: NormalizedPoint,
): Pair<NormalizedPoint, NormalizedPoint> {
    if (distance(start, end) >= MIN_ANNOTATION_SIZE) return start to end
    val endX = if (start.x <= 1f - MIN_ANNOTATION_SIZE) {
        start.x + MIN_ANNOTATION_SIZE
    } else {
        start.x - MIN_ANNOTATION_SIZE
    }
    return start to NormalizedPoint(endX, start.y)
}

private fun distance(first: NormalizedPoint, second: NormalizedPoint): Float = hypot(
    first.x - second.x,
    first.y - second.y,
)

private fun distanceToSegment(
    point: MetricPoint,
    start: MetricPoint,
    end: MetricPoint,
): Double {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0.0) return hypot(point.x - start.x, point.y - start.y)
    val projection = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
        .coerceIn(0.0, 1.0)
    return hypot(
        point.x - (start.x + projection * dx),
        point.y - (start.y + projection * dy),
    )
}

private fun requirePageSize(width: Float, height: Float) {
    require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
        "Page size must be finite and positive"
    }
}

private data class MetricPoint(val x: Double, val y: Double) {
    operator fun plus(other: MetricPoint) = MetricPoint(x + other.x, y + other.y)
    operator fun minus(other: MetricPoint) = MetricPoint(x - other.x, y - other.y)
    operator fun times(scale: Double) = MetricPoint(x * scale, y * scale)
}

private fun NormalizedPoint.onPage(width: Float, height: Float) =
    MetricPoint(x.toDouble() * width, y.toDouble() * height)

private fun InkAnnotation.highlighterPolygons(
    pageWidth: Float,
    pageHeight: Float,
): Sequence<List<MetricPoint>> = sequence {
    val halfWidth = width.toDouble() * min(pageWidth, pageHeight) / 2
    val path = points.map { it.onPage(pageWidth, pageHeight) }
    val segments = path.zipWithNext().filter { (start, end) -> start != end }
    if (segments.isEmpty()) {
        val center = path.first()
        yield(
            listOf(
                center + MetricPoint(-halfWidth, -halfWidth),
                center + MetricPoint(halfWidth, -halfWidth),
                center + MetricPoint(halfWidth, halfWidth),
                center + MetricPoint(-halfWidth, halfWidth),
            ),
        )
        return@sequence
    }
    var previousNormal: MetricPoint? = null
    segments.forEachIndexed { index, (start, end) ->
        val delta = end - start
        val cap = delta * (halfWidth / hypot(delta.x, delta.y))
        val normal = MetricPoint(-cap.y, cap.x)
        val from = if (index == 0) start - cap else start
        val to = if (index == segments.lastIndex) end + cap else end
        yield(listOf(from + normal, to + normal, to - normal, from - normal))
        val previous = previousNormal
        if (previous != null && previous.x * normal.y != previous.y * normal.x) {
            yield(listOf(start, start + previous, start + normal))
            yield(listOf(start, start - previous, start - normal))
        }
        previousNormal = normal
    }
}

private fun List<MetricPoint>.hit(point: MetricPoint, radius: Double): Boolean {
    var positive = false
    var negative = false
    for (index in indices) {
        val start = this[index]
        val end = this[(index + 1) % size]
        if (distanceToSegment(point, start, end) <= radius) return true
        val cross = (end.x - start.x) * (point.y - start.y) -
            (end.y - start.y) * (point.x - start.x)
        positive = positive || cross > 0
        negative = negative || cross < 0
    }
    return !(positive && negative)
}

private fun InkAnnotation.intersectsLasso(
    lasso: NormalizedRect,
    pageWidth: Float,
    pageHeight: Float,
): Boolean {
    val bounds = normalizedBounds()
    var left = bounds.left.toDouble() * pageWidth
    var right = bounds.right.toDouble() * pageWidth
    var top = bounds.top.toDouble() * pageHeight
    var bottom = bounds.bottom.toDouble() * pageHeight
    val padding = if (kind == InkKind.PEN) width.toDouble() * min(pageWidth, pageHeight) / 2 else 0.0
    val outline = if (kind == InkKind.HIGHLIGHTER) {
        highlighterPolygons(pageWidth, pageHeight).flatten()
    } else {
        points.asSequence().map { it.onPage(pageWidth, pageHeight) }
    }
    for (point in outline) {
        left = min(left, point.x - padding)
        right = max(right, point.x + padding)
        top = min(top, point.y - padding)
        bottom = max(bottom, point.y + padding)
    }
    return left <= lasso.right.toDouble() * pageWidth && right >= lasso.left.toDouble() * pageWidth &&
        top <= lasso.bottom.toDouble() * pageHeight && bottom >= lasso.top.toDouble() * pageHeight
}
