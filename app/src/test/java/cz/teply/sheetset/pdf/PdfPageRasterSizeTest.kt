package cz.teply.sheetset.pdf

import cz.teply.sheetset.settings.PageFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageRasterSizeTest {
    @Test
    fun portraitPageUsesTheVisiblePixelWidth() {
        assertEquals(PdfPageRasterSize(720, 1019), size(595, 842, 720, 1280))
        assertEquals(PdfPageRasterSize(360, 509), size(595, 842, 360, 1280))
    }

    @Test
    fun landscapeAndHalfPageUseTheSelectedFit() {
        assertEquals(PdfPageRasterSize(509, 720), size(595, 842, 1280, 720))
        assertEquals(
            PdfPageRasterSize(1190, 1684),
            pdfPageRasterSize(595, 842, 1280, 720, PageFit.WIDTH, 1f),
        )
    }

    @Test
    fun zoomRestoresThePreviousFullRasterResolution() {
        assertEquals(PdfPageRasterSize(1190, 1684), size(595, 842, 720, 1280, zoom = 5f))
    }

    @Test
    fun largeAndExtremePagesStayBoundedAndNonempty() {
        val square = size(10_000, 10_000, 4000, 4000, zoom = 5f)
        assertEquals(PdfPageRasterSize(3072, 3072), square)
        assertTrue(square.width.toLong() * square.height * 4 <= 36L * 1024 * 1024)
        assertEquals(PdfPageRasterSize(1, 3072), size(1, Int.MAX_VALUE, 720, 1280, zoom = 5f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonfiniteZoomIsRejected() {
        size(595, 842, 720, 1280, Float.NaN)
    }

    private fun size(width: Int, height: Int, viewWidth: Int, viewHeight: Int, zoom: Float = 1f) =
        pdfPageRasterSize(width, height, viewWidth, viewHeight, PageFit.PAGE, zoom)
}
