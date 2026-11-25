package cz.teply.sheetset.pdf

import cz.teply.sheetset.settings.PageFit
import kotlin.math.roundToInt

internal data class PdfPageRasterSize(val width: Int, val height: Int)

internal fun pdfPageRasterSize(
    pageWidth: Int,
    pageHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    pageFit: PageFit,
    zoom: Float,
): PdfPageRasterSize {
    require(pageWidth > 0 && pageHeight > 0 && viewWidth > 0 && viewHeight > 0)
    require(zoom.isFinite() && zoom >= 1f)
    val fit = when (pageFit) {
        PageFit.PAGE -> minOf(viewWidth.toFloat() / pageWidth, viewHeight.toFloat() / pageHeight)
        PageFit.WIDTH -> viewWidth.toFloat() / pageWidth
    }
    val scale = minOf(fit * zoom, 2f, 3_072f / pageWidth, 3_072f / pageHeight)
    return PdfPageRasterSize(
        width = (pageWidth * scale).roundToInt().coerceAtLeast(1),
        height = (pageHeight * scale).roundToInt().coerceAtLeast(1),
    )
}
