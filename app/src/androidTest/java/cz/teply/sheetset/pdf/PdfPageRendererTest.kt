package cz.teply.sheetset.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.teply.sheetset.settings.PageFit
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPageRendererTest {
    @Test
    fun turnsAndDocumentReplacementUseTheCurrentSourceAndFit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = createPdf(context, Color.BLUE)
        val replacement = createPdf(context, Color.GREEN)
        try {
            PdfPageRenderer().use { renderer ->
                assertPage(renderer, first, 0, Color.BLUE)
                assertPage(renderer, first, 1, Color.RED)
                assertPage(renderer, replacement, 0, Color.GREEN)
                renderer.close()
                assertPage(renderer, first, 0, Color.BLUE)
            }
        } finally {
            first.delete()
            replacement.delete()
        }
    }

    private fun assertPage(renderer: PdfPageRenderer, file: File, index: Int, color: Int) {
        val page = renderer.render(file, index, 720, 1280, PageFit.PAGE, 1f)
        try {
            assertEquals(595, page.pageWidth)
            assertEquals(842, page.pageHeight)
            assertEquals(720, page.bitmap.width)
            assertEquals(1019, page.bitmap.height)
            assertEquals(color, page.bitmap.getPixel(360, 509))
        } finally {
            page.bitmap.recycle()
        }
    }

    private fun createPdf(context: Context, firstPageColor: Int): File {
        val file = File(context.cacheDir, "raster-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawColor(if (index == 0) firstPageColor else Color.RED)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }
}
