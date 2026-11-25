package cz.teply.sheetset.pdf

import cz.teply.sheetset.settings.DEFAULT_PDF_ZOOM_PERCENT
import cz.teply.sheetset.settings.MAX_PDF_ZOOM_PERCENT

data class PdfViewport(val zoom: Float, val panX: Float, val panY: Float) {
    fun scaledAround(factor: Float, focusX: Float, focusY: Float): PdfViewport = scaledAndMoved(
        factor = factor,
        previousFocusX = focusX,
        previousFocusY = focusY,
        focusX = focusX,
        focusY = focusY,
    )

    fun scaledAndMoved(
        factor: Float,
        previousFocusX: Float,
        previousFocusY: Float,
        focusX: Float,
        focusY: Float,
    ): PdfViewport {
        val nextZoom = (zoom * factor).coerceIn(DEFAULT_PDF_ZOOM_PERCENT / 100f, MAX_PDF_ZOOM_PERCENT / 100f)
        val ratio = nextZoom / zoom
        return copy(
            zoom = nextZoom,
            panX = focusX - (previousFocusX - panX) * ratio,
            panY = focusY - (previousFocusY - panY) * ratio,
        )
    }
}

fun halfPagePan(maxPanY: Float, part: Int): Float {
    require(maxPanY.isFinite() && maxPanY >= 0f) { "Invalid page overflow" }
    require(part in 0..1) { "Invalid half-page part" }
    return if (part == 0) maxPanY else -maxPanY
}
