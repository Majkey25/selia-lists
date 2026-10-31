package cz.teply.sheetset.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.teply.sheetset.MainActivity
import cz.teply.sheetset.settings.PageFit
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPageViewInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var source: File
    private lateinit var view: PdfPageView

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        source = createPdf(context)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            view = PdfPageView(activity)
            activity.setContentView(view)
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { view.showPage(source, 0) }
        awaitRenderedPage()
    }

    @After
    fun tearDown() {
        scenario.close()
        source.delete()
    }

    @Test
    fun penStrokeCommitsOnePresetStyledAnnotation() {
        val added = mutableListOf<PageAnnotation>()
        val settings = AnnotationEditorSettings.defaults()
        scenario.onActivity {
            view.editorSettings = settings
            view.activeDrawingPreset = settings.preset("pen-2")
            view.tool = ReaderTool.PEN
            view.onAddAnnotation = added::add
        }

        drag(pagePoint(0.2f, 0.2f), pagePoint(0.8f, 0.8f))

        val stroke = added.single() as InkAnnotation
        assertEquals(AnnotationColor.BLUE, stroke.color)
        assertEquals(255, stroke.opacity)
        assertEquals(0.005f, stroke.width, 0.0001f)
    }

    @Test
    fun highlighterUsesReadableDefaultWidthAndOpacity() {
        val added = mutableListOf<PageAnnotation>()
        val settings = AnnotationEditorSettings.defaults()
        scenario.onActivity {
            view.editorSettings = settings
            view.activeDrawingPreset = settings.preset("highlighter")
            view.tool = ReaderTool.HIGHLIGHTER
            view.onAddAnnotation = added::add
        }

        drag(pagePoint(0.2f, 0.5f), pagePoint(0.8f, 0.5f))

        val stroke = added.single() as InkAnnotation
        assertEquals(InkKind.HIGHLIGHTER, stroke.kind)
        assertEquals(AnnotationColor.YELLOW, stroke.color)
        assertEquals(105, stroke.opacity)
        assertEquals(0.08f, stroke.width, 0.0001f)
    }

    @Test
    fun widestHighlighterStrokeCommitsWithoutCrashing() {
        val added = mutableListOf<PageAnnotation>()
        val settings = AnnotationEditorSettings.defaults()
        val highlighter = settings.preset("highlighter").copy(width = 6)
        scenario.onActivity {
            view.editorSettings = settings
            view.activeDrawingPreset = highlighter
            view.tool = ReaderTool.HIGHLIGHTER
            view.onAddAnnotation = added::add
        }

        drag(pagePoint(0.2f, 0.5f), pagePoint(0.8f, 0.5f))

        assertEquals(0.2f, (added.single() as InkAnnotation).width, 0.0001f)
    }

    @Test
    fun widestPenMatchesHighlighterMaximum() {
        val added = mutableListOf<PageAnnotation>()
        val settings = AnnotationEditorSettings.defaults()
        val pen = settings.preset("pen-1").copy(width = 6)
        scenario.onActivity {
            view.editorSettings = settings
            view.activeDrawingPreset = pen
            view.tool = ReaderTool.PEN
            view.onAddAnnotation = added::add
        }

        drag(pagePoint(0.2f, 0.5f), pagePoint(0.8f, 0.5f))

        assertEquals(0.2f, (added.single() as InkAnnotation).width, 0.0001f)
    }

    @Test
    fun canceledStrokeCommitsNothing() {
        val added = mutableListOf<PageAnnotation>()
        scenario.onActivity {
            view.tool = ReaderTool.PEN
            view.onAddAnnotation = added::add
        }

        drag(pagePoint(0.2f, 0.2f), pagePoint(0.8f, 0.8f), MotionEvent.ACTION_CANCEL)

        assertTrue(added.isEmpty())
    }

    @Test
    fun liftingOutsidePageCommitsLastValidStrokePoint() {
        val added = mutableListOf<PageAnnotation>()
        scenario.onActivity {
            view.tool = ReaderTool.PEN
            view.onAddAnnotation = added::add
        }
        val downTime = SystemClock.uptimeMillis()
        send(event(downTime, downTime, MotionEvent.ACTION_DOWN, Pointer(0, FINGER, pagePoint(0.2f, 0.2f))))
        send(event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, Pointer(0, FINGER, pagePoint(0.8f, 0.8f))))
        send(event(downTime, downTime + 20, MotionEvent.ACTION_UP, Pointer(0, FINGER, pagePoint(1.1f, 1.1f))))

        assertEquals(NormalizedPoint(0.8f, 0.8f), (added.single() as InkAnnotation).points.last())
    }

    @Test
    fun changingPageOrSourceClearsBitmapAndCancelsStroke() {
        val replacement = createPdf(ApplicationProvider.getApplicationContext())
        val added = mutableListOf<PageAnnotation>()
        try {
            for ((file, index) in listOf(source to 1, replacement to 1)) {
                val start = pagePoint(0.2f, 0.2f)
                val end = pagePoint(0.8f, 0.8f)
                val downTime = SystemClock.uptimeMillis()
                scenario.onActivity {
                    view.tool = ReaderTool.PEN
                    view.onAddAnnotation = added::add
                }
                send(event(downTime, downTime, MotionEvent.ACTION_DOWN, Pointer(0, FINGER, start)))
                send(event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, Pointer(0, FINGER, end)))
                scenario.onActivity {
                    view.showPage(file, index)
                    val output = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(output))
                        assertEquals(Color.rgb(18, 18, 18), output.getPixel(view.width / 2, view.height / 2))
                    } finally {
                        output.recycle()
                    }
                    val up = event(downTime, downTime + 20, MotionEvent.ACTION_UP, Pointer(0, FINGER, end))
                    view.dispatchTouchEvent(up)
                    up.recycle()
                    assertTrue(added.isEmpty())
                }
                awaitRenderedPage()
                send(event(downTime, downTime + 30, MotionEvent.ACTION_UP, Pointer(0, FINGER, end)))
                assertTrue(added.isEmpty())
            }
        } finally {
            replacement.delete()
        }
    }

    @Test
    fun pageChangeCancelsViewGestureUntilNextDown() {
        val tallSource = createPdf(ApplicationProvider.getApplicationContext(), pageHeight = 1_200)
        var nextCount = 0
        try {
            scenario.onActivity {
                view.layout(0, 0, 300, 300)
                view.pageFit = PageFit.WIDTH
                view.onNextPage = { nextCount++ }
                view.showPage(tallSource, 0)
            }
            awaitRenderedPage()
            val point = Point(250f, 150f)
            val downTime = SystemClock.uptimeMillis()
            send(event(downTime, downTime, MotionEvent.ACTION_DOWN, Pointer(0, FINGER, point)))
            scenario.onActivity { view.showPage(tallSource, 1) }
            awaitRenderedPage()
            val before = viewport()
            assertTrue(before.panY > 0f)

            send(event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, Pointer(0, FINGER, point.copy(y = 50f))))
            assertEquals(before, viewport())
            send(event(downTime, downTime + 20, MotionEvent.ACTION_UP, Pointer(0, FINGER, point)))
            assertEquals(0, nextCount)

            tap(point)
            assertEquals(1, nextCount)
        } finally {
            tallSource.delete()
        }
    }

    @Test
    fun viewStylusPointerUpDoesNotTurnPageFromRemainingFinger() {
        var nextCount = 0
        scenario.onActivity {
            view.layout(0, 0, 300, 300)
            view.tool = ReaderTool.VIEW
            view.pageFit = PageFit.PAGE
            view.editorSettings = AnnotationEditorSettings.defaults().copy(palmRejection = true)
            view.onNextPage = { nextCount++ }
        }
        val stylus = Point(250f, 150f)
        val finger = Point(50f, 150f)
        val downTime = SystemClock.uptimeMillis()

        send(event(downTime, downTime, MotionEvent.ACTION_DOWN, Pointer(10, STYLUS, stylus)))
        send(
            event(
                downTime, downTime + 10,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(10, STYLUS, stylus), Pointer(11, FINGER, finger),
            ),
        )
        send(
            event(
                downTime, downTime + 20, MotionEvent.ACTION_POINTER_UP,
                Pointer(10, STYLUS, stylus), Pointer(11, FINGER, finger),
            ),
        )
        send(event(downTime, downTime + 30, MotionEvent.ACTION_UP, Pointer(11, FINGER, finger)))

        assertEquals("The remaining finger must not finish the stylus gesture", 0, nextCount)
        tap(stylus)
        assertEquals("A fresh finger gesture must still turn the page", 1, nextCount)
    }

    @Test
    fun enteringHalfPageModeRealignsUnchangedTopPart() {
        val tallSource = createPdf(ApplicationProvider.getApplicationContext(), pageHeight = 1_200)
        try {
            scenario.onActivity {
                view.layout(0, 0, 300, 300)
                view.pageFit = PageFit.WIDTH
                view.showPage(tallSource, 0)
            }
            awaitRenderedPage()
            drag(Point(150f, 150f), Point(150f, 50f))
            val panned = viewport()
            scenario.onActivity { view.setHalfPagePart(0, halfPageMode = true) }

            assertTrue(viewport().panY > panned.panY)
            assertEquals(1f, viewport().zoom)
        } finally {
            tallSource.delete()
        }
    }

    @Test
    fun halfPageResizeKeepsSelectedHalfAnchoredAndReusesBitmap() {
        val tallSource = createPdf(ApplicationProvider.getApplicationContext(), pageHeight = 1_200)
        try {
            scenario.onActivity {
                view.layout(0, 0, 300, 600)
                view.pageFit = PageFit.WIDTH
                view.showPage(tallSource, 0)
            }
            awaitRenderQueue()
            val bitmap = renderedBitmap()
            for (part in 0..1) {
                scenario.onActivity {
                    view.layout(0, 0, 300, 600)
                    view.setHalfPagePart(part, halfPageMode = true)
                }
                awaitRenderQueue()
                assertEquals(if (part == 0) 300f else -300f, viewport().panY, 0.001f)

                scenario.onActivity { view.layout(0, 0, 300, 300) }
                awaitRenderQueue()

                val resized = viewport()
                assertEquals(
                    "The selected half must stay anchored after height shrinks",
                    if (part == 0) 450f else -450f,
                    resized.panY,
                    0.001f,
                )
                assertEquals(1f, resized.zoom, 0.0001f)
                assertSame("Half-page resizing must reuse the rendered bitmap", bitmap, renderedBitmap())
            }
        } finally {
            tallSource.delete()
        }
    }

    @Test
    fun renderFailureReportsErrorAndClearsPreviousPage() {
        val failed = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity {
            view.onRenderError = { failed.countDown() }
            view.showPage(File(source.parentFile, "missing-${UUID.randomUUID()}.pdf"), 0)
        }

        assertTrue(failed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(Color.rgb(18, 18, 18), pageCenterColor())
        scenario.onActivity { view.showPage(source, 0) }
        awaitRenderedPage()
    }

    @Test
    fun pageRenderingCompletesBeforeViewIsAttached() {
        scenario.onActivity { activity ->
            view = PdfPageView(activity)
            view.layout(0, 0, 300, 300)
            view.showPage(source, 0)
            assertFalse(view.isAttachedToWindow)
        }
        try {
            awaitRenderedPage()
        } finally {
            scenario.onActivity { activity -> activity.setContentView(view) }
        }
    }

    @Test
    fun repeatedResizePreservesZoomAndRenderedBitmap() {
        awaitRenderQueue()
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime
        var first = pagePoint(0.45f, 0.5f)
        var second = pagePoint(0.55f, 0.5f)
        send(event(downTime, eventTime, MotionEvent.ACTION_DOWN, Pointer(0, FINGER, first)))
        send(
            event(
                downTime, ++eventTime,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(0, FINGER, first), Pointer(1, FINGER, second),
            ),
        )
        for (radius in listOf(0.075f, 0.125f, 0.2f, 0.3f, 0.4f)) {
            first = pagePoint(0.5f - radius, 0.5f)
            second = pagePoint(0.5f + radius, 0.5f)
            eventTime += 20
            send(
                event(
                    downTime, eventTime, MotionEvent.ACTION_MOVE,
                    Pointer(0, FINGER, first), Pointer(1, FINGER, second),
                ),
            )
        }
        send(
            event(
                downTime, ++eventTime,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(0, FINGER, first), Pointer(1, FINGER, second),
            ),
        )
        send(event(downTime, ++eventTime, MotionEvent.ACTION_UP, Pointer(0, FINGER, first)))
        val before = viewport()
        val bitmap = renderedBitmap()
        assertTrue("The page must be zoomed before resizing", before.zoom > 1f)

        repeat(3) {
            scenario.onActivity { view.layout(0, 0, view.width - 10, view.height - 10) }
            awaitRenderQueue()
            val after = viewport()
            val resizedBitmap = renderedBitmap()
            assertEquals(
                "Resize must preserve zoom; bitmap reused=${bitmap === resizedBitmap}",
                before.zoom,
                after.zoom,
                0.0001f,
            )
            assertSame("Resize must reuse the rendered bitmap", bitmap, resizedBitmap)
            assertFalse("The displayed bitmap must remain usable", bitmap.isRecycled)
        }
    }

    @Test
    fun detachedAndReattachedViewRendersCurrentAndNextPage() {
        val replacement = createPdf(ApplicationProvider.getApplicationContext(), secondPageColor = Color.RED)
        try {
            scenario.onActivity { view.showPage(replacement, 0) }
            awaitRenderedPage()
            awaitRenderQueue()
            scenario.onActivity {
                assertTrue(view.isAttachedToWindow)
                val parent = view.parent as ViewGroup
                val index = parent.indexOfChild(view)
                val layoutParams = view.layoutParams
                parent.removeView(view)
                assertFalse(view.isAttachedToWindow)
                parent.addView(view, index, layoutParams)
                assertTrue(view.isAttachedToWindow)
            }
            awaitRenderedPage()
            scenario.onActivity { view.showPage(replacement, 1) }
            awaitRenderedPage(Color.RED)
        } finally {
            replacement.delete()
        }
    }

    @Test
    fun twoFingerGesturePansAndZoomsWithoutCommittingPenStroke() {
        val added = mutableListOf<PageAnnotation>()
        scenario.onActivity {
            view.tool = ReaderTool.PEN
            view.onAddAnnotation = added::add
        }
        val before = viewport()
        val first = pagePoint(0.45f, 0.5f)
        val second = pagePoint(0.55f, 0.5f)
        val expandedFirst = pagePoint(0.425f, 0.5f)
        val expandedSecond = pagePoint(0.575f, 0.5f)
        val widerFirst = pagePoint(0.375f, 0.5f)
        val widerSecond = pagePoint(0.625f, 0.5f)
        val wideFirst = pagePoint(0.3f, 0.5f)
        val wideSecond = pagePoint(0.7f, 0.5f)
        val widestFirst = pagePoint(0.2f, 0.5f)
        val widestSecond = pagePoint(0.8f, 0.5f)
        val zoomedFirst = pagePoint(0.1f, 0.5f)
        val zoomedSecond = pagePoint(0.9f, 0.5f)
        val movedFirst = pagePoint(0.15f, 0.5f)
        val movedSecond = pagePoint(0.95f, 0.5f)
        val downTime = SystemClock.uptimeMillis()

        send(event(downTime, downTime, MotionEvent.ACTION_DOWN, Pointer(0, FINGER, first)))
        send(
            event(
                downTime,
                downTime + 10,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(0, FINGER, first),
                Pointer(1, FINGER, second),
            ),
        )
        send(
            event(
                downTime,
                downTime + 20,
                MotionEvent.ACTION_MOVE,
                Pointer(0, FINGER, expandedFirst),
                Pointer(1, FINGER, expandedSecond),
            ),
        )
        send(
            event(
                downTime,
                downTime + 30,
                MotionEvent.ACTION_MOVE,
                Pointer(0, FINGER, widerFirst),
                Pointer(1, FINGER, widerSecond),
            ),
        )
        send(
            event(
                downTime,
                downTime + 50,
                MotionEvent.ACTION_MOVE,
                Pointer(0, FINGER, wideFirst),
                Pointer(1, FINGER, wideSecond),
            ),
        )
        send(
            event(
                downTime,
                downTime + 70,
                MotionEvent.ACTION_MOVE,
                Pointer(0, FINGER, widestFirst),
                Pointer(1, FINGER, widestSecond),
            ),
        )
        send(
            event(
                downTime,
                downTime + 90,
                MotionEvent.ACTION_MOVE,
                Pointer(0, FINGER, zoomedFirst),
                Pointer(1, FINGER, zoomedSecond),
            ),
        )
        send(
            event(
                downTime,
                downTime + 110,
                MotionEvent.ACTION_MOVE,
                Pointer(0, FINGER, movedFirst),
                Pointer(1, FINGER, movedSecond),
            ),
        )
        send(
            event(
                downTime,
                downTime + 130,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(0, FINGER, movedFirst),
                Pointer(1, FINGER, movedSecond),
            ),
        )
        send(event(downTime, downTime + 150, MotionEvent.ACTION_UP, Pointer(0, FINGER, movedFirst)))

        val after = viewport()
        assertTrue("before=$before after=$after", after.zoom > before.zoom)
        assertTrue("before=$before after=$after", after.panX > before.panX)
        assertTrue(added.isEmpty())

        scenario.onActivity {
            view.tool = ReaderTool.PEN
            view.pageFit = PageFit.PAGE
            view.setHalfPagePart(0)
        }
        assertEquals(after, viewport())
    }

    @Test
    fun twoFingerZoomIgnoresStylusMotionAndEndsWhenFingersLift() {
        val added = mutableListOf<PageAnnotation>()
        scenario.onActivity {
            view.editorSettings = AnnotationEditorSettings.defaults().copy(palmRejection = true)
            view.tool = ReaderTool.PEN
            view.onAddAnnotation = added::add
        }
        val downTime = SystemClock.uptimeMillis()
        var eventTime = downTime
        var stylus = pagePoint(0.2f, 0.2f)
        var first = pagePoint(0.45f, 0.5f)
        var second = pagePoint(0.55f, 0.5f)
        fun move() {
            eventTime += 20
            send(
                event(
                    downTime, eventTime, MotionEvent.ACTION_MOVE,
                    Pointer(10, STYLUS, stylus), Pointer(11, FINGER, first), Pointer(12, FINGER, second),
                ),
            )
        }
        send(event(downTime, eventTime, MotionEvent.ACTION_DOWN, Pointer(10, STYLUS, stylus)))
        send(
            event(
                downTime, ++eventTime,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(10, STYLUS, stylus), Pointer(11, FINGER, first),
            ),
        )
        send(
            event(
                downTime, ++eventTime,
                MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(10, STYLUS, stylus), Pointer(11, FINGER, first), Pointer(12, FINGER, second),
            ),
        )
        for (radius in listOf(0.075f, 0.125f, 0.2f, 0.3f, 0.4f)) {
            first = pagePoint(0.5f - radius, 0.5f)
            second = pagePoint(0.5f + radius, 0.5f)
            move()
        }
        val beforeStylusMove = viewport()
        assertTrue("Two fingers must still zoom with a stylus down", beforeStylusMove.zoom > 1f)
        assertTrue(added.isEmpty())

        stylus = pagePoint(0.8f, 0.8f)
        move()

        val afterStylusMove = viewport()
        assertEquals("Stylus movement must not zoom", beforeStylusMove.zoom, afterStylusMove.zoom, 0.0001f)
        assertEquals("Stylus movement must not pan X", beforeStylusMove.panX, afterStylusMove.panX, 0.001f)
        assertEquals("Stylus movement must not pan Y", beforeStylusMove.panY, afterStylusMove.panY, 0.001f)
        first = pagePoint(0.15f, 0.5f)
        second = pagePoint(0.95f, 0.5f)
        move()
        assertTrue("Moving both fingers must still pan", viewport().panX > afterStylusMove.panX)
        send(
            event(
                downTime, ++eventTime,
                MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(10, STYLUS, stylus), Pointer(11, FINGER, first), Pointer(12, FINGER, second),
            ),
        )
        send(
            event(
                downTime, ++eventTime,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(10, STYLUS, stylus), Pointer(11, FINGER, first),
            ),
        )
        send(event(downTime, ++eventTime, MotionEvent.ACTION_UP, Pointer(10, STYLUS, stylus)))
        assertTrue(added.isEmpty())

        drag(pagePoint(0.4f, 0.4f), pagePoint(0.6f, 0.6f))
        assertEquals("A fresh stroke must work after the mixed gesture", 1, added.size)
    }

    @Test
    fun palmRejectionKeepsStylusStrokeThroughMixedPointerStream() {
        val added = mutableListOf<PageAnnotation>()
        scenario.onActivity {
            view.editorSettings = AnnotationEditorSettings.defaults().copy(palmRejection = true)
            view.tool = ReaderTool.PEN
            view.onAddAnnotation = added::add
        }
        val stylusStart = pagePoint(0.25f, 0.25f)
        val stylusMiddle = pagePoint(0.4f, 0.4f)
        val stylusEnd = pagePoint(0.75f, 0.75f)
        val fingerStart = pagePoint(0.8f, 0.2f)
        val fingerEnd = pagePoint(0.7f, 0.3f)
        val downTime = SystemClock.uptimeMillis()

        send(event(downTime, downTime, MotionEvent.ACTION_DOWN, Pointer(10, STYLUS, stylusStart)))
        send(event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, Pointer(10, STYLUS, stylusMiddle)))
        send(
            event(
                downTime,
                downTime + 20,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(10, STYLUS, stylusMiddle),
                Pointer(11, FINGER, fingerStart),
            ),
        )
        send(
            event(
                downTime,
                downTime + 30,
                MotionEvent.ACTION_MOVE,
                Pointer(11, FINGER, fingerEnd),
                Pointer(10, STYLUS, stylusEnd),
            ),
        )
        send(
            event(
                downTime,
                downTime + 40,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(10, STYLUS, stylusEnd),
                Pointer(11, FINGER, fingerEnd),
            ),
        )
        send(event(downTime, downTime + 50, MotionEvent.ACTION_UP, Pointer(10, STYLUS, stylusEnd)))

        val stroke = added.single() as InkAnnotation
        assertEquals(0.25f, stroke.points.first().x, 0.000001f)
        assertEquals(0.25f, stroke.points.first().y, 0.000001f)
        assertEquals(0.75f, stroke.points.last().x, 0.000001f)
        assertEquals(0.75f, stroke.points.last().y, 0.000001f)
    }

    @Test
    fun palmRejectionTransfersFingerGestureToStylus() {
        val added = mutableListOf<PageAnnotation>()
        scenario.onActivity {
            view.editorSettings = AnnotationEditorSettings.defaults().copy(palmRejection = true)
            view.tool = ReaderTool.PEN
            view.onAddAnnotation = added::add
        }
        val fingerStart = pagePoint(0.1f, 0.8f)
        val fingerMove = pagePoint(0.2f, 0.7f)
        val stylusStart = pagePoint(0.3f, 0.3f)
        val stylusMove = pagePoint(0.5f, 0.5f)
        val stylusEnd = pagePoint(0.8f, 0.8f)
        val downTime = SystemClock.uptimeMillis()

        send(event(downTime, downTime, MotionEvent.ACTION_DOWN, Pointer(11, FINGER, fingerStart)))
        send(event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, Pointer(11, FINGER, fingerMove)))
        send(
            event(
                downTime,
                downTime + 20,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(11, FINGER, fingerMove),
                Pointer(10, STYLUS, stylusStart),
            ),
        )
        send(
            event(
                downTime,
                downTime + 30,
                MotionEvent.ACTION_MOVE,
                Pointer(10, STYLUS, stylusMove),
                Pointer(11, FINGER, fingerMove),
            ),
        )
        send(
            event(
                downTime,
                downTime + 40,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                Pointer(10, STYLUS, stylusMove),
                Pointer(11, FINGER, fingerMove),
            ),
        )
        send(event(downTime, downTime + 50, MotionEvent.ACTION_MOVE, Pointer(10, STYLUS, stylusEnd)))
        send(event(downTime, downTime + 60, MotionEvent.ACTION_UP, Pointer(10, STYLUS, stylusEnd)))

        val stroke = added.single() as InkAnnotation
        assertEquals(0.3f, stroke.points.first().x, 0.000001f)
        assertEquals(0.3f, stroke.points.first().y, 0.000001f)
        assertEquals(0.8f, stroke.points.last().x, 0.000001f)
        assertEquals(0.8f, stroke.points.last().y, 0.000001f)
        assertFalse(stroke.points.any { abs(it.x - 0.1f) <= 0.000001f && abs(it.y - 0.8f) <= 0.000001f })
        assertFalse(stroke.points.any { abs(it.x - 0.2f) <= 0.000001f && abs(it.y - 0.7f) <= 0.000001f })
    }

    @Test
    fun eyedropperSamplesPdfBitmapAndReturnsToPreviousTool() {
        val sampled = mutableListOf<AnnotationColor>()
        scenario.onActivity {
            view.tool = ReaderTool.PEN
            view.onSampleColor = sampled::add
            view.startEyedropper()
        }

        tap(pagePoint(0.5f, 0.5f))

        assertEquals(listOf(PDF_COLOR), sampled)
        scenario.onActivity {
            assertEquals(ReaderTool.PEN, view.tool)
            assertFalse(view.eyedropperActive)
        }
    }

    @Test
    fun lassoReturnsIntersectingAnnotationIds() {
        val selections = mutableListOf<Set<String>>()
        scenario.onActivity {
            view.annotations = listOf(
                rectangle("inside", 0.1f, 0.1f, 0.2f, 0.2f),
                rectangle("outside", 0.7f, 0.7f, 0.8f, 0.8f),
            )
            view.tool = ReaderTool.LASSO
            view.onSelectionChange = selections::add
        }

        drag(pagePoint(0.05f, 0.05f), pagePoint(0.3f, 0.3f))

        assertEquals(setOf("inside"), selections.single())
    }

    @Test
    fun selectedBatchMoveEmitsOneFullPageUpdate() {
        val updates = mutableListOf<List<PageAnnotation>>()
        val first = rectangle("first", 0.1f, 0.1f, 0.2f, 0.2f)
        val second = rectangle("second", 0.4f, 0.4f, 0.5f, 0.5f)
        scenario.onActivity {
            view.annotations = listOf(first, second)
            view.selectedAnnotationIds = setOf(first.id, second.id)
            view.tool = ReaderTool.SELECT
            view.onUpdateAnnotations = updates::add
        }

        drag(pagePoint(0.15f, 0.15f), pagePoint(0.25f, 0.25f))

        assertEquals(1, updates.size)
        assertEquals(0.2f, updates.single()[0].normalizedBounds().left, 0.001f)
        assertEquals(0.5f, updates.single()[1].normalizedBounds().left, 0.001f)
    }

    @Test
    fun eraserGestureEmitsOneBatchDelete() {
        val deletes = mutableListOf<Set<String>>()
        scenario.onActivity {
            view.annotations = listOf(
                rectangle("first", 0.15f, 0.45f, 0.25f, 0.55f),
                rectangle("second", 0.65f, 0.45f, 0.75f, 0.55f),
            )
            view.tool = ReaderTool.ERASER
            view.onDeleteAnnotations = deletes::add
        }

        drag(pagePoint(0.2f, 0.5f), pagePoint(0.7f, 0.5f))

        assertEquals(listOf(setOf("first", "second")), deletes)
    }

    @Test
    fun symbolRotationEmitsOneFullPageUpdate() {
        val updates = mutableListOf<List<PageAnnotation>>()
        val symbol = SymbolAnnotation(
            id = "symbol",
            symbolId = "sharp",
            center = NormalizedPoint(0.5f, 0.5f),
            size = 0.2f,
            rotationDegrees = 0f,
            color = AnnotationColor.BLACK,
            opacity = 255,
        )
        scenario.onActivity {
            view.annotations = listOf(symbol)
            view.selectedAnnotationIds = setOf(symbol.id)
            view.tool = ReaderTool.SELECT
            view.onUpdateAnnotations = updates::add
        }

        drag(pagePoint(0.5f, 0.4f), pagePoint(0.7f, 0.5f))

        assertEquals(1, updates.size)
        assertEquals(90f, (updates.single().single() as SymbolAnnotation).rotationDegrees, 0.001f)
    }

    @Test
    fun symbolTapRequestsNormalizedPlacementPoint() {
        val points = mutableListOf<NormalizedPoint>()
        scenario.onActivity {
            view.tool = ReaderTool.SYMBOL
            view.onRequestSymbol = points::add
        }

        tap(pagePoint(0.5f, 0.5f))

        assertEquals(0.5f, points.single().x, 0.001f)
        assertEquals(0.5f, points.single().y, 0.001f)
    }

    private fun awaitRenderQueue() {
        instrumentation.waitForIdleSync()
        lateinit var executor: ExecutorService
        scenario.onActivity {
            executor = PdfPageView::class.java.getDeclaredField("executor")
                .apply { isAccessible = true }.get(view) as ExecutorService
        }
        executor.submit(Runnable {}).get(5, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }

    private fun renderedBitmap(): Bitmap {
        var bitmap: Bitmap? = null
        scenario.onActivity {
            bitmap = PdfPageView::class.java.getDeclaredField("bitmap")
                .apply { isAccessible = true }.get(view) as Bitmap?
        }
        return requireNotNull(bitmap)
    }

    private fun awaitRenderedPage(expectedColor: Int = PDF_COLOR.argb) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (pageCenterColor() != expectedColor && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
        }
        assertEquals(expectedColor, pageCenterColor())
    }

    private fun pageCenterColor(): Int {
        var color = Color.TRANSPARENT
        scenario.onActivity {
            val output = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output))
            color = output.getPixel(view.width / 2, view.height / 2)
            output.recycle()
        }
        return color
    }

    private fun viewport(): PdfViewport {
        var result: PdfViewport? = null
        scenario.onActivity { result = view.currentViewport }
        return requireNotNull(result)
    }

    private fun pagePoint(x: Float, y: Float): Point {
        var result: Point? = null
        scenario.onActivity {
            val size = min(view.width, view.height).toFloat()
            result = Point(
                x = (view.width - size) / 2f + size * x,
                y = (view.height - size) / 2f + size * y,
            )
        }
        return requireNotNull(result)
    }

    private fun tap(point: Point) = drag(point, point)

    private fun drag(start: Point, end: Point, finishAction: Int = MotionEvent.ACTION_UP) {
        val downTime = SystemClock.uptimeMillis()
        send(event(downTime, downTime, MotionEvent.ACTION_DOWN, Pointer(0, FINGER, start)))
        send(event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, Pointer(0, FINGER, end)))
        send(event(downTime, downTime + 20, finishAction, Pointer(0, FINGER, end)))
    }

    private fun send(event: MotionEvent) {
        scenario.onActivity {
            assertTrue(view.dispatchTouchEvent(event))
            event.recycle()
        }
    }

    private fun event(
        downTime: Long,
        eventTime: Long,
        action: Int,
        vararg pointers: Pointer,
    ): MotionEvent = MotionEvent.obtain(
        downTime,
        eventTime,
        action,
        pointers.size,
        pointers.map { pointer ->
            MotionEvent.PointerProperties().apply {
                id = pointer.id
                toolType = pointer.toolType
            }
        }.toTypedArray(),
        pointers.map { pointer ->
            MotionEvent.PointerCoords().apply {
                x = pointer.point.x
                y = pointer.point.y
                pressure = 1f
                size = 0.1f
            }
        }.toTypedArray(),
        0,
        0,
        1f,
        1f,
        0,
        0,
        if (pointers.any { it.toolType == STYLUS }) {
            InputDevice.SOURCE_STYLUS
        } else {
            InputDevice.SOURCE_TOUCHSCREEN
        },
        0,
    )

    private fun createPdf(
        context: Context,
        pageHeight: Int = 300,
        secondPageColor: Int = PDF_COLOR.argb,
    ): File {
        val file = File(context.cacheDir, "input-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, pageHeight, index + 1).create())
                page.canvas.drawColor(if (index == 0) PDF_COLOR.argb else secondPageColor)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    private fun rectangle(
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): ShapeAnnotation = ShapeAnnotation(
        id = id,
        kind = ShapeKind.RECTANGLE,
        start = NormalizedPoint(left, top),
        end = NormalizedPoint(right, bottom),
        width = 0.004f,
    )

    private data class Point(val x: Float, val y: Float)

    private data class Pointer(val id: Int, val toolType: Int, val point: Point)

    private companion object {
        val PDF_COLOR = AnnotationColor(Color.rgb(20, 80, 160))
        const val FINGER = MotionEvent.TOOL_TYPE_FINGER
        const val STYLUS = MotionEvent.TOOL_TYPE_STYLUS
    }
}
