package cz.teply.sheetset.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import cz.teply.sheetset.R
import cz.teply.sheetset.settings.AnnotationTextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class PdfExporterTest {
    @Test
    fun transparentArrowKeepsOpacityAtTip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val typeface = requireNotNull(ResourcesCompat.getFont(context, R.font.noto_music_regular))
        val bitmap = Bitmap.createBitmap(1_000, 1_000, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            AnnotationRenderer.draw(
                Canvas(bitmap),
                ShapeAnnotation(
                    id = "arrow",
                    kind = ShapeKind.ARROW,
                    start = NormalizedPoint(0.1f, 0.5f),
                    end = NormalizedPoint(0.9f, 0.5f),
                    width = 0.02f,
                    opacity = 128,
                ),
                RectF(0f, 0f, 1_000f, 1_000f),
                typeface,
            )
            assertEquals("Arrow body opacity", 128, Color.alpha(bitmap.getPixel(500, 500)))
            assertEquals("Arrow tip opacity", 128, Color.alpha(bitmap.getPixel(900, 500)))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun exportKeepsPreviewTextWrappingAndStrokeWidthsAcrossPageScales() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val typeface = requireNotNull(ResourcesCompat.getFont(context, R.font.noto_music_regular))
        val annotations = listOf(
            InkAnnotation(
                id = "ink",
                kind = InkKind.PEN,
                width = 0.002f,
                points = listOf(NormalizedPoint(0.1f, 0.1f), NormalizedPoint(0.9f, 0.1f)),
            ),
            ShapeAnnotation(
                id = "line",
                kind = ShapeKind.LINE,
                start = NormalizedPoint(0.1f, 0.2f),
                end = NormalizedPoint(0.9f, 0.2f),
                width = 0.0015f,
            ),
            MarkupAnnotation(
                id = "underline",
                kind = MarkupKind.UNDERLINE,
                bounds = listOf(NormalizedRect(0.1f, 0.26f, 0.9f, 0.31f)),
            ),
            MarkupAnnotation(
                id = "strike",
                kind = MarkupKind.STRIKE_THROUGH,
                bounds = listOf(NormalizedRect(0.1f, 0.35f, 0.9f, 0.41f)),
            ),
            TextBoxAnnotation(
                id = "text",
                bounds = NormalizedRect(0.1f, 0.5f, 0.7f, 0.95f),
                text = "Allegro moderato\nRepeat softly until the final measure.",
                size = AnnotationTextSize.SMALL,
            ),
        )
        val regions = mapOf(
            "ink" to NormalizedRect(0.05f, 0.06f, 0.95f, 0.14f),
            "shape" to NormalizedRect(0.05f, 0.16f, 0.95f, 0.24f),
            "underline" to NormalizedRect(0.05f, 0.25f, 0.95f, 0.35f),
            "strike" to NormalizedRect(0.05f, 0.35f, 0.95f, 0.45f),
            "text border" to NormalizedRect(0.15f, 0.49f, 0.65f, 0.504f),
            "wrapped text" to NormalizedRect(0.103f, 0.503f, 0.697f, 0.947f),
        )
        val preview = Bitmap.createBitmap(1_000, 1_250, Bitmap.Config.ARGB_8888)
        try {
            preview.eraseColor(Color.WHITE)
            val canvas = Canvas(preview)
            val pageBounds = RectF(0f, 0f, preview.width.toFloat(), preview.height.toFloat())
            annotations.forEach { AnnotationRenderer.draw(canvas, it, pageBounds, typeface) }
            for ((width, height) in listOf(200 to 250, 600 to 750)) {
                val source = createBlankPdf(context, pages = 1, width = width, height = height)
                val destination = File(context.cacheDir, "scale-export-${UUID.randomUUID()}.pdf")
                try {
                    destination.outputStream().use { output ->
                        PdfExporter.export(source, output, DocumentAnnotations(mapOf(0 to annotations)), typeface)
                    }
                    val exported = renderPdf(destination, preview.width, preview.height)
                    try {
                        for ((name, region) in regions) {
                            val (previewInk, previewBounds) = measureInk(preview, region)
                            val (exportedInk, exportedBounds) = measureInk(exported, region)
                            var message = "$width x $height $name preview=$previewBounds exported=$exportedBounds"
                            assertInkBounds(message, previewBounds, exportedBounds)
                            if (name == "wrapped text") {
                                val previewLines = inkLines(preview, region)
                                val exportedLines = inkLines(exported, region)
                                message += " previewLines=$previewLines exportedLines=$exportedLines"
                                assertEquals(message, previewLines.size, exportedLines.size)
                                previewLines.zip(exportedLines).forEach { (expected, actual) ->
                                    assertInkBounds(message, expected, actual)
                                }
                                assertTextInkCoverage(message, preview, exported, region)
                            } else {
                                assertEquals(message, previewInk.toDouble(), exportedInk.toDouble(), previewInk * 0.08)
                            }
                        }
                    } finally {
                        exported.recycle()
                    }
                } finally {
                    source.delete()
                    destination.delete()
                }
            }
        } finally {
            preview.recycle()
        }
    }

    @Test
    fun exportRendersEveryAnnotationTypeAndPreservesOriginal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = createBlankPdf(context, pages = 2)
        val destination = File(context.cacheDir, "export-${UUID.randomUUID()}.pdf")
        val beforeHash = source.sha256()
        val annotations = DocumentAnnotations(
            mapOf(
                0 to listOf(
                    InkAnnotation(
                        id = "ink",
                        kind = InkKind.PEN,
                        width = 0.008f,
                        points = listOf(
                            NormalizedPoint(0.05f, 0.08f),
                            NormalizedPoint(0.35f, 0.18f),
                        ),
                        color = AnnotationColor.RED,
                        opacity = 128,
                    ),
                    MarkupAnnotation(
                        id = "highlight",
                        kind = MarkupKind.HIGHLIGHT,
                        bounds = listOf(NormalizedRect(0.05f, 0.25f, 0.4f, 0.34f)),
                        color = AnnotationColor.BLUE,
                        opacity = 64,
                    ),
                    MarkupAnnotation(
                        id = "underline",
                        kind = MarkupKind.UNDERLINE,
                        bounds = listOf(NormalizedRect(0.05f, 0.36f, 0.4f, 0.42f)),
                        color = AnnotationColor.BLUE,
                    ),
                    MarkupAnnotation(
                        id = "strike",
                        kind = MarkupKind.STRIKE_THROUGH,
                        bounds = listOf(NormalizedRect(0.05f, 0.44f, 0.4f, 0.5f)),
                        color = AnnotationColor.RED,
                    ),
                    TextBoxAnnotation(
                        id = "text",
                        bounds = NormalizedRect(0.05f, 0.55f, 0.45f, 0.7f),
                        text = "Allegro",
                        size = AnnotationTextSize.MEDIUM,
                    ),
                    ShapeAnnotation(
                        id = "line",
                        kind = ShapeKind.LINE,
                        start = NormalizedPoint(0.55f, 0.1f),
                        end = NormalizedPoint(0.9f, 0.1f),
                        width = 0.008f,
                    ),
                    ShapeAnnotation(
                        id = "arrow",
                        kind = ShapeKind.ARROW,
                        start = NormalizedPoint(0.55f, 0.22f),
                        end = NormalizedPoint(0.9f, 0.3f),
                        width = 0.008f,
                    ),
                    ShapeAnnotation(
                        id = "rectangle",
                        kind = ShapeKind.RECTANGLE,
                        start = NormalizedPoint(0.55f, 0.38f),
                        end = NormalizedPoint(0.9f, 0.52f),
                        width = 0.008f,
                    ),
                    ShapeAnnotation(
                        id = "ellipse",
                        kind = ShapeKind.ELLIPSE,
                        start = NormalizedPoint(0.55f, 0.6f),
                        end = NormalizedPoint(0.9f, 0.76f),
                        width = 0.008f,
                    ),
                    SymbolAnnotation(
                        id = "symbol",
                        symbolId = "sharp",
                        center = NormalizedPoint(0.75f, 0.88f),
                        size = 0.15f,
                        rotationDegrees = 0f,
                        color = AnnotationColor.BLACK,
                        opacity = 255,
                    ),
                ),
            ),
        )

        destination.outputStream().use { output ->
            PdfExporter.export(
                source,
                output,
                annotations,
                requireNotNull(ResourcesCompat.getFont(context, R.font.noto_music_regular)),
            )
        }

        assertEquals(beforeHash, source.sha256())
        assertEquals(2, pageCount(destination))
        assertTrue(darkPixels(destination) > darkPixels(source) + 500)
        assertTrue(
            pixelsNearColorIn(
                destination,
                0,
                0,
                140,
                90,
                Color.rgb(233, 151, 151),
                tolerance = 16,
            ) > 50,
        )
        assertTrue(
            pixelsNearColorIn(
                destination,
                0,
                85,
                140,
                145,
                Color.rgb(197, 221, 244),
                tolerance = 16,
            ) > 50,
        )
        assertTrue(darkPixelsIn(destination, 0, 200, 150, 300) > 50)
        assertTrue(darkPixelsIn(destination, 150, 0, 300, 320) > 200)
        assertTrue(darkPixelsIn(destination, 180, 320, 270, 400) > 20)
        source.delete()
        destination.delete()
    }

    private fun createBlankPdf(context: Context, pages: Int, width: Int = 300, height: Int = 400): File {
        val file = File(context.cacheDir, "source-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    private fun renderPdf(file: File, width: Int, height: Int): Bitmap = ParcelFileDescriptor
        .open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        .use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    private fun measureInk(bitmap: Bitmap, region: NormalizedRect): Pair<Long, Rect> {
        var coverage = 0L
        val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
        for (y in (region.top * bitmap.height).toInt() until (region.bottom * bitmap.height).toInt()) {
            for (x in (region.left * bitmap.width).toInt() until (region.right * bitmap.width).toInt()) {
                val red = Color.red(bitmap.getPixel(x, y))
                coverage += 255 - red
                if (red < 128) {
                    bounds.left = minOf(bounds.left, x)
                    bounds.top = minOf(bounds.top, y)
                    bounds.right = maxOf(bounds.right, x + 1)
                    bounds.bottom = maxOf(bounds.bottom, y + 1)
                }
            }
        }
        assertTrue("Expected visible annotation ink", !bounds.isEmpty)
        return coverage to bounds
    }

    private fun assertInkBounds(message: String, expected: Rect, actual: Rect) {
        assertEquals(message, expected.left.toDouble(), actual.left.toDouble(), 2.0)
        assertEquals(message, expected.top.toDouble(), actual.top.toDouble(), 2.0)
        assertEquals(message, expected.right.toDouble(), actual.right.toDouble(), 2.0)
        assertEquals(message, expected.bottom.toDouble(), actual.bottom.toDouble(), 2.0)
    }

    private fun inkLines(bitmap: Bitmap, region: NormalizedRect): List<Rect> {
        val lines = mutableListOf<Rect>()
        var current: Rect? = null
        for (y in (region.top * bitmap.height).toInt() until (region.bottom * bitmap.height).toInt()) {
            val row = Rect(bitmap.width, y, 0, y + 1)
            for (x in (region.left * bitmap.width).toInt() until (region.right * bitmap.width).toInt()) {
                if (Color.red(bitmap.getPixel(x, y)) < 128) {
                    row.left = minOf(row.left, x)
                    row.right = maxOf(row.right, x + 1)
                }
            }
            if (row.isEmpty) {
                current = null
            } else {
                val line = current
                if (line == null) {
                    lines.add(row)
                    current = row
                } else {
                    line.union(row)
                }
            }
        }
        return lines
    }

    private fun assertTextInkCoverage(message: String, preview: Bitmap, exported: Bitmap, region: NormalizedRect) {
        val left = (region.left * preview.width).toInt()
        val top = (region.top * preview.height).toInt()
        val right = (region.right * preview.width).toInt()
        val bottom = (region.bottom * preview.height).toInt()
        fun hasNearbyInk(bitmap: Bitmap, x: Int, y: Int): Boolean {
            for (sampleY in maxOf(top, y - 2) until minOf(bottom, y + 3)) {
                for (sampleX in maxOf(left, x - 2) until minOf(right, x + 3)) {
                    if (Color.red(bitmap.getPixel(sampleX, sampleY)) < 128) return true
                }
            }
            return false
        }
        // PDFium and Canvas hint fonts differently; compare glyph coverage within the same 2px bounds allowance.
        for ((expected, actual) in listOf(preview to exported, exported to preview)) {
            for (y in top until bottom) {
                for (x in left until right) {
                    if (Color.red(expected.getPixel(x, y)) < 128 && !hasNearbyInk(actual, x, y)) {
                        fail("$message unmatched glyph ink at ($x, $y)")
                    }
                }
            }
        }
    }

    private fun pageCount(file: File): Int = ParcelFileDescriptor
        .open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        .use { descriptor -> PdfRenderer(descriptor).use { it.pageCount } }

    private fun darkPixels(file: File): Int = ParcelFileDescriptor
        .open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        .use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    var count = 0
                    for (y in 0 until bitmap.height) {
                        for (x in 0 until bitmap.width) {
                            if (Color.red(bitmap.getPixel(x, y)) < 128) count++
                        }
                    }
                    bitmap.recycle()
                    count
                }
            }
        }

    private fun darkPixelsIn(
        file: File,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Int = pixelsIn(file, left, top, right, bottom) { color ->
        Color.red(color) < 128 && Color.green(color) < 128 && Color.blue(color) < 128
    }

    private fun pixelsNearColorIn(
        file: File,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        expected: Int,
        tolerance: Int,
    ): Int = pixelsIn(file, left, top, right, bottom) { color ->
        Color.red(color) in Color.red(expected) - tolerance..Color.red(expected) + tolerance &&
            Color.green(color) in Color.green(expected) - tolerance..Color.green(expected) + tolerance &&
            Color.blue(color) in Color.blue(expected) - tolerance..Color.blue(expected) + tolerance
    }

    private fun pixelsIn(
        file: File,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        matches: (Int) -> Boolean,
    ): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(0).use { page ->
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                var count = 0
                for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height)) {
                    for (x in left.coerceAtLeast(0) until right.coerceAtMost(bitmap.width)) {
                        if (matches(bitmap.getPixel(x, y))) count++
                    }
                }
                bitmap.recycle()
                count
            }
        }
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { "%02x".format(it) }
}
