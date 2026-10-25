package cz.teply.sheetset

import android.graphics.pdf.PdfDocument
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.data.Setlist
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.ui.SheetSetActions
import cz.teply.sheetset.ui.SheetSetApp
import cz.teply.sheetset.ui.SheetSetTheme
import cz.teply.sheetset.ui.WindowLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class ErrorAndNavigationStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val score = Score("score-1", "Moonlight Sonata", "score-1.pdf", 1, 1L)
    private var state by mutableStateOf(LibraryUiState())
    private var windowLayout by mutableStateOf(WindowLayout.COMPACT)
    private var rootBackCount = 0
    private lateinit var pdfFile: File

    @Before
    fun createPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        pdfFile = File(context.cacheDir, "navigation-${System.nanoTime()}.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            document.finishPage(page)
            pdfFile.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    @After
    fun deletePdf() {
        pdfFile.delete()
    }

    @Test
    fun errorsAreVisibleInHomeAndReader() {
        showApp()
        composeRule.runOnIdle { state = state.copy(error = true) }
        assertErrorVisible()

        composeRule.runOnIdle { state = state.copy(error = false) }
        composeRule.onNodeWithText(score.title).performClick()
        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(error = true) }

        assertErrorVisible()
    }

    @Test
    fun errorsAreVisibleInCompactAndMediumSetlistDetail() {
        showApp(setlists = listOf(Setlist("set-1", "Show", listOf(score.id))))
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("Show").performClick()
        composeRule.onNodeWithText("Add PDFs").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(error = true) }
        assertErrorVisible()

        composeRule.runOnIdle { windowLayout = WindowLayout.MEDIUM }
        composeRule.onNodeWithText("Add PDFs").assertIsDisplayed()
        assertErrorVisible()
    }

    @Test
    fun libraryQuerySurvivesReaderAndClearsOnTabChange() {
        val other = Score("score-2", "Prelude", "score-2.pdf", 1, 2L)
        showApp(scores = listOf(score, other))
        composeRule.onNodeWithContentDescription("Search PDFs").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Moonlight")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithText(score.title).performClick()
        composeRule.onNodeWithContentDescription("Close").performClick()

        composeRule.onNode(hasSetTextAction()).assertTextContains("Moonlight")
        composeRule.onNodeWithText(score.title).assertIsDisplayed()
        composeRule.onAllNodesWithText(other.title).assertCountEquals(0)

        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("PDF").performClick()
        composeRule.onNodeWithContentDescription("Search PDFs").assertIsDisplayed()
        composeRule.onNodeWithText(other.title).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search PDFs").performClick()
        composeRule.onNode(hasSetTextAction()).assertTextContains("")
    }

    @Test
    fun librarySortAndScrollSurviveReader() {
        val scores = List(60) { index ->
            Score(
                "score-$index",
                "Score ${index.toString().padStart(2, '0')}",
                "score-$index.pdf",
                1,
                index.toLong(),
            )
        }
        showApp(scores)
        composeRule.onNodeWithContentDescription("Sort: Imported").performClick()
        composeRule.onNodeWithText("Title").performClick()
        composeRule.onNodeWithContentDescription("Ascending").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Score 30"))
        val previousTop = composeRule.onNodeWithText("Score 30").fetchSemanticsNode().boundsInRoot.top
        composeRule.onNodeWithText("Score 30").performClick()
        composeRule.onNodeWithContentDescription("Close").performClick()

        composeRule.onNodeWithContentDescription("Sort: Title").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Descending").assertIsDisplayed()
        composeRule.onNodeWithText("Score 30").assertIsDisplayed()
        assertEquals(
            previousTop,
            composeRule.onNodeWithText("Score 30").fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
    }

    @Test
    fun backClosesReaderAndStaysInAppAtRoot() {
        showApp()
        Espresso.pressBack()
        composeRule.onNodeWithContentDescription("Search PDFs").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, rootBackCount) }

        composeRule.onNodeWithText(score.title).performClick()
        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.onNodeWithContentDescription("Search PDFs").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, rootBackCount) }

        Espresso.pressBack()
        composeRule.onNodeWithContentDescription("Search PDFs").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, rootBackCount) }
    }

    private fun showApp(scores: List<Score> = listOf(score), setlists: List<Setlist> = emptyList()) {
        state = LibraryUiState(catalog = LibraryCatalog(scores = scores, setlists = setlists))
        composeRule.setContent {
            SheetSetTheme {
                BackHandler { rootBackCount++ }
                SheetSetApp(
                    state = state,
                    actions = SheetSetActions(
                        openScore = { selected ->
                            state = state.copy(
                                reader = ReaderUiState(
                                    score = selected,
                                    file = pdfFile,
                                    scoreIds = listOf(selected.id),
                                    scoreIndex = 0,
                                    pageIndex = 0,
                                    annotations = DocumentAnnotations(),
                                ),
                            )
                        },
                        closeReader = { state = state.copy(reader = null) },
                    ),
                    windowLayout = windowLayout,
                )
            }
        }
    }

    private fun assertErrorVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = context.getString(R.string.action_failed)
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onAllNodesWithText(message).assertCountEquals(1)
    }
}
