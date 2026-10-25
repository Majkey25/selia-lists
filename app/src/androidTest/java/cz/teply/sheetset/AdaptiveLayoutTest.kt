package cz.teply.sheetset

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.MAX_TITLE_LENGTH
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.data.Setlist
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.ui.SheetSetActions
import cz.teply.sheetset.ui.SheetSetApp
import cz.teply.sheetset.ui.SheetSetTheme
import cz.teply.sheetset.ui.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

@Suppress("DEPRECATION")
class AdaptiveLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandedSetlistsShowsRailAndDetailTogether() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val setlist = Setlist("set-1", "Show", listOf(score.id))
        val state = LibraryUiState(
            catalog = LibraryCatalog(scores = listOf(score), setlists = listOf(setlist)),
        )
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(1000.dp, 800.dp)),
            ) {
                SheetSetTheme {
                    SheetSetApp(
                        state = state,
                        actions = SheetSetActions(),
                        windowLayout = WindowLayout.EXPANDED,
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("PDF navigation").assertIsDisplayed()
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("Show").performClick()

        composeRule.onAllNodesWithText("Show").assertCountEquals(2)
        composeRule.onNodeWithText("Add PDFs").assertIsDisplayed()
    }

    @Test
    fun pdfDestinationSurvivesCollapsingSelectedExpandedSetlist() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val setlist = Setlist("set-1", "Show", listOf(score.id))
        val state = LibraryUiState(
            catalog = LibraryCatalog(scores = listOf(score), setlists = listOf(setlist)),
        )
        var windowLayout by mutableStateOf(WindowLayout.EXPANDED)
        composeRule.setContent {
            val width = when (windowLayout) {
                WindowLayout.COMPACT -> 400.dp
                WindowLayout.MEDIUM -> 700.dp
                WindowLayout.EXPANDED -> 1000.dp
            }
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(width, 800.dp)),
            ) {
                SheetSetTheme {
                    SheetSetApp(state, SheetSetActions(), windowLayout)
                }
            }
        }
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("Show").performClick()
        composeRule.onNodeWithText("Add PDFs").assertIsDisplayed()
        composeRule.onNodeWithText("PDF").performClick()

        for (layout in listOf(WindowLayout.MEDIUM, WindowLayout.COMPACT)) {
            composeRule.runOnIdle { windowLayout = layout }
            composeRule.onNodeWithContentDescription("Search PDFs").assertIsDisplayed()
            composeRule.onAllNodesWithText("Add PDFs").assertCountEquals(0)
        }

        composeRule.runOnIdle { windowLayout = WindowLayout.EXPANDED }
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("Add PDFs").assertIsDisplayed()
    }

    @Test
    fun createSetlistDraftStaysBoundedAfterStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        var created = ""
        restoration.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(),
                    SheetSetActions(createSetlist = { created = it }),
                )
            }
        }
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithContentDescription("Create").performClick()
        composeRule.onNode(hasSetTextAction())
            .performTextReplacement("x".repeat(MAX_TITLE_LENGTH + 1))

        restoration.emulateSavedInstanceStateRestore()

        val expected = "x".repeat(MAX_TITLE_LENGTH)
        composeRule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)),
        )
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle { assertEquals(expected, created) }
    }

    @Test
    fun expandedReaderShowsTwoPageSpread() {
        val score = Score("score-1", "Song", "score-1.pdf", 4, 1L)
        val reader = ReaderUiState(
            score = score,
            file = File("missing.pdf"),
            scoreIds = listOf(score.id),
            scoreIndex = 0,
            pageIndex = 0,
            annotations = DocumentAnnotations(),
        )
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(1000.dp, 800.dp)),
            ) {
                SheetSetTheme {
                    SheetSetApp(
                        state = LibraryUiState(
                            catalog = LibraryCatalog(scores = listOf(score)),
                            reader = reader,
                            settings = AppSettings(readerLayout = ReaderLayout.TWO_PAGE),
                        ),
                        actions = SheetSetActions(),
                        windowLayout = WindowLayout.EXPANDED,
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Page 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Page 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Page").performClick()
        composeRule.onNodeWithText("Two pages").assertIsDisplayed()
    }
}
