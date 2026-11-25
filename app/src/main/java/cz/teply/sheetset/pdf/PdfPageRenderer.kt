package cz.teply.sheetset.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import cz.teply.sheetset.settings.PageFit
import java.io.File

internal data class RenderedPdfPage(val bitmap: Bitmap, val pageWidth: Int, val pageHeight: Int)

// All calls, including close, stay on the owning view's serial executor.
internal class PdfPageRenderer : AutoCloseable {
    private var renderer: PdfRenderer? = null
    private var source: File? = null
    private var sourceLength = 0L
    private var sourceModified = 0L

    fun render(
        file: File,
        index: Int,
        viewWidth: Int,
        viewHeight: Int,
        pageFit: PageFit,
        zoom: Float,
    ): RenderedPdfPage {
        val length = file.length()
        val modified = file.lastModified()
        if (source != file || sourceLength != length || sourceModified != modified) {
            close()
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = try {
                PdfRenderer(descriptor)
            } catch (failure: Throwable) {
                descriptor.close()
                throw failure
            }
            source = file
            sourceLength = length
            sourceModified = modified
        }
        val document = requireNotNull(renderer)
        require(index in 0 until document.pageCount)
        return document.openPage(index).use { page ->
            val size = pdfPageRasterSize(page.width, page.height, viewWidth, viewHeight, pageFit, zoom)
            val bitmap = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                RenderedPdfPage(bitmap, page.width, page.height)
            } catch (failure: Throwable) {
                bitmap.recycle()
                throw failure
            }
        }
    }

    override fun close() {
        renderer?.close()
        renderer = null
        source = null
    }
}
