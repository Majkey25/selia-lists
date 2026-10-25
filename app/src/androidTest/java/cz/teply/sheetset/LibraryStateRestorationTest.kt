package cz.teply.sheetset

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.ui.LibraryScreen
import cz.teply.sheetset.ui.SheetSetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryStateRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)

    @Test
    fun renameDialogRestoresDraftAndScoreTarget() {
        var renamed: Pair<String, String>? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SheetSetTheme {
                LibraryScreen(
                    scores = listOf(score),
                    onOpen = {},
                    onOpenBookmark = { _, _ -> },
                    onRename = { id, title -> renamed = id to title },
                    onDelete = {},
                    onLabels = { _, _ -> },
                    searching = false,
                    onSearchingChange = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Rename").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Encore draft")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Rename PDF").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertTextContains("Encore draft")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle { assertEquals(score.id to "Encore draft", renamed) }
    }

    @Test
    fun labelsDialogRestoresDraftAndScoreTarget() {
        var savedLabels: Pair<String, List<String>>? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SheetSetTheme {
                LibraryScreen(
                    scores = listOf(score),
                    onOpen = {},
                    onOpenBookmark = { _, _ -> },
                    onRename = { _, _ -> },
                    onDelete = {},
                    onLabels = { id, labels -> savedLabels = id to labels },
                    searching = false,
                    onSearchingChange = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Labels").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Band,Encore")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNode(hasSetTextAction()).assertTextContains("Band,Encore")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            assertEquals(score.id to listOf("Band", "Encore"), savedLabels)
        }
    }
}
