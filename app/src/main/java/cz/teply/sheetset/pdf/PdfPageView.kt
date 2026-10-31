package cz.teply.sheetset.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import cz.teply.sheetset.R
import cz.teply.sheetset.settings.AnnotationTextSize
import cz.teply.sheetset.settings.PageFit
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ReaderTool {
    VIEW,
    SELECT,
    LASSO,
    PEN,
    HIGHLIGHTER,
    UNDERLINE,
    STRIKE_THROUGH,
    TEXT_BOX,
    SYMBOL,
    LINE,
    ARROW,
    RECTANGLE,
    ELLIPSE,
    ERASER,
}

class PdfPageView(context: Context) : View(context) {
    private var executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val symbolTypeface = requireNotNull(
        ResourcesCompat.getFont(context, R.font.noto_music_regular),
    )
    private var viewport = PdfViewport(zoom = 1f, panX = 0f, panY = 0f)
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (activeStylusPointerIds.isNotEmpty() && !multiTouchActive) return false
                viewport = viewport.scaledAndMoved(
                    factor = detector.scaleFactor,
                    previousFocusX = lastCentroidX,
                    previousFocusY = lastCentroidY,
                    focusX = detector.focusX - width / 2f,
                    focusY = detector.focusY - height / 2f,
                )
                scaleAppliedForEvent = true
                return true
            }
        },
    )

    private var source: File? = null
    private var pageIndex = -1
    private var bitmap: Bitmap? = null
    @Volatile
    private var generation = 0
    private var halfPagePart = 0
    private var halfPageMode = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var activeViewPointerId: Int? = null
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val previewPoints = mutableListOf<NormalizedPoint>()
    private var dragStart: NormalizedPoint? = null
    private var dragCurrent: NormalizedPoint? = null
    private var gestureOriginal: PageAnnotation? = null
    private var gesturePreview: Map<String, PageAnnotation> = emptyMap()
    private var resizeHandle: AnnotationHandle? = null
    private val erasedIds = mutableSetOf<String>()
    private val activeStylusPointerIds = mutableSetOf<Int>()
    private var activeEditorPointerId: Int? = null
    private var multiTouchActive = false
    private var scaleAppliedForEvent = false
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    private var drawingOpacity = DEFAULT_ANNOTATION_OPACITY

    init {
        isClickable = true
    }

    var tool = ReaderTool.VIEW
        set(value) {
            if (field == value) return
            field = value
            activeViewPointerId = null
            activeEditorPointerId = null
            eyedropperActive = false
            resetGesture()
        }
    var editorSettings = AnnotationEditorSettings.defaults()
    var activeDrawingPreset = editorSettings.presets.first()
        set(value) {
            field = value
            annotationColor = value.color
            drawingOpacity = value.opacity
            when (value.kind) {
                DrawingPresetKind.PEN, DrawingPresetKind.MARKER -> {
                    penWidth = value.normalizedWidth()
                }
                DrawingPresetKind.HIGHLIGHTER -> {
                    highlighterWidth = value.normalizedWidth()
                    highlighterOpacity = value.opacity
                }
            }
        }
    var annotationColor = AnnotationColor.BLACK
    var annotationOpacity = DEFAULT_ANNOTATION_OPACITY
        set(value) {
            require(value in 0..255) { "Invalid annotation opacity" }
            field = value
        }
    var penWidth = 0.004f
    var highlighterWidth = 0.016f
    var shapeWidth = 0.004f
    var straightLine = false
    var textSize = AnnotationTextSize.MEDIUM
    var highlighterOpacity = LEGACY_HIGHLIGHTER_OPACITY
        set(value) {
            require(value in 0..255) { "Invalid highlighter opacity" }
            field = value
        }
    var pageFit = PageFit.PAGE
        set(value) {
            if (field == value) return
            field = value
            if (value == PageFit.WIDTH) applyHalfPagePart() else clampPan()
            invalidate()
        }
    var pageTurnTaps = true
    var pageTurnSwipes = true
    var annotations: List<PageAnnotation> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var selectedAnnotationIds: Set<String> = emptySet()
        set(value) {
            require(value.size <= MAX_ANNOTATIONS_PER_PAGE) { "Too many selected annotations" }
            field = value.toSet()
            invalidate()
        }
    val currentViewport: PdfViewport get() = viewport
    var eyedropperActive = false
        private set
    var onPreviousPage: () -> Unit = {}
    var onNextPage: () -> Unit = {}
    var onPageClick: () -> Unit = {}
    var onSelectionChange: (Set<String>) -> Unit = {}
    var onAddAnnotation: (PageAnnotation) -> Unit = {}
    var onUpdateAnnotations: (List<PageAnnotation>) -> Unit = {}
    var onDeleteAnnotations: (Set<String>) -> Unit = {}
    var onSampleColor: (AnnotationColor) -> Unit = {}
    var onRequestText: (NormalizedRect) -> Unit = {}
    var onRequestSymbol: (NormalizedPoint) -> Unit = {}
    var onRequestMarkup: (MarkupKind, NormalizedPoint, NormalizedPoint) -> Unit = { _, _, _ -> }
    var onRenderError: () -> Unit = {}

    fun showPage(file: File, index: Int) {
        if (source == file && pageIndex == index) return
        generation++
        cancelPointerGesture()
        bitmap?.recycle()
        bitmap = null
        source = file
        pageIndex = index
        renderPage()
    }

    fun setHalfPagePart(part: Int, halfPageMode: Boolean = false) {
        require(part in 0..1) { "Invalid half-page part" }
        if (halfPagePart == part && this.halfPageMode == halfPageMode) return
        if (halfPageMode && !this.halfPageMode) {
            viewport = PdfViewport(zoom = 1f, panX = 0f, panY = 0f)
        }
        this.halfPageMode = halfPageMode
        halfPagePart = part
        if (pageFit == PageFit.WIDTH) {
            applyHalfPagePart()
            invalidate()
        }
    }

    fun startEyedropper() {
        activeEditorPointerId = null
        resetGesture()
        eyedropperActive = true
    }

    fun cancelEyedropper() {
        eyedropperActive = false
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width == oldWidth && height == oldHeight) return
        if (bitmap == null) renderPage()
        else if (halfPageMode && pageFit == PageFit.WIDTH) applyHalfPagePart()
        else clampPan()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 18, 18))
        val page = bitmap ?: return
        val bounds = pageBounds(page)
        canvas.drawBitmap(page, null, bounds, pagePaint)
        annotations.forEach { stored ->
            if (stored.id in erasedIds) return@forEach
            val annotation = gesturePreview[stored.id] ?: stored
            AnnotationRenderer.draw(
                canvas = canvas,
                annotation = annotation,
                page = bounds,
                symbolTypeface = symbolTypeface,
                selected = annotation.id in selectedAnnotationIds,
            )
            if (
                annotation is SymbolAnnotation &&
                selectedAnnotationIds.size == 1 &&
                annotation.id in selectedAnnotationIds
            ) {
                drawRotationHandle(canvas, annotation, bounds)
            }
        }
        previewAnnotation()?.let { preview ->
            AnnotationRenderer.draw(
                canvas = canvas,
                annotation = preview,
                page = bounds,
                symbolTypeface = symbolTypeface,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        trackStylusDown(event)
        val fingerCount = event.fingerCount()
        scaleAppliedForEvent = false
        trackFingerScale(event, fingerCount)
        val ownerId = activeEditorPointerId
        val ownerIndex = ownerId?.let(event::findPointerIndex) ?: -1
        val ownerIsStylus = ownerId != null && ownerId in activeStylusPointerIds
        val handled = when {
            event.actionMasked == MotionEvent.ACTION_CANCEL -> {
                cancelPointerGesture()
                true
            }
            shouldStylusTakeOver(event, ownerId) -> {
                cancelEditorPointer()
                startEditorPointer(event, event.actionIndex)
                true
            }
            multiTouchActive || fingerCount >= 2 -> {
                handleMultiTouch(event)
                true
            }
            ownerIsStylus && ownerIndex >= 0 && fingerCount > 0 -> handleEditorTouch(event)
            editorSettings.palmRejection && ownerIsStylus && fingerCount > 0 -> true
            else -> {
                when {
                    eyedropperActive -> handleEyedropperTouch(event)
                    tool == ReaderTool.VIEW -> {
                        if (handleViewTouch(event)) performClick()
                        true
                    }
                    else -> handleEditorTouch(event)
                }
            }
        }
        trackStylusUp(event)
        return handled
    }

    override fun performClick(): Boolean {
        super.performClick()
        onPageClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
            renderPage()
        }
    }

    override fun onDetachedFromWindow() {
        generation++
        executor.shutdownNow()
        bitmap?.recycle()
        bitmap = null
        super.onDetachedFromWindow()
    }

    private fun handleMultiTouch(event: MotionEvent) {
        val focus = fingerFocus(event)
        if (focus == null) {
            multiTouchActive = false
            return
        }
        val centroidX = focus.first
        val centroidY = focus.second
        if (!multiTouchActive || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            activeViewPointerId = null
            activeEditorPointerId = null
            resetGesture()
            eyedropperActive = false
            multiTouchActive = true
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (!scaleAppliedForEvent) {
                viewport = viewport.scaledAndMoved(
                    factor = 1f,
                    previousFocusX = lastCentroidX,
                    previousFocusY = lastCentroidY,
                    focusX = centroidX,
                    focusY = centroidY,
                )
            }
            clampPan()
            invalidate()
        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
            multiTouchActive = false
        }
        lastCentroidX = centroidX
        lastCentroidY = centroidY
    }

    private fun trackFingerScale(event: MotionEvent, fingerCount: Int) {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || fingerCount == event.pointerCount) {
            scaleDetector.onTouchEvent(event)
            return
        }
        if (fingerCount == 0) return
        val indices = (0 until event.pointerCount).filter { index ->
            event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
        }
        val actionIndex = indices.indexOf(event.actionIndex)
        val pointerAction = event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP
        val action = when {
            !pointerAction -> event.actionMasked
            actionIndex < 0 -> MotionEvent.ACTION_MOVE
            fingerCount == 1 -> if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                MotionEvent.ACTION_DOWN
            } else {
                MotionEvent.ACTION_UP
            }
            else -> event.actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        val fingers = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            action,
            fingerCount,
            Array(fingerCount) { index ->
                MotionEvent.PointerProperties().also { event.getPointerProperties(indices[index], it) }
            },
            Array(fingerCount) { index ->
                MotionEvent.PointerCoords().also { event.getPointerCoords(indices[index], it) }
            },
            event.metaState,
            0,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags,
        )
        try {
            scaleDetector.onTouchEvent(fingers)
        } finally {
            fingers.recycle()
        }
    }

    private fun fingerFocus(event: MotionEvent): Pair<Float, Float>? {
        var count = 0
        var x = 0f
        var y = 0f
        repeat(event.pointerCount) { index ->
            if (
                !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex) &&
                event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
            ) {
                count++
                x += event.getX(index)
                y += event.getY(index)
            }
        }
        return if (count == 0) {
            null
        } else {
            x / count - width / 2f to y / count - height / 2f
        }
    }

    private fun MotionEvent.fingerCount(): Int = (0 until pointerCount).count { index ->
        getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
    }

    private fun cancelPointerGesture() {
        activeViewPointerId = null
        activeEditorPointerId = null
        multiTouchActive = false
        eyedropperActive = false
        resetGesture()
    }

    private fun drawRotationHandle(
        canvas: Canvas,
        annotation: SymbolAnnotation,
        page: RectF,
    ) {
        val handle = annotation.resizeHandles(page.width(), page.height()).getValue(AnnotationHandle.ROTATION)
        val x = page.left + handle.x * page.width()
        val y = page.top + handle.y * page.height()
        val radius = max(6f, min(page.width(), page.height()) * 0.007f)
        canvas.drawCircle(x, y, radius, handlePaint)
        canvas.drawCircle(x, y, radius, handleStroke)
    }

    private fun trackStylusDown(event: MotionEvent) {
        if (
            event.actionMasked != MotionEvent.ACTION_DOWN &&
            event.actionMasked != MotionEvent.ACTION_POINTER_DOWN
        ) {
            return
        }
        val index = event.actionIndex
        if (event.getToolType(index) in STYLUS_TOOL_TYPES) {
            activeStylusPointerIds += event.getPointerId(index)
        }
    }

    private fun trackStylusUp(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                activeStylusPointerIds -= event.getPointerId(event.actionIndex)
            }
            MotionEvent.ACTION_CANCEL -> activeStylusPointerIds.clear()
        }
    }

    private fun handleEyedropperTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> sampleColor(event.x, event.y)
            MotionEvent.ACTION_CANCEL -> cancelEyedropper()
        }
        return true
    }

    private fun sampleColor(x: Float, y: Float) {
        val page = bitmap ?: return
        val bounds = pageBounds(page)
        val point = normalizePoint(
            x,
            y,
            PageBounds(bounds.left, bounds.top, bounds.width(), bounds.height()),
        ) ?: return
        val pixel = page.getPixel(
            (point.x * (page.width - 1)).roundToInt(),
            (point.y * (page.height - 1)).roundToInt(),
        )
        eyedropperActive = false
        onSampleColor(
            AnnotationColor(Color.rgb(Color.red(pixel), Color.green(pixel), Color.blue(pixel))),
        )
    }

    private fun handleViewTouch(event: MotionEvent): Boolean {
        var clicked = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                activeViewPointerId = event.getPointerId(index)
                downX = event.getX(index)
                downY = event.getY(index)
                lastX = downX
                lastY = downY
            }
            MotionEvent.ACTION_MOVE -> {
                val index = activeViewPointerId?.let(event::findPointerIndex) ?: -1
                if (index < 0) {
                    activeViewPointerId = null
                } else if (canPan()) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    viewport = viewport.copy(
                        panX = viewport.panX + x - lastX,
                        panY = viewport.panY + y - lastY,
                    )
                    lastX = x
                    lastY = y
                    clampPan()
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activeViewPointerId) activeViewPointerId = null
            }
            MotionEvent.ACTION_UP -> {
                val ownerId = activeViewPointerId
                activeViewPointerId = null
                if (event.getPointerId(event.actionIndex) == ownerId) {
                    clicked = finishViewGesture(event.getX(event.actionIndex), event.getY(event.actionIndex))
                }
            }
        }
        return clicked
    }

    private fun finishViewGesture(x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        if (
            pageTurnSwipes &&
            viewport.zoom == 1f &&
            abs(dx) > 80f &&
            abs(dx) > abs(dy) &&
            !canPanHorizontally()
        ) {
            if (dx < 0f) onNextPage() else onPreviousPage()
            return false
        }
        if (hypot(dx, dy) > 24f) return false
        val now = System.currentTimeMillis()
        if (
            viewport.zoom > 1f &&
            now - lastTapAt < 300 &&
            hypot(x - lastTapX, y - lastTapY) < 60f
        ) {
            viewport = PdfViewport(zoom = 1f, panX = 0f, panY = 0f)
            lastTapAt = 0L
            invalidate()
            return false
        }
        lastTapAt = now
        lastTapX = x
        lastTapY = y
        if (!pageTurnTaps) return true
        return when {
            x < width / 3f -> {
                onPreviousPage()
                false
            }
            x > width * 2f / 3f -> {
                onNextPage()
                false
            }
            else -> true
        }
    }

    private fun handleEditorTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                if (isRejectedPalmPointer(event, index)) return true
                startEditorPointer(event, index)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = editorPointerIndex(event) ?: return true
                if (dragStart != null) normalizedPoint(event, index)?.let(::updateGesture)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) == activeEditorPointerId) {
                    finishEditorPointer(event, event.actionIndex)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelEditorPointer()
        }
        return true
    }

    private fun shouldStylusTakeOver(event: MotionEvent, ownerId: Int?): Boolean =
        editorSettings.palmRejection &&
            ownerId != null &&
            ownerId !in activeStylusPointerIds &&
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            event.getToolType(event.actionIndex) in STYLUS_TOOL_TYPES

    private fun startEditorPointer(event: MotionEvent, index: Int) {
        activeEditorPointerId = event.getPointerId(index)
        val point = normalizedPoint(event, index)
        if (point == null) {
            activeEditorPointerId = null
        } else {
            beginGesture(point)
        }
    }

    private fun editorPointerIndex(event: MotionEvent): Int? {
        val pointerId = activeEditorPointerId ?: return null
        val index = event.findPointerIndex(pointerId)
        if (index >= 0) return index
        cancelEditorPointer()
        return null
    }

    private fun finishEditorPointer(event: MotionEvent, index: Int) {
        val point = normalizedPoint(event, index) ?: dragCurrent
        if (dragStart == null || point == null) {
            resetGesture()
        } else {
            updateGesture(point)
            finishGesture()
        }
        activeEditorPointerId = null
    }

    private fun cancelEditorPointer() {
        activeEditorPointerId = null
        resetGesture()
    }

    private fun isRejectedPalmPointer(event: MotionEvent, index: Int): Boolean =
        editorSettings.palmRejection &&
            event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER &&
            activeStylusPointerIds.isNotEmpty()

    private fun normalizedPoint(event: MotionEvent, index: Int): NormalizedPoint? {
        val page = bitmap ?: return null
        val bounds = pageBounds(page)
        return normalizePoint(
            event.getX(index),
            event.getY(index),
            PageBounds(bounds.left, bounds.top, bounds.width(), bounds.height()),
        )
    }

    private fun beginGesture(point: NormalizedPoint) {
        resetGesture()
        dragStart = point
        dragCurrent = point
        when (tool) {
            ReaderTool.SELECT -> beginSelection(point)
            ReaderTool.PEN, ReaderTool.HIGHLIGHTER -> previewPoints.add(point)
            ReaderTool.ERASER -> eraseAt(point)
            else -> Unit
        }
        invalidate()
    }

    private fun updateGesture(point: NormalizedPoint) {
        dragCurrent = point
        when (tool) {
            ReaderTool.SELECT -> updateSelectionGesture(point)
            ReaderTool.PEN, ReaderTool.HIGHLIGHTER -> {
                val previous = previewPoints.lastOrNull()
                if (previous == null || distance(previous, point) > 0.001f) {
                    if (previewPoints.size < MAX_POINTS_PER_INK) previewPoints.add(point)
                }
            }
            ReaderTool.ERASER -> eraseAt(point)
            else -> Unit
        }
        invalidate()
    }

    private fun finishGesture() {
        val start = dragStart
        val end = dragCurrent
        when (tool) {
            ReaderTool.SELECT -> finishSelectionGesture()
            ReaderTool.LASSO -> if (start != null && end != null) {
                bitmap?.let { page ->
                    updateSelection(annotations.lassoSelection(
                        manualMarkup(start, end).single(),
                        page.width.toFloat(),
                        page.height.toFloat(),
                    ))
                }
            }
            ReaderTool.PEN -> addInk(InkKind.PEN, penWidth)
            ReaderTool.HIGHLIGHTER -> addInk(InkKind.HIGHLIGHTER, highlighterWidth)
            ReaderTool.UNDERLINE -> requestMarkup(MarkupKind.UNDERLINE, start, end)
            ReaderTool.STRIKE_THROUGH -> requestMarkup(MarkupKind.STRIKE_THROUGH, start, end)
            ReaderTool.TEXT_BOX -> if (start != null && end != null) {
                onRequestText(manualMarkup(start, end).single())
            }
            ReaderTool.SYMBOL -> end?.let(onRequestSymbol)
            ReaderTool.LINE -> addShape(ShapeKind.LINE, start, end)
            ReaderTool.ARROW -> addShape(ShapeKind.ARROW, start, end)
            ReaderTool.RECTANGLE -> addShape(ShapeKind.RECTANGLE, start, end)
            ReaderTool.ELLIPSE -> addShape(ShapeKind.ELLIPSE, start, end)
            ReaderTool.ERASER -> finishEraseGesture()
            ReaderTool.VIEW -> Unit
        }
        resetGesture()
    }

    private fun beginSelection(point: NormalizedPoint) {
        val page = bitmap ?: return
        val selected = selectedAnnotationIds.singleOrNull()?.let { id ->
            annotations.firstOrNull { it.id == id }
        }
        resizeHandle = selected?.handleAt(point, 0.04f / viewport.zoom)
        val hit = if (resizeHandle != null) {
            selected
        } else {
            annotations.topmostHit(
                point,
                0.025f / viewport.zoom,
                page.width.toFloat(),
                page.height.toFloat(),
            )
        }
        updateSelection(
            when {
                hit == null -> emptySet()
                hit.id in selectedAnnotationIds -> selectedAnnotationIds
                else -> setOf(hit.id)
            },
        )
        gestureOriginal = hit
    }

    private fun updateSelectionGesture(point: NormalizedPoint) {
        val original = gestureOriginal ?: return
        val start = dragStart ?: return
        val page = bitmap ?: return
        val handle = resizeHandle
        gesturePreview = if (handle == null) {
            annotations.translateSelection(
                selectedAnnotationIds,
                point.x - start.x,
                point.y - start.y,
            ).filter { it.id in selectedAnnotationIds }.associateBy(PageAnnotation::id)
        } else {
            mapOf(original.id to original.resized(handle, point, page.width.toFloat(), page.height.toFloat()))
        }
    }

    private fun finishSelectionGesture() {
        if (gesturePreview.isEmpty()) return
        val updated = annotations.map { gesturePreview[it.id] ?: it }
        if (updated != annotations) onUpdateAnnotations(updated)
    }

    private fun updateSelection(ids: Set<String>) {
        if (ids == selectedAnnotationIds) return
        selectedAnnotationIds = ids
        onSelectionChange(selectedAnnotationIds)
    }

    private fun requestMarkup(
        kind: MarkupKind,
        start: NormalizedPoint?,
        end: NormalizedPoint?,
    ) {
        if (start != null && end != null) onRequestMarkup(kind, start, end)
    }

    private fun addInk(kind: InkKind, width: Float) {
        if (previewPoints.isEmpty()) return
        add(
            InkAnnotation(
                id = UUID.randomUUID().toString(),
                kind = kind,
                width = width,
                points = strokePoints(previewPoints.toList(), straightLine),
                color = annotationColor,
                opacity = if (kind == InkKind.HIGHLIGHTER) {
                    highlighterOpacity
                } else {
                    drawingOpacity
                },
            ),
        )
    }

    private fun addShape(
        kind: ShapeKind,
        start: NormalizedPoint?,
        end: NormalizedPoint?,
    ) {
        if (start == null || end == null) return
        val raw = ShapeAnnotation(
            id = UUID.randomUUID().toString(),
            kind = kind,
            start = start,
            end = end,
            width = shapeWidth,
            color = annotationColor,
            opacity = annotationOpacity,
        )
        add(raw.resized(start, end))
    }

    private fun add(annotation: PageAnnotation) {
        onAddAnnotation(annotation)
    }

    private fun eraseAt(point: NormalizedPoint) {
        val page = bitmap ?: return
        val hit = annotations.asReversed().firstOrNull {
            it.id !in erasedIds && it.hitTest(
                point,
                0.025f / viewport.zoom,
                page.width.toFloat(),
                page.height.toFloat(),
            )
        } ?: return
        erasedIds += hit.id
    }

    private fun finishEraseGesture() {
        if (erasedIds.isEmpty()) return
        updateSelection(selectedAnnotationIds - erasedIds)
        onDeleteAnnotations(erasedIds.toSet())
    }

    private fun previewAnnotation(): PageAnnotation? {
        val start = dragStart ?: return null
        val end = dragCurrent ?: return null
        return when (tool) {
            ReaderTool.PEN -> previewInk(InkKind.PEN, penWidth)
            ReaderTool.HIGHLIGHTER -> previewInk(InkKind.HIGHLIGHTER, highlighterWidth)
            ReaderTool.UNDERLINE -> MarkupAnnotation(
                id = "preview",
                kind = MarkupKind.UNDERLINE,
                bounds = manualMarkup(start, end),
                color = annotationColor,
                opacity = annotationOpacity,
            )
            ReaderTool.STRIKE_THROUGH -> MarkupAnnotation(
                id = "preview",
                kind = MarkupKind.STRIKE_THROUGH,
                bounds = manualMarkup(start, end),
                color = annotationColor,
                opacity = annotationOpacity,
            )
            ReaderTool.TEXT_BOX -> ShapeAnnotation(
                id = "preview",
                kind = ShapeKind.RECTANGLE,
                start = start,
                end = end,
                width = shapeWidth,
                color = annotationColor,
                opacity = annotationOpacity,
            ).resized(start, end)
            ReaderTool.LASSO -> ShapeAnnotation(
                id = "preview",
                kind = ShapeKind.RECTANGLE,
                start = start,
                end = end,
                width = 0.003f,
                color = AnnotationColor.BLACK,
                opacity = DEFAULT_ANNOTATION_OPACITY,
            ).resized(start, end)
            ReaderTool.LINE, ReaderTool.ARROW, ReaderTool.RECTANGLE, ReaderTool.ELLIPSE -> {
                val kind = when (tool) {
                    ReaderTool.LINE -> ShapeKind.LINE
                    ReaderTool.ARROW -> ShapeKind.ARROW
                    ReaderTool.RECTANGLE -> ShapeKind.RECTANGLE
                    ReaderTool.ELLIPSE -> ShapeKind.ELLIPSE
                    else -> error("Unsupported shape preview")
                }
                ShapeAnnotation(
                    id = "preview",
                    kind = kind,
                    start = start,
                    end = end,
                    width = shapeWidth,
                    color = annotationColor,
                    opacity = annotationOpacity,
                ).resized(start, end)
            }
            else -> null
        }
    }

    private fun previewInk(kind: InkKind, width: Float): InkAnnotation? =
        if (previewPoints.isEmpty()) {
            null
        } else {
            InkAnnotation(
                id = "preview",
                kind = kind,
                width = width,
                points = strokePoints(previewPoints, straightLine),
                color = annotationColor,
                opacity = if (kind == InkKind.HIGHLIGHTER) {
                    highlighterOpacity
                } else {
                    drawingOpacity
                },
            )
        }

    private fun resetGesture() {
        previewPoints.clear()
        dragStart = null
        dragCurrent = null
        gestureOriginal = null
        gesturePreview = emptyMap()
        resizeHandle = null
        erasedIds.clear()
        invalidate()
    }

    private fun pageBounds(page: Bitmap): RectF {
        val fit = baseScale(page)
        val drawWidth = page.width * fit * viewport.zoom
        val drawHeight = page.height * fit * viewport.zoom
        return RectF(
            (width - drawWidth) / 2f + viewport.panX,
            (height - drawHeight) / 2f + viewport.panY,
            (width + drawWidth) / 2f + viewport.panX,
            (height + drawHeight) / 2f + viewport.panY,
        )
    }

    private fun baseScale(page: Bitmap): Float = when (pageFit) {
        PageFit.PAGE -> min(width.toFloat() / page.width, height.toFloat() / page.height)
        PageFit.WIDTH -> width.toFloat() / page.width
    }

    private fun canPan(): Boolean {
        val page = bitmap ?: return false
        return maxPanX(page) > 0f || maxPanY(page) > 0f
    }

    private fun canPanHorizontally(): Boolean {
        val page = bitmap ?: return false
        return maxPanX(page) > 0f
    }

    private fun clampPan() {
        val page = bitmap ?: return
        viewport = viewport.copy(
            panX = viewport.panX.coerceIn(-maxPanX(page), maxPanX(page)),
            panY = viewport.panY.coerceIn(-maxPanY(page), maxPanY(page)),
        )
    }

    private fun applyHalfPagePart() {
        val page = bitmap ?: return
        viewport = viewport.copy(
            panX = 0f,
            panY = halfPagePan(maxPanY(page), halfPagePart),
        )
    }

    private fun maxPanX(page: Bitmap): Float =
        max(0f, (page.width * baseScale(page) * viewport.zoom - width) / 2f)

    private fun maxPanY(page: Bitmap): Float =
        max(0f, (page.height * baseScale(page) * viewport.zoom - height) / 2f)

    private fun renderPage() {
        val file = source ?: return
        if (width < 1 || height < 1 || executor.isShutdown) return
        val requestedPage = pageIndex
        val request = ++generation
        executor.execute {
            if (request != generation) return@execute
            try {
                val rendered = render(file, requestedPage)
                val posted = mainHandler.post {
                    if (request == generation) {
                        bitmap?.recycle()
                        bitmap = rendered
                        viewport = PdfViewport(zoom = 1f, panX = 0f, panY = 0f)
                        viewport = viewport.copy(
                            panY = halfPagePan(maxPanY(rendered), halfPagePart),
                        )
                        invalidate()
                    } else {
                        rendered.recycle()
                    }
                }
                if (!posted) rendered.recycle()
            } catch (_: Exception) {
                mainHandler.post { if (request == generation) onRenderError() }
            }
        }
    }

    private fun render(file: File, index: Int): Bitmap = ParcelFileDescriptor
        .open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        .use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(index in 0 until renderer.pageCount)
                renderer.openPage(index).use { page ->
                    val scale = minOf(
                        2f,
                        3_072f / page.width,
                        3_072f / page.height,
                        sqrt(12_000_000f / (page.width.toFloat() * page.height)),
                    )
                    createBitmap(
                        (page.width * scale).roundToInt(),
                        (page.height * scale).roundToInt(),
                        Bitmap.Config.ARGB_8888,
                    ).also { result ->
                        result.eraseColor(Color.WHITE)
                        page.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    private fun distance(first: NormalizedPoint, second: NormalizedPoint): Float = hypot(
        first.x - second.x,
        first.y - second.y,
    )

    private fun PageAnnotation.handleAt(
        point: NormalizedPoint,
        radius: Float,
    ): AnnotationHandle? {
        val page = bitmap ?: return null
        return resizeHandles(page.width.toFloat(), page.height.toFloat()).minByOrNull { (_, handlePoint) ->
            distance(point, handlePoint)
        }?.takeIf { (_, handlePoint) -> distance(point, handlePoint) <= radius }?.key
    }

    private fun DrawingPreset.normalizedWidth(): Float = when (kind) {
        DrawingPresetKind.HIGHLIGHTER -> width.normalizedHighlighterWidth()
        DrawingPresetKind.PEN, DrawingPresetKind.MARKER -> width.normalizedAnnotationWidth()
    }

    private companion object {
        val STYLUS_TOOL_TYPES = setOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
    }
}
