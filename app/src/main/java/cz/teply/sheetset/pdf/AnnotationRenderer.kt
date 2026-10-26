package cz.teply.sheetset.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import cz.teply.sheetset.settings.AnnotationTextSize
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object AnnotationRenderer {
    private const val TEXT_LAYOUT_PAGE_HEIGHT = 1_000f

    private val symbolGlyphs = mapOf(
        "sharp" to "♯",
        "flat" to "♭",
        "natural" to "♮",
        "fermata" to "𝄐",
        "accent" to "𝆓",
        "breath" to "𝄒",
        "crescendo" to "<",
        "decrescendo" to ">",
        "p" to "p",
        "mf" to "mf",
        "f" to "f",
        "ff" to "ff",
    )

    fun symbolGlyph(symbolId: String): String = requireNotNull(symbolGlyphs[symbolId]) {
        "Unsupported symbol ID"
    }

    fun draw(
        canvas: Canvas,
        annotation: PageAnnotation,
        page: RectF,
        symbolTypeface: Typeface,
        selected: Boolean = false,
    ) {
        when (annotation) {
            is InkAnnotation -> drawInk(canvas, annotation, page)
            is MarkupAnnotation -> drawMarkup(canvas, annotation, page)
            is TextBoxAnnotation -> drawTextBox(canvas, annotation, page)
            is ShapeAnnotation -> drawShape(canvas, annotation, page)
            is SymbolAnnotation -> drawSymbol(canvas, annotation, page, symbolTypeface)
        }
        if (selected) drawSelection(canvas, annotation, page)
    }

    private fun drawInk(
        canvas: Canvas,
        annotation: InkAnnotation,
        page: RectF,
    ) {
        val paint = strokePaint(
            annotation.color.argb,
            annotation.width * min(page.width(), page.height()),
            annotation.opacity,
        ).apply {
            if (annotation.kind == InkKind.HIGHLIGHTER) {
                strokeCap = Paint.Cap.SQUARE
                strokeJoin = Paint.Join.BEVEL
            }
        }
        val first = annotation.points.first()
        if (annotation.points.size == 1) {
            canvas.drawPoint(first.x(page), first.y(page), paint)
            return
        }
        val path = Path().apply {
            moveTo(first.x(page), first.y(page))
            annotation.points.drop(1).forEach { point -> lineTo(point.x(page), point.y(page)) }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawMarkup(
        canvas: Canvas,
        annotation: MarkupAnnotation,
        page: RectF,
    ) {
        val color = annotation.color.argb
        annotation.bounds.forEach { normalized ->
            val bounds = normalized.toRectF(page)
            when (annotation.kind) {
                MarkupKind.HIGHLIGHT -> canvas.drawRect(
                    bounds,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        this.color = color
                        alpha = annotation.opacity
                    },
                )
                MarkupKind.UNDERLINE -> canvas.drawLine(
                    bounds.left,
                    bounds.bottom,
                    bounds.right,
                    bounds.bottom,
                    strokePaint(color, page.height() * 0.003f, annotation.opacity),
                )
                MarkupKind.STRIKE_THROUGH -> canvas.drawLine(
                    bounds.left,
                    bounds.centerY(),
                    bounds.right,
                    bounds.centerY(),
                    strokePaint(color, page.height() * 0.003f, annotation.opacity),
                )
            }
        }
    }

    private fun drawTextBox(canvas: Canvas, annotation: TextBoxAnnotation, page: RectF) {
        val bounds = annotation.bounds.toRectF(page)
        val padding = min(page.width(), page.height()) * 0.008f
        canvas.drawRect(
            bounds,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
                alpha = min(235, annotation.opacity)
            },
        )
        canvas.drawRect(
            bounds,
            strokePaint(annotation.color.argb, page.height() * 0.002f, annotation.opacity),
        )
        // StaticLayout uses integer metrics; keep wrapping independent of preview and PDF scale.
        val textScale = page.height() / TEXT_LAYOUT_PAGE_HEIGHT
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = annotation.color.argb
            alpha = annotation.opacity
            textSize = when (annotation.size) {
                AnnotationTextSize.SMALL -> TEXT_LAYOUT_PAGE_HEIGHT * 0.028f
                AnnotationTextSize.MEDIUM -> TEXT_LAYOUT_PAGE_HEIGHT * 0.036f
                AnnotationTextSize.LARGE -> TEXT_LAYOUT_PAGE_HEIGHT * 0.048f
            }
        }
        val layout = StaticLayout.Builder.obtain(
            annotation.text,
            0,
            annotation.text.length,
            textPaint,
            ((bounds.width() - padding * 2f) / textScale).toInt().coerceAtLeast(1),
        )
            .setAlignment(
                when (annotation.alignment) {
                    AnnotationTextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
                    AnnotationTextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                    AnnotationTextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
                },
            )
            .setLineSpacing(0f, annotation.lineHeight)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(bounds.left + padding, bounds.top + padding)
        canvas.scale(textScale, textScale)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawShape(canvas: Canvas, annotation: ShapeAnnotation, page: RectF) {
        val startX = annotation.start.x(page)
        val startY = annotation.start.y(page)
        val endX = annotation.end.x(page)
        val endY = annotation.end.y(page)
        val paint = strokePaint(
            annotation.color.argb,
            annotation.width * min(page.width(), page.height()),
            annotation.opacity,
        )
        when (annotation.kind) {
            ShapeKind.LINE -> canvas.drawLine(startX, startY, endX, endY, paint)
            ShapeKind.ARROW -> {
                val angle = atan2(endY - startY, endX - startX)
                val length = hypot(endX - startX, endY - startY)
                val head = min(length * 0.25f, min(page.width(), page.height()) * 0.04f)
                val spread = 0.55f
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                    moveTo(endX, endY)
                    lineTo(endX - cos(angle - spread) * head, endY - sin(angle - spread) * head)
                    moveTo(endX, endY)
                    lineTo(endX - cos(angle + spread) * head, endY - sin(angle + spread) * head)
                }
                canvas.drawPath(path, paint)
            }
            ShapeKind.RECTANGLE -> canvas.drawRect(shapeBounds(annotation, page), paint)
            ShapeKind.ELLIPSE -> canvas.drawOval(shapeBounds(annotation, page), paint)
        }
    }

    private fun drawSymbol(
        canvas: Canvas,
        annotation: SymbolAnnotation,
        page: RectF,
        symbolTypeface: Typeface,
    ) {
        val centerX = annotation.center.x(page)
        val centerY = annotation.center.y(page)
        val glyph = symbolGlyph(annotation.symbolId)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = annotation.color.argb
            alpha = annotation.opacity
            textAlign = Paint.Align.CENTER
            textSize = annotation.size * min(page.width(), page.height())
            typeface = symbolTypeface
        }
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        canvas.save()
        canvas.rotate(annotation.rotationDegrees, centerX, centerY)
        canvas.drawText(glyph, centerX, baseline, paint)
        canvas.restore()
    }

    private fun drawSelection(canvas: Canvas, annotation: PageAnnotation, page: RectF) {
        val bounds = selectionBounds(annotation, page)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.GRAY
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        canvas.drawRect(bounds, paint)
        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.DKGRAY
            strokeWidth = 2f
        }
        val radius = max(6f, min(page.width(), page.height()) * 0.007f)
        selectionHandles(annotation, bounds, page).forEach { (x, y) ->
            canvas.drawCircle(x, y, radius, handlePaint)
            canvas.drawCircle(x, y, radius, handleStroke)
        }
    }

    private fun selectionBounds(annotation: PageAnnotation, page: RectF): RectF {
        val result = when (annotation) {
            is InkAnnotation -> RectF(
                annotation.points.minOf { it.x }.normalizedX(page),
                annotation.points.minOf { it.y }.normalizedY(page),
                annotation.points.maxOf { it.x }.normalizedX(page),
                annotation.points.maxOf { it.y }.normalizedY(page),
            )
            is MarkupAnnotation -> RectF(
                annotation.bounds.minOf { it.left }.normalizedX(page),
                annotation.bounds.minOf { it.top }.normalizedY(page),
                annotation.bounds.maxOf { it.right }.normalizedX(page),
                annotation.bounds.maxOf { it.bottom }.normalizedY(page),
            )
            is TextBoxAnnotation -> annotation.bounds.toRectF(page)
            is ShapeAnnotation -> shapeBounds(annotation, page)
            is SymbolAnnotation -> annotation.normalizedBounds().toRectF(page)
        }
        if (result.width() < 2f) result.inset(-6f, 0f)
        if (result.height() < 2f) result.inset(0f, -6f)
        return result
    }

    private fun selectionHandles(
        annotation: PageAnnotation,
        bounds: RectF,
        page: RectF,
    ): List<Pair<Float, Float>> = when (annotation) {
        is InkAnnotation -> emptyList()
        is ShapeAnnotation -> when (annotation.kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> listOf(
                annotation.start.x(page) to annotation.start.y(page),
                annotation.end.x(page) to annotation.end.y(page),
            )
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> bounds.corners()
        }
        is MarkupAnnotation, is TextBoxAnnotation, is SymbolAnnotation -> bounds.corners()
    }

    private fun RectF.corners(): List<Pair<Float, Float>> = listOf(
        left to top,
        right to top,
        right to bottom,
        left to bottom,
    )

    private fun shapeBounds(annotation: ShapeAnnotation, page: RectF): RectF = RectF(
        min(annotation.start.x, annotation.end.x).normalizedX(page),
        min(annotation.start.y, annotation.end.y).normalizedY(page),
        max(annotation.start.x, annotation.end.x).normalizedX(page),
        max(annotation.start.y, annotation.end.y).normalizedY(page),
    )

    private fun strokePaint(color: Int, width: Float, alpha: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            this.alpha = alpha
            strokeWidth = width
        }

    private fun NormalizedPoint.x(page: RectF): Float = x.normalizedX(page)

    private fun NormalizedPoint.y(page: RectF): Float = y.normalizedY(page)

    private fun Float.normalizedX(page: RectF): Float = page.left + this * page.width()

    private fun Float.normalizedY(page: RectF): Float = page.top + this * page.height()

    private fun NormalizedRect.toRectF(page: RectF): RectF = RectF(
        left.normalizedX(page),
        top.normalizedY(page),
        right.normalizedX(page),
        bottom.normalizedY(page),
    )
}
