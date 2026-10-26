package cz.teply.sheetset.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PdfExportLimitException : IllegalArgumentException("PDF exceeds the safe export budget")

internal const val MAX_EXPORT_PAGES = 1_000
private const val MAX_EXPORT_PIXELS = 64_000_000L

internal class PdfExportBudget(availableHeapBytes: Long) {
    // Reserve half the available heap for native/parser overhead. Include one extra live bitmap.
    private val maxPixels = minOf(MAX_EXPORT_PIXELS, availableHeapBytes.coerceAtLeast(0L) / 8L)
    private var pixels = 0L
    private var largestPage = 0L

    fun include(width: Int, height: Int) {
        val (bitmapWidth, bitmapHeight) = renderSize(width, height)
        val pagePixels = bitmapWidth.toLong() * bitmapHeight
        pixels += pagePixels
        largestPage = maxOf(largestPage, pagePixels)
        if (pixels + largestPage > maxPixels) throw PdfExportLimitException()
    }
}

private fun renderSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Invalid PDF page size" }
    val scale = minOf(
        2f,
        4_096f / width,
        4_096f / height,
        sqrt(12_000_000f / (width.toFloat() * height)),
    )
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

object PdfExporter {
    suspend fun export(
        source: File,
        destination: () -> OutputStream,
        annotations: DocumentAnnotations,
        symbolTypeface: Typeface,
    ) {
        val context = currentCoroutineContext()
        context.ensureActive()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount > MAX_EXPORT_PAGES) throw PdfExportLimitException()
                val runtime = Runtime.getRuntime()
                val budget = PdfExportBudget(runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()))
                repeat(renderer.pageCount) { index ->
                    context.ensureActive()
                    renderer.openPage(index).use { page -> budget.include(page.width, page.height) }
                }
                val document = PdfDocument()
                try {
                    repeat(renderer.pageCount) { index ->
                        context.ensureActive()
                        renderer.openPage(index).use { page ->
                            val bitmap = renderPage(page)
                            try {
                                val outputPage = document.startPage(
                                    PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create(),
                                )
                                try {
                                    val bounds = RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
                                    outputPage.canvas.drawBitmap(bitmap, null, bounds, null)
                                    annotations.pages[index].orEmpty().forEach { annotation ->
                                        AnnotationRenderer.draw(
                                            outputPage.canvas,
                                            annotation,
                                            bounds,
                                            symbolTypeface,
                                        )
                                    }
                                } finally {
                                    document.finishPage(outputPage)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    context.ensureActive()
                    destination().use { output ->
                        context.ensureActive()
                        document.writeTo(output)
                        output.flush()
                    }
                } finally {
                    document.close()
                }
            }
        }
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val (width, height) = renderSize(page.width, page.height)
        return createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
        ).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        }
    }

}
