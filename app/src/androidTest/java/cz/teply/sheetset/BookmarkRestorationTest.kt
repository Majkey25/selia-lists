package cz.teply.sheetset

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import cz.teply.sheetset.data.Bookmark
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.ui.SheetSetActions
import cz.teply.sheetset.ui.SheetSetApp
import cz.teply.sheetset.ui.SheetSetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class BookmarkRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val score = Score(
        "score-1",
        "Song",
        "score-1.pdf",
        3,
        1L,
        bookmarks = listOf(Bookmark("verse", "Chorus", 0), Bookmark("chorus", "Chorus", 2)),
    )
    private val reader = ReaderUiState(
        score = score,
        file = File("missing.pdf"),
        scoreIds = listOf(score.id),
        scoreIndex = 0,
        pageIndex = 1,
        annotations = DocumentAnnotations(),
    )

    @Test
    fun addBookmarkRestoresDraftAndStartsNextDialogEmpty() {
        var title = ""
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(scores = listOf(score)), reader = reader),
                    SheetSetActions(addBookmark = { title = it }),
                )
            }
        }
        composeRule.onNodeWithContentDescription("Bookmark").performClick()
        composeRule.onNodeWithText("Add bookmark").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Verse draft")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Bookmark title").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertTextContains("Verse draft")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle { assertEquals("Verse draft", title) }

        composeRule.onNodeWithText("Add bookmark").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Next draft")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle { assertEquals("Next draft", title) }
    }

    @Test
    fun renameBookmarkRestoresDraftAndExactTarget() {
        var renamed: Pair<String, String>? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(scores = listOf(score)), reader = reader),
                    SheetSetActions(renameBookmark = { id, title -> renamed = id to title }),
                )
            }
        }
        composeRule.onNodeWithContentDescription("Bookmark").performClick()
        composeRule.onAllNodesWithContentDescription("Rename")[1].performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Encore draft")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNode(hasSetTextAction()).assertTextContains("Encore draft")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle { assertEquals("chorus" to "Encore draft", renamed) }

        composeRule.onAllNodesWithContentDescription("Rename")[0].performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).assertTextContains("Chorus")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle { assertEquals("verse" to "Chorus", renamed) }
    }
}
