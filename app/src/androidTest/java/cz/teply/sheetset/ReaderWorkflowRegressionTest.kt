package cz.teply.sheetset

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.MAX_TEXT_LENGTH
import cz.teply.sheetset.pdf.NormalizedRect
import cz.teply.sheetset.pdf.PdfPageView
import cz.teply.sheetset.pdf.PlainTextAnnotation
import cz.teply.sheetset.pdf.ReaderTool
import cz.teply.sheetset.pdf.TextBoxAnnotation
import cz.teply.sheetset.settings.AnnotationTextSize
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.DEFAULT_PDF_ZOOM_PERCENT
import cz.teply.sheetset.settings.PageFit
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.settings.SettingsStore
import cz.teply.sheetset.ui.PDF_PAGE_TEST_TAG
import cz.teply.sheetset.ui.ReaderPosition
import cz.teply.sheetset.ui.ReaderScreen
import cz.teply.sheetset.ui.SheetSetActions
import cz.teply.sheetset.ui.SheetSetApp
import cz.teply.sheetset.ui.SheetSetTheme
import cz.teply.sheetset.ui.WindowLayout
import cz.teply.sheetset.ui.nextPosition
import cz.teply.sheetset.ui.previousPosition
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ReaderWorkflowRegressionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var source: File

    @Before
    fun createSource() {
        source = File(composeRule.activity.cacheDir, "reader-workflow-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 600, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                document.finishPage(page)
            }
            source.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    @After
    fun deleteSource() {
        source.delete()
    }

    @Test
    fun pedalReleaseAndRepeatDoNotJumpSliderBackToTopHalf() {
        var reader by mutableStateOf(reader())
        var nextCount = 0
        var jumpCount = 0
        composeRule.setContent {
            SheetSetTheme {
                ReaderScreen(
                    reader,
                    AppSettings(readerLayout = ReaderLayout.HALF),
                    WindowLayout.COMPACT,
                    SheetSetActions(
                        nextPage = {
                            nextCount++
                            reader = reader.copy(pagePart = 1)
                        },
                        jumpToPage = {
                            jumpCount++
                            reader = reader.copy(pageIndex = it, pagePart = 0)
                        },
                    ),
                )
            }
        }
        slider().performSemanticsAction(SemanticsActions.RequestFocus) { it() }

        composeRule.runOnIdle {
            composeRule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        composeRule.runOnIdle {
            composeRule.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1))
            composeRule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }

        composeRule.runOnIdle {
            assertEquals(1, nextCount)
            assertEquals(0, jumpCount)
            assertEquals(1, reader.pagePart)
        }
    }

    @Test
    fun horizontalSwipesCrossBetweenSetlistPdfsInBothDirections() {
        val first = reader()
        val secondFile = File.createTempFile("reader-workflow-second-", ".pdf", composeRule.activity.cacheDir)
        try {
            source.copyTo(secondFile, overwrite = true)
            val scores = listOf(first.score, first.score.copy(id = "score-2", title = "Second song"))
            val files = listOf(source, secondFile)
            val pageCounts = scores.map(Score::pageCount)
            val scoreIds = scores.map(Score::id)
            val settings = AppSettings(pageFit = PageFit.WIDTH, pageTurnTaps = false, autoHideControls = false)
            var position by mutableStateOf(ReaderPosition(0, 1))
            composeRule.setContent {
                SheetSetTheme {
                    ReaderScreen(
                        first.copy(
                            score = scores[position.scoreIndex],
                            file = files[position.scoreIndex],
                            scoreIds = scoreIds,
                            scoreIndex = position.scoreIndex,
                            pageIndex = position.pageIndex,
                        ),
                        settings,
                        WindowLayout.COMPACT,
                        SheetSetActions(
                            nextPage = { nextPosition(position, pageCounts, it)?.let { next -> position = next } },
                            previousPage = { previousPosition(position, pageCounts, it)?.let { previous -> position = previous } },
                        ),
                    )
                }
            }
            composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG).performTouchInput {
                swipe(center.copy(x = width * 0.8f), center.copy(x = width * 0.2f))
            }
            composeRule.onNodeWithText("Second song").assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(ReaderPosition(1, 0), position) }

            composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG).performTouchInput {
                swipe(center.copy(x = width * 0.2f), center.copy(x = width * 0.8f))
            }
            composeRule.onNodeWithText(first.score.title).assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(ReaderPosition(0, 1), position) }
        } finally {
            secondFile.delete()
        }
    }

    @Test
    fun zoomAndHiddenControlsSurvivePageAndSetlistPdfTurns() {
        val first = reader()
        val second = secondReader(first).copy(scoreIndex = 1)
        try {
            val readers = listOf(first, second)
            val scores = readers.map(ReaderUiState::score)
            val pageCounts = scores.map(Score::pageCount)
            val scoreIds = scores.map(Score::id)
            val catalog = LibraryCatalog(scores = scores)
            val settings = AppSettings(autoHideControls = false)
            var position by mutableStateOf(ReaderPosition(0, 0))
            composeRule.setContent {
                SheetSetTheme {
                    SheetSetApp(
                        LibraryUiState(
                            catalog = catalog,
                            reader = readers[position.scoreIndex].copy(scoreIds = scoreIds, pageIndex = position.pageIndex),
                            settings = settings,
                        ),
                        SheetSetActions(nextPage = { layout ->
                            nextPosition(position, pageCounts, layout)?.let { position = it }
                        }),
                    )
                }
            }

            pinchPdf()
            val zoom = currentPdfZoom()
            assertTrue("The gesture must zoom in before turning pages", zoom > 1f)

            val page = composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG)
            val annotation = composeRule.onNodeWithContentDescription("Annotation")
            annotation.assertIsDisplayed()
            page.performTouchInput { click(center) }
            annotation.assertDoesNotExist()

            page.performTouchInput { click(center.copy(x = width * 0.85f)) }
            composeRule.runOnIdle { assertEquals(ReaderPosition(0, 1), position) }
            assertEquals(zoom, currentPdfZoom(), 0.0001f)
            annotation.assertDoesNotExist()

            page.performTouchInput { click(center.copy(x = width * 0.85f)) }
            composeRule.runOnIdle { assertEquals(ReaderPosition(1, 0), position) }
            assertEquals(zoom, currentPdfZoom(), 0.0001f)
            annotation.assertDoesNotExist()
            composeRule.onNodeWithText(second.score.title).assertDoesNotExist()

            page.performTouchInput { click(center) }
            composeRule.onNodeWithText(second.score.title).assertIsDisplayed()
            annotation.assertIsDisplayed()
        } finally {
            second.file.delete()
        }
    }

    @Test
    fun savedZoomAppliesToNewlyOpenedPdfsUntilReset() {
        val first = reader()
        val second = secondReader(first)
        val context = composeRule.activity
        val preferencesName = "reader-zoom-${UUID.randomUUID()}"
        val store = SettingsStore(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE))
        try {
            store.save(AppSettings(autoHideControls = false))
            val readers = listOf(first, second)
            var state by mutableStateOf(
                LibraryUiState(
                    catalog = LibraryCatalog(scores = readers.map(ReaderUiState::score)),
                    reader = first,
                    settings = store.load(),
                ),
            )
            composeRule.setContent {
                SheetSetTheme {
                    SheetSetApp(
                        state,
                        SheetSetActions(
                            updateSettings = {
                                store.save(it)
                                state = state.copy(settings = it)
                            },
                            closeReader = { state = state.copy(reader = null) },
                            openScore = { score ->
                                state = state.copy(
                                    reader = readers.first { it.score.id == score.id },
                                    settings = store.load(),
                                )
                            },
                        ),
                    )
                }
            }

            assertEquals(1f, currentPdfZoom(), 0.0001f)
            pinchPdf()
            val liveZoom = currentPdfZoom()
            val expectedPercent = (liveZoom * DEFAULT_PDF_ZOOM_PERCENT).roundToInt()
            assertTrue("The gesture must zoom in before saving", expectedPercent > DEFAULT_PDF_ZOOM_PERCENT)

            val pageTools = composeRule.onNodeWithContentDescription(context.getString(R.string.reader_tab_page))
            val closeReader = composeRule.onNodeWithContentDescription(context.getString(R.string.close))
            pageTools.performClick()
            composeRule.onNodeWithText(context.getString(R.string.save_pdf_zoom))
                .performScrollTo().assertIsEnabled().performClick()
            composeRule.runOnIdle { assertEquals(expectedPercent, store.load().defaultZoomPercent) }
            Espresso.pressBack()
            assertEquals(liveZoom, currentPdfZoom(), 0.0001f)
            closeReader.performClick()

            composeRule.onNodeWithText(second.score.title).performClick()
            assertEquals(expectedPercent.toFloat() / DEFAULT_PDF_ZOOM_PERCENT, currentPdfZoom(), 0.0001f)
            pageTools.performClick()
            composeRule.onNodeWithText(context.getString(R.string.reset_pdf_zoom)).performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(DEFAULT_PDF_ZOOM_PERCENT, store.load().defaultZoomPercent) }
            Espresso.pressBack()
            assertEquals(1f, currentPdfZoom(), 0.0001f)
            closeReader.performClick()

            composeRule.onNodeWithText(first.score.title).performClick()
            assertEquals(1f, currentPdfZoom(), 0.0001f)
        } finally {
            context.deleteSharedPreferences(preferencesName)
            second.file.delete()
        }
    }

    @Test
    fun autoHideWaitsUntilSliderDragFinishes() {
        composeRule.setContent {
            SheetSetTheme {
                ReaderScreen(reader(), AppSettings(autoHideControls = true), WindowLayout.COMPACT, SheetSetActions())
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG).performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG).performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(200)
        slider().performTouchInput {
            down(center)
            moveTo(center.copy(x = center.x + 30f))
        }
        composeRule.mainClock.advanceTimeBy(2_000)

        composeRule.onNodeWithContentDescription("Annotation").assertIsDisplayed()
        slider().performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithContentDescription("Annotation").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.onAllNodesWithContentDescription("Annotation").assertCountEquals(0)
    }

    @Test
    fun bookmarkPanelRemainsOpenAfterStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SheetSetTheme {
                ReaderScreen(reader(), AppSettings(), WindowLayout.COMPACT, SheetSetActions())
            }
        }
        composeRule.onNodeWithContentDescription("Bookmark").performClick()
        composeRule.onNodeWithText("Add bookmark").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Add bookmark").assertIsDisplayed()
    }

    @Test
    fun editedTextRetainsIdentityBoundsAndDraftAfterStateRestoration() {
        val initial = TextBoxAnnotation(
            id = "text-1",
            bounds = NormalizedRect(0.2f, 0.2f, 0.7f, 0.4f),
            text = "Before",
            size = AnnotationTextSize.MEDIUM,
        )
        var saved = DocumentAnnotations()
        val state = reader().copy(annotations = DocumentAnnotations(pages = mapOf(0 to listOf(initial))))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SheetSetTheme {
                ReaderScreen(
                    state,
                    AppSettings(),
                    WindowLayout.COMPACT,
                    SheetSetActions(saveAnnotations = { _, _, annotations -> saved = annotations }),
                )
            }
        }
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.runOnIdle {
            requireNotNull(findPdfPage(composeRule.activity.window.decorView)).onSelectionChange(setOf(initial.id))
        }
        composeRule.onNodeWithContentDescription("Edit text").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("After rotation")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNode(hasSetTextAction()).assertTextContains("After rotation")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            val text = saved.pages.getValue(0).single() as TextBoxAnnotation
            assertEquals(initial.id, text.id)
            assertEquals(initial.bounds, text.bounds)
            assertEquals("After rotation", text.text)
        }
    }

    @Test
    fun rejectedTextKeepsDraftAndExistingAnnotations() {
        val existing = List(15) { index ->
            TextBoxAnnotation(
                id = "text-$index",
                bounds = NormalizedRect(0.2f, 0.2f, 0.7f, 0.4f),
                text = "a".repeat(MAX_TEXT_LENGTH),
                size = AnnotationTextSize.MEDIUM,
            )
        }
        val annotations = DocumentAnnotations(pages = mapOf(0 to existing))
        var saves = 0
        composeRule.setContent {
            SheetSetTheme {
                ReaderScreen(
                    reader().copy(annotations = annotations),
                    AppSettings(),
                    WindowLayout.COMPACT,
                    SheetSetActions(saveAnnotations = { _, _, _ -> saves++ }),
                )
            }
        }
        openInlineText()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft remains")

        composeRule.onNodeWithContentDescription("Done").performClick()

        composeRule.onNode(hasSetTextAction()).assertIsDisplayed().assertTextContains("Draft remains")
        composeRule.runOnIdle {
            assertEquals(0, saves)
            assertEquals(existing, annotations.pages.getValue(0))
        }
    }

    @Test
    fun textDraftDoesNotCarryIntoAnotherScoreAfterStateRestoration() {
        val first = reader()
        val second = first.copy(
            score = first.score.copy(id = "score-2", title = "Second song"),
            scoreIds = listOf("score-2"),
        )
        var state = LibraryUiState(
            catalog = LibraryCatalog(scores = listOf(first.score, second.score)),
            reader = first,
        )
        var saved: Pair<String, DocumentAnnotations>? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SheetSetTheme {
                SheetSetApp(
                    state,
                    SheetSetActions(saveAnnotations = { id, _, annotations -> saved = id to annotations }),
                )
            }
        }
        openInlineText()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft for first score")
        // Change only the recreation input, leaving the saved composition on the first score.
        composeRule.runOnIdle { state = state.copy(reader = second) }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        openInlineText()
        composeRule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft for second score")
        composeRule.onNodeWithContentDescription("Done").performClick()
        composeRule.runOnIdle {
            val (scoreId, annotations) = requireNotNull(saved)
            assertEquals(second.score.id, scoreId)
            assertEquals("Draft for second score", (annotations.pages.getValue(0).single() as PlainTextAnnotation).text)
        }
    }

    @Test
    fun textDraftDoesNotCarryIntoAnotherPageAfterStateRestoration() {
        var state = reader()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SheetSetTheme {
                ReaderScreen(state, AppSettings(), WindowLayout.COMPACT, SheetSetActions())
            }
        }
        openInlineText()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft for first page")
        // Change only the recreation input, leaving the saved composition on the first page.
        composeRule.runOnIdle { state = state.copy(pageIndex = 1) }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        openInlineText()
        composeRule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    @Test
    fun pageChangePreservesActiveAnnotationTool() {
        var state by mutableStateOf(reader())
        composeRule.setContent {
            SheetSetTheme {
                Box(Modifier.width(320.dp)) {
                    ReaderScreen(state, AppSettings(), WindowLayout.COMPACT, SheetSetActions())
                }
            }
        }
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.onNodeWithContentDescription("Eraser").assertIsDisplayed().performClick()

        composeRule.runOnIdle { state = state.copy(pageIndex = 1) }

        composeRule.onNodeWithContentDescription("Eraser").assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun hidingEditingToolsKeepsThePdfEditable() {
        composeRule.setContent {
            SheetSetTheme {
                ReaderScreen(reader(), AppSettings(), WindowLayout.COMPACT, SheetSetActions())
            }
        }
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.onNodeWithContentDescription("Eraser").performClick()
        composeRule.onNodeWithText("Hide tools").performClick()
        composeRule.onNodeWithContentDescription("Done").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(ReaderTool.ERASER, findPdfPage(composeRule.activity.window.decorView)?.tool)
        }
        composeRule.onNodeWithText("Show tools").performClick()
        composeRule.onNodeWithContentDescription("Eraser").assertIsSelected()
    }

    @Test
    fun twoPagePreviousIsDisabledAtFirstSpreadUnlessAnotherScorePrecedesIt() {
        var state by mutableStateOf(reader().copy(pageIndex = 1))
        var previousLayout: ReaderLayout? = null
        composeRule.setContent {
            SheetSetTheme {
                ReaderScreen(
                    state,
                    AppSettings(readerLayout = ReaderLayout.TWO_PAGE),
                    WindowLayout.MEDIUM,
                    SheetSetActions(previousPage = { previousLayout = it }),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Previous").assertIsNotEnabled()
        composeRule.runOnIdle {
            state = state.copy(scoreIds = listOf("previous-score", state.score.id), scoreIndex = 1)
        }

        composeRule.onNodeWithContentDescription("Previous").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(ReaderLayout.TWO_PAGE, previousLayout) }
    }

    @Test
    fun eraserRemainsVisibleWhenReenteringAnnotationMode() {
        composeRule.setContent {
            SheetSetTheme {
                Box(Modifier.width(320.dp)) {
                    ReaderScreen(reader(), AppSettings(), WindowLayout.COMPACT, SheetSetActions())
                }
            }
        }
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.onNodeWithContentDescription("Eraser").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Done").performClick()
        composeRule.onNodeWithContentDescription("Annotation").performClick()

        composeRule.onNodeWithContentDescription("Eraser").assertIsDisplayed().assertIsSelected()
    }

    private fun reader(): ReaderUiState {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        return ReaderUiState(score, source, listOf(score.id), 0, 0, annotations = DocumentAnnotations())
    }

    private fun secondReader(first: ReaderUiState): ReaderUiState {
        val file = File.createTempFile("reader-workflow-second-", ".pdf", composeRule.activity.cacheDir)
        first.file.copyTo(file, overwrite = true)
        val score = first.score.copy(id = "score-2", title = "Second song", fileName = "score-2.pdf")
        return first.copy(score = score, file = file, scoreIds = listOf(score.id))
    }

    private fun slider() = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    private fun pinchPdf() {
        composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG).performTouchInput {
            pinch(
                center.copy(x = width * 0.35f), center.copy(x = width * 0.1f),
                center.copy(x = width * 0.65f), center.copy(x = width * 0.9f),
            )
        }
    }

    private fun currentPdfZoom(): Float = composeRule.runOnIdle {
        requireNotNull(findPdfPage(composeRule.activity.window.decorView)).currentViewport.zoom
    }

    private fun openInlineText() {
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.runOnIdle {
                findPdfPage(composeRule.activity.window.decorView)?.displayedPageBounds() != null
            }
        }
        composeRule.onNodeWithContentDescription("Insert text").assertIsEnabled().performClick()
    }

    private fun findPdfPage(view: View): PdfPageView? {
        if (view is PdfPageView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPdfPage(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
