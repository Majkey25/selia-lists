package cz.teply.sheetset

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.MAX_TEXT_LENGTH
import cz.teply.sheetset.pdf.NormalizedRect
import cz.teply.sheetset.pdf.PdfPageView
import cz.teply.sheetset.pdf.TextBoxAnnotation
import cz.teply.sheetset.settings.AnnotationTextSize
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.ui.PDF_PAGE_TEST_TAG
import cz.teply.sheetset.ui.ReaderScreen
import cz.teply.sheetset.ui.SheetSetActions
import cz.teply.sheetset.ui.SheetSetApp
import cz.teply.sheetset.ui.SheetSetTheme
import cz.teply.sheetset.ui.WindowLayout
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
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
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.runOnIdle {
            requireNotNull(findPdfPage(composeRule.activity.window.decorView))
                .onRequestText(NormalizedRect(0.2f, 0.2f, 0.7f, 0.4f))
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft remains")

        composeRule.onNodeWithText("Save").performClick()

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
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.runOnIdle {
            requireNotNull(findPdfPage(composeRule.activity.window.decorView))
                .onRequestText(NormalizedRect(0.2f, 0.2f, 0.7f, 0.4f))
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft for first score")
        // Change only the recreation input, leaving the saved composition on the first score.
        composeRule.runOnIdle { state = state.copy(reader = second) }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.runOnIdle {
            requireNotNull(findPdfPage(composeRule.activity.window.decorView))
                .onRequestText(NormalizedRect(0.2f, 0.2f, 0.7f, 0.4f))
        }
        composeRule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft for second score")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            val (scoreId, annotations) = requireNotNull(saved)
            assertEquals(second.score.id, scoreId)
            assertEquals("Draft for second score", (annotations.pages.getValue(0).single() as TextBoxAnnotation).text)
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
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.runOnIdle {
            requireNotNull(findPdfPage(composeRule.activity.window.decorView))
                .onRequestText(NormalizedRect(0.2f, 0.2f, 0.7f, 0.4f))
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft for first page")
        // Change only the recreation input, leaving the saved composition on the first page.
        composeRule.runOnIdle { state = state.copy(pageIndex = 1) }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.runOnIdle {
            requireNotNull(findPdfPage(composeRule.activity.window.decorView))
                .onRequestText(NormalizedRect(0.2f, 0.2f, 0.7f, 0.4f))
        }
        composeRule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    @Test
    fun pageChangePreservesActiveAnnotationTool() {
        var state by mutableStateOf(reader())
        composeRule.setContent {
            SheetSetTheme {
                ReaderScreen(state, AppSettings(), WindowLayout.COMPACT, SheetSetActions())
            }
        }
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.onNodeWithContentDescription("Eraser").performClick()

        composeRule.runOnIdle { state = state.copy(pageIndex = 1) }

        composeRule.onNodeWithContentDescription("Eraser").assertIsDisplayed().assertIsSelected()
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
                ReaderScreen(reader(), AppSettings(), WindowLayout.COMPACT, SheetSetActions())
            }
        }
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.onNodeWithContentDescription("Eraser").performClick()
        composeRule.onNodeWithContentDescription("Done").performClick()
        composeRule.onNodeWithContentDescription("Annotation").performClick()

        composeRule.onNodeWithContentDescription("Eraser").assertIsDisplayed().assertIsSelected()
    }

    private fun reader(): ReaderUiState {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        return ReaderUiState(score, source, listOf(score.id), 0, 0, annotations = DocumentAnnotations())
    }

    private fun slider() = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

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
