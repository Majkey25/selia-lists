package cz.teply.sheetset

import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.MAX_TITLE_LENGTH
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.data.Setlist
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.AnnotationTextAlignment
import cz.teply.sheetset.pdf.InkAnnotation
import cz.teply.sheetset.pdf.NormalizedRect
import cz.teply.sheetset.pdf.PageAnnotation
import cz.teply.sheetset.pdf.TextBoxAnnotation
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.AnnotationTextSize
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.settings.ReaderDefaultTool
import cz.teply.sheetset.ui.ColorPanel
import cz.teply.sheetset.ui.COLOR_PANEL_SCROLL_TAG
import cz.teply.sheetset.ui.PDF_PAGE_TEST_TAG
import cz.teply.sheetset.ui.SheetSetActions
import cz.teply.sheetset.ui.SheetSetApp
import cz.teply.sheetset.ui.SetlistDetail
import cz.teply.sheetset.ui.SheetSetTheme
import cz.teply.sheetset.ui.PerformanceToolsSheet
import cz.teply.sheetset.ui.ReaderPanel
import cz.teply.sheetset.ui.WindowLayout
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import androidx.test.platform.app.InstrumentationRegistry

@Suppress("DEPRECATION")
class SheetSetFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shellShowsOnlyPdfAndSetlistTabs() {
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(LibraryUiState(), SheetSetActions())
            }
        }

        composeRule.onNodeWithText("PDF").assertIsDisplayed()
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("No setlists yet").assertIsDisplayed()
    }

    @Test
    fun tabsExposeSelectedState() {
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(LibraryUiState(), SheetSetActions())
            }
        }

        composeRule.onNodeWithText("PDF").assertIsSelected()
        composeRule.onNodeWithText("Setlists").assertIsNotSelected().performClick()
        composeRule.onNodeWithText("PDF").assertIsNotSelected()
        composeRule.onNodeWithText("Setlists").assertIsSelected()
    }

    @Test
    fun emptyLibraryShowsVisibleImportAction() {
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(LibraryUiState(), SheetSetActions())
            }
        }

        composeRule.onNodeWithText("Import PDF").assertIsDisplayed()
    }

    @Test
    fun importSheetShowsOneFilesAndOneSeliaScanRow() {
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(LibraryUiState(), SheetSetActions())
            }
        }

        composeRule.onNodeWithContentDescription("Import PDF").performClick()

        composeRule.onAllNodesWithText("Files").assertCountEquals(1)
        composeRule.onAllNodesWithText("Scan with SeliaScan").assertCountEquals(1)
    }

    @Test
    fun headerUsesMenuAndPdfActionWithoutBrandTitle() {
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(LibraryUiState(), SheetSetActions())
            }
        }

        composeRule.onNodeWithContentDescription("Menu").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Import PDF").assertIsDisplayed()
        composeRule.onAllNodesWithText("SheetSet").assertCountEquals(0)
        composeRule.onAllNodesWithText("Import PDF").assertCountEquals(1)
    }

    @Test
    fun emptySetlistsKeepsCreateOnlyInHeader() {
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(LibraryUiState(), SheetSetActions())
            }
        }

        composeRule.onNodeWithText("Setlists").performClick()

        composeRule.onNodeWithContentDescription("Create").assertIsDisplayed()
        composeRule.onAllNodesWithText("New setlist").assertCountEquals(0)
    }

    @Test
    fun setlistSearchMatchesLabels() {
        val setlist = Setlist("set-1", "Show", labels = listOf("Saturday"))
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(setlists = listOf(setlist))),
                    SheetSetActions(),
                )
            }
        }
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithContentDescription("Search setlists").performClick()
        composeRule.onNodeWithText("Search setlists").performTextInput("saturday")

        composeRule.onNodeWithText("Show").assertIsDisplayed()
    }

    @Test
    fun setlistSearchBoundsAndRestoresQuery() {
        val restorationTester = StateRestorationTester(composeRule)
        val query = "x".repeat(MAX_TITLE_LENGTH)
        restorationTester.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(setlists = listOf(Setlist("set-1", "Show")))),
                    SheetSetActions(),
                )
            }
        }
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithContentDescription("Search setlists").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("$query-extra")
        assertEquals(
            query,
            composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )

        restorationTester.emulateSavedInstanceStateRestore()

        assertEquals(
            query,
            composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
    }

    @Test
    fun searchOpensOnlyWhenRequested() {
        val score = Score("score-1", "Moonlight Sonata", "score-1.pdf", 3, 1L)
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(scores = listOf(score))),
                    SheetSetActions(),
                )
            }
        }

        composeRule.onAllNodesWithText("Search PDFs").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Search PDFs").performClick()
        composeRule.onNodeWithText("Search PDFs").assertIsDisplayed()
        composeRule.onNodeWithText("Search PDFs").performTextInput("missing")
        composeRule.onAllNodesWithText("Moonlight Sonata").assertCountEquals(0)
    }

    @Test
    fun libraryAvoidsDuplicateSectionHeading() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(scores = listOf(score))),
                    SheetSetActions(),
                )
            }
        }

        composeRule.onAllNodesWithText("PDF").assertCountEquals(1)
    }

    @Test
    fun searchClosesWhenLeavingLibrary() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(scores = listOf(score))),
                    SheetSetActions(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Search PDFs").performClick()
        composeRule.onNodeWithText("Search PDFs").assertIsDisplayed()
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("PDF").performClick()

        composeRule.onAllNodesWithText("Search PDFs").assertCountEquals(0)
    }

    @Test
    fun bookmarkSearchOpensExactPage() {
        var openedPage = -1
        val score = Score(
            "score-1",
            "Song",
            "score-1.pdf",
            4,
            1L,
            bookmarks = listOf(cz.teply.sheetset.data.Bookmark("chorus", "Chorus", 2)),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(scores = listOf(score))),
                    SheetSetActions(openScoreAt = { _, page -> openedPage = page }),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Search PDFs").performClick()
        composeRule.onNodeWithText("Search PDFs").performTextInput("chorus")
        composeRule.onNodeWithText("Chorus").performClick()

        composeRule.runOnIdle { assertEquals(2, openedPage) }
    }

    @Test
    fun scoreLabelsCanBeEditedFromRowMenu() {
        var state by mutableStateOf(
            LibraryUiState(
                catalog = LibraryCatalog(
                    scores = listOf(Score("score-1", "Song", "score-1.pdf", 2, 1L)),
                ),
            ),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    state,
                    SheetSetActions(
                        updateScoreLabels = { id, labels ->
                            state = state.copy(catalog = state.catalog.updateScoreLabels(id, labels))
                        },
                    ),
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("More options")[0].performClick()
        composeRule.onNodeWithText("Labels").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Band, Encore")
        composeRule.onNodeWithText("Save").performClick()

        composeRule.runOnIdle { assertEquals(listOf("Band", "Encore"), state.catalog.scores.single().labels) }
        composeRule.onNodeWithText("Band · Encore").assertIsDisplayed()
    }

    @Test
    fun moreThanThreeSetlistsCanBeCreated() {
        var state by mutableStateOf(LibraryUiState())
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    state = state,
                    actions = SheetSetActions(
                        createSetlist = { name ->
                            val number = state.catalog.setlists.size + 1
                            state = state.copy(
                                catalog = state.catalog.createSetlist(name, "set-$number"),
                            )
                        },
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Setlists").performClick()

        repeat(4) { index ->
            composeRule.onNodeWithContentDescription("Create").performClick()
            composeRule.onNodeWithText("Setlist name").performTextInput("Set ${index + 1}")
            composeRule.onNodeWithText("Save").performClick()
        }

        composeRule.onNodeWithText("Set 4").assertIsDisplayed()
    }

    @Test
    fun pdfRowOpensReaderAction() {
        var opened = false
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(scores = listOf(score))),
                    SheetSetActions(openScore = { opened = true }),
                )
            }
        }

        composeRule.onNodeWithText("Song").performClick()

        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun samePdfCanBeAddedToSetlistTwice() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        var state by mutableStateOf(
            LibraryUiState(
                catalog = LibraryCatalog(
                    scores = listOf(score),
                    setlists = listOf(Setlist("set-1", "Show")),
                ),
            ),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    state,
                    SheetSetActions(
                        addScores = { setlistId, scoreIds ->
                            state = state.copy(
                                catalog = scoreIds.fold(state.catalog) { catalog, scoreId ->
                                    catalog.addScoreToSetlist(setlistId, scoreId)
                                },
                            )
                        },
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("Show").performClick()
        composeRule.onNodeWithText("Add PDFs").performClick()
        composeRule.onNodeWithText("Song").performClick()
        composeRule.onNodeWithText("Add").performClick()
        composeRule.onNodeWithText("Add PDFs").performClick()
        composeRule.onAllNodesWithText("Song")[1].performClick()
        composeRule.onNodeWithText("Add").performClick()

        composeRule.onAllNodesWithText("Song").assertCountEquals(2)
    }

    @Test
    fun movingIdenticalOccurrencesDoesNotWaitForPersistence() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val setlist = Setlist("set-1", "Show", listOf(score.id, score.id))
        var reorderCount = 0
        composeRule.setContent {
            SheetSetTheme {
                SetlistDetail(
                    setlist = setlist,
                    scores = listOf(score),
                    actions = SheetSetActions(reorderScores = { _, _ -> reorderCount++ }),
                    busy = false,
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Edit order").performClick()
        val moveDown = composeRule.onAllNodesWithContentDescription("Reorder", useUnmergedTree = true)[0]
            .fetchSemanticsNode().config[SemanticsActions.CustomActions].single()

        composeRule.runOnIdle { assertTrue(moveDown.action()) }

        composeRule.onNodeWithText("Add PDFs").assertIsEnabled()
        composeRule.onNodeWithText("Done").assertIsEnabled()
        composeRule.onAllNodesWithContentDescription("Reorder", useUnmergedTree = true)[0].performTouchInput {
            down(center)
            moveTo(center.copy(y = center.y + height * 1.5f))
            up()
        }

        composeRule.runOnIdle { assertEquals(0, reorderCount) }
        composeRule.onNodeWithText("Add PDFs").assertIsEnabled()
        composeRule.onNodeWithText("Done").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Edit order").assertIsDisplayed()
    }

    @Test
    fun dragUsesCurrentIndexAfterFastRemovalAndRepeatedReorder() {
        val first = Score("score-1", "First song", "score-1.pdf", 2, 1L)
        val second = Score("score-2", "Second song", "score-2.pdf", 2, 2L)
        val third = Score("score-3", "Third song", "score-3.pdf", 2, 3L)
        var catalog by mutableStateOf(
            LibraryCatalog(
                scores = listOf(first, second, third),
                setlists = listOf(Setlist("set-1", "Show", listOf(first.id, second.id, third.id))),
            ),
        )
        composeRule.setContent {
            SheetSetTheme {
                SetlistDetail(
                    setlist = catalog.setlists.single(),
                    scores = catalog.scores,
                    actions = SheetSetActions(
                        removeScore = { id, index -> catalog = catalog.removeScoreFromSetlist(id, index) },
                        reorderScores = { id, ids -> catalog = catalog.reorderScores(id, ids) },
                    ),
                    busy = false,
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Edit order").performClick()
        // Start the handler before its row index changes.
        composeRule.onAllNodesWithContentDescription("Reorder", useUnmergedTree = true)[1]
            .performTouchInput { click(center) }
        composeRule.onAllNodesWithContentDescription("Remove")[0].performClick()

        composeRule.onAllNodesWithContentDescription("Reorder", useUnmergedTree = true)[0].performTouchInput {
            down(center)
            moveTo(center.copy(y = center.y + height * 1.5f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf(third.id, second.id), catalog.setlists.single().scoreIds)
        }
        composeRule.onAllNodesWithText("First song").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Reorder", useUnmergedTree = true)[0].performTouchInput {
            down(center)
            moveTo(center.copy(y = center.y + height * 1.5f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf(second.id, third.id), catalog.setlists.single().scoreIds)
        }
        composeRule.onNodeWithText("Done").assertIsEnabled()
    }

    @Test
    fun failedReorderWithoutBusyEmissionRestoresOrderAndAllowsRetry() {
        val first = Score("score-1", "First song", "score-1.pdf", 2, 1L)
        val second = Score("score-2", "Second song", "score-2.pdf", 2, 2L)
        var catalog by mutableStateOf(
            LibraryCatalog(
                scores = listOf(first, second),
                setlists = listOf(Setlist("set-1", "Show", listOf(first.id, second.id))),
            ),
        )
        var attempts = 0
        composeRule.setContent {
            SheetSetTheme {
                SetlistDetail(
                    setlist = catalog.setlists.single(),
                    scores = catalog.scores,
                    actions = SheetSetActions(reorderScores = { id, ids ->
                        attempts++
                        // The first write fails before loading reaches a rendered frame.
                        if (attempts > 1) catalog = catalog.reorderScores(id, ids)
                    }),
                    busy = false,
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Edit order").performClick()
        composeRule.onAllNodesWithContentDescription("Reorder", useUnmergedTree = true)[0].performTouchInput {
            down(center)
            moveTo(center.copy(y = center.y + height * 1.5f))
            up()
        }

        composeRule.runOnIdle { assertEquals(1, attempts) }
        composeRule.onNodeWithText("Add PDFs").assertIsEnabled()
        composeRule.onNodeWithText("Done").assertIsEnabled()
        assertTrue(
            composeRule.onNodeWithText(first.title).fetchSemanticsNode().boundsInRoot.top <
                composeRule.onNodeWithText(second.title).fetchSemanticsNode().boundsInRoot.top,
        )
        composeRule.onAllNodesWithContentDescription("Reorder", useUnmergedTree = true)[0].performTouchInput {
            down(center)
            moveTo(center.copy(y = center.y + height * 1.5f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(listOf(second.id, first.id), catalog.setlists.single().scoreIds)
        }
        composeRule.onNodeWithText("Done").assertIsEnabled()
    }

    @Test
    fun dragTargetUsesMeasuredRowHeightIncludingDivider() {
        val scores = (1..3).map { index -> Score("score-$index", "Song $index", "score-$index.pdf", 2, 1L) }
        val setlist = Setlist("set-1", "Show", scores.map(Score::id))
        var reordered = emptyList<String>()
        var touchSlop = 0f
        composeRule.setContent {
            SheetSetTheme {
                touchSlop = LocalViewConfiguration.current.touchSlop
                SetlistDetail(
                    setlist = setlist,
                    scores = scores,
                    actions = SheetSetActions(reorderScores = { _, ids -> reordered = ids }),
                    busy = false,
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Edit order").performClick()
        val first = composeRule.onNodeWithText(scores[0].title).fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithText(scores[1].title).fetchSemanticsNode().boundsInRoot
        val distance = (second.center.y - first.center.y) * 1.5f - 1f

        composeRule.onAllNodesWithContentDescription("Reorder", useUnmergedTree = true)[0].performTouchInput {
            down(center)
            moveTo(center.copy(y = center.y + touchSlop + distance))
            up()
        }

        composeRule.runOnIdle { assertEquals(listOf(scores[1].id, scores[0].id, scores[2].id), reordered) }
    }

    @Test
    fun addPdfDialogRestoresSelectionAndExcludesDeletedScores() {
        val restorationTester = StateRestorationTester(composeRule)
        val first = Score("score-1", "First song", "score-1.pdf", 2, 1L)
        val second = Score("score-2", "Second song", "score-2.pdf", 2, 2L)
        var scores by mutableStateOf(listOf(first, second))
        var added = emptyList<String>()
        restorationTester.setContent {
            SheetSetTheme {
                SetlistDetail(
                    setlist = Setlist("set-1", "Show"),
                    scores = scores,
                    actions = SheetSetActions(addScores = { _, ids -> added = ids }),
                    busy = false,
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Add PDFs").performClick()
        composeRule.onNodeWithText("First song").performClick()
        composeRule.onNodeWithText("Second song").performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On),
            useUnmergedTree = true,
        ).assertCountEquals(2)
        composeRule.onNodeWithText("Add").assertIsEnabled()
        composeRule.runOnIdle { scores = listOf(first) }
        composeRule.onAllNodesWithText("Second song").assertCountEquals(0)
        composeRule.onNodeWithText("Add").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf(first.id), added) }
    }

    @Test
    fun addPdfDialogExposesOneNamedCheckedCheckbox() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        var added = emptyList<String>()
        composeRule.setContent {
            SheetSetTheme {
                SetlistDetail(
                    setlist = Setlist("set-1", "Show"),
                    scores = listOf(score),
                    actions = SheetSetActions(addScores = { _, ids -> added = ids }),
                    busy = false,
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Add PDFs").performClick()
        val checkbox = composeRule.onNode(
            hasText(score.title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
        )
        checkbox.assertIsOff().performClick()
        checkbox.assertIsOn()
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox),
        ).assertCountEquals(1)
        checkbox.performClick().assertIsOff().performClick()
        composeRule.onNodeWithText("Add").performClick()

        composeRule.runOnIdle { assertEquals(listOf(score.id), added) }
    }

    @Test
    fun setlistEditingControlsStayOutOfBrowseMode() {
        val first = Score("score-1", "First song", "score-1.pdf", 2, 1L)
        val second = Score("score-2", "Second song", "score-2.pdf", 2, 2L)
        val setlist = Setlist("set-1", "Show", listOf(first.id, second.id))
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(
                        catalog = LibraryCatalog(
                            scores = listOf(first, second),
                            setlists = listOf(setlist),
                        ),
                    ),
                    SheetSetActions(),
                )
            }
        }

        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("Show").performClick()
        composeRule.onAllNodesWithContentDescription("Remove").assertCountEquals(0)
        composeRule.onNodeWithText("Edit order").performClick()
        composeRule.onAllNodesWithContentDescription("Reorder").assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("Move up").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Move down").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Remove").assertCountEquals(2)
    }

    @Test
    fun setlistEditorUsesIconsInsteadOfTextGlyphs() {
        val first = Score("score-1", "First song", "score-1.pdf", 2, 1L)
        val second = Score("score-2", "Second song", "score-2.pdf", 2, 2L)
        val setlist = Setlist("set-1", "Show", listOf(first.id, second.id))
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(
                        catalog = LibraryCatalog(
                            scores = listOf(first, second),
                            setlists = listOf(setlist),
                        ),
                    ),
                    SheetSetActions(),
                )
            }
        }

        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithText("Show").performClick()
        composeRule.onNodeWithText("Edit order").performClick()

        listOf("‹", "↑", "↓", "×").forEach { glyph ->
            composeRule.onAllNodesWithText(glyph).assertCountEquals(0)
        }
    }

    @Test
    fun readerOpensAnnotationTools() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val reader = ReaderUiState(
            score = score,
            file = File("missing.pdf"),
            scoreIds = listOf(score.id),
            scoreIndex = 0,
            pageIndex = 0,
            annotations = DocumentAnnotations(),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(
                        catalog = LibraryCatalog(scores = listOf(score)),
                        reader = reader,
                    ),
                    SheetSetActions(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Export").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Annotation").performClick()

        composeRule.onNodeWithContentDescription("Objects").assertIsDisplayed()
        listOf("Pen", "Highlighter", "Eraser", "Color", "Done")
            .forEach { label ->
                composeRule.onAllNodesWithContentDescription(label).assertCountEquals(1)
            }
        listOf("Pen 2", "Marker", "Select", "Lasso").forEach { label ->
            composeRule.onAllNodesWithContentDescription(label).assertCountEquals(0)
        }
        listOf(
            "Black",
            "Red",
            "Orange",
            "Yellow",
            "Green",
            "Blue",
            "Purple",
            "Pink",
        ).forEach { label ->
            composeRule.onAllNodesWithContentDescription(label).assertCountEquals(0)
        }

        composeRule.onNodeWithContentDescription("Pen").assertIsSelected()
        composeRule.onNodeWithContentDescription("Straight line").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Straight line").performClick()
        composeRule.onNodeWithContentDescription("Straight line").assertIsSelected()

        composeRule.onNodeWithContentDescription("Eraser").performClick()
        composeRule.onAllNodesWithContentDescription("Color").assertCountEquals(0)
    }

    @Test
    fun selectedTextShowsObjectActionsInsteadOfDrawingProperties() {
        val text = sampleText()
        setReaderContent(textAnnotation = text, file = onePagePdf())

        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.onNodeWithContentDescription("Objects").performClick()
        composeRule.onNodeWithContentDescription("Select").assertIsSelected()
        waitForPdfPage()
        composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG).performTouchInput {
            click(center)
        }

        composeRule.onNodeWithContentDescription("Edit text").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Duplicate").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete annotation").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Straight line").assertCountEquals(0)

        composeRule.onNodeWithContentDescription("Edit text").performClick()
        composeRule.onAllNodesWithText("Line height").assertCountEquals(0)
        composeRule.onNodeWithText("More options").performClick()
        composeRule.onNodeWithText("Line height").assertIsDisplayed()
        composeRule.onNodeWithText("Alignment").assertIsDisplayed()
    }

    @Test
    fun compactToolbarShowsOnePersistentColorControl() {
        setReaderContent()
        composeRule.onNodeWithContentDescription("Annotation").performClick()

        assertToolbarHasOneColorControl()
    }

    @Test
    fun expandedToolbarShowsOnePersistentColorControl() {
        setReaderContent(windowLayout = WindowLayout.EXPANDED)
        composeRule.onNodeWithContentDescription("Annotation").performClick()

        assertToolbarHasOneColorControl()
    }

    @Test
    fun doneThenAnnotateRestoresTheLastEditorTool() {
        setReaderContent()
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.onNodeWithContentDescription("Highlighter").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Done").performClick()

        composeRule.onNodeWithContentDescription("Annotation").performClick()

        composeRule.onNodeWithContentDescription("Objects").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Highlighter").assertIsSelected()
    }

    @Test
    fun completedStrokeSurvivesTextDoneAndToolsTransition() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        setReaderContent(
            file = onePagePdf(),
            onSaveAnnotations = { scoreId, pageIndex, annotations ->
                events += if (annotations.pages[pageIndex]?.singleOrNull() is InkAnnotation) {
                    "save:$scoreId:$pageIndex:ink"
                } else {
                    "save:$scoreId:$pageIndex:unexpected"
                }
            },
        )
        composeRule.onNodeWithContentDescription("Annotation").performClick()
        composeRule.onNodeWithContentDescription("Pen").performClick()

        waitForPdfPage()
        composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG).performTouchInput {
            down(center.copy(x = center.x - 40f))
            moveTo(center.copy(x = center.x + 40f))
            up()
        }
        events += "objects-start"
        composeRule.onNodeWithContentDescription("Objects").performClick()
        events += "objects"
        composeRule.onNodeWithContentDescription("Text box").performClick()
        events += "text-tool"
        waitForPdfPage()
        composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG).performTouchInput { click(center) }
        events += "text-open"
        composeRule.onNodeWithText("Cancel").performClick()
        events += "text-cancel"
        composeRule.onNodeWithContentDescription("Done").performClick()
        events += "done"
        composeRule.onNodeWithContentDescription("Tools").performClick()
        events += "tools"

        composeRule.onNodeWithText("Export").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    "save:score-1:0:ink",
                    "objects-start",
                    "objects",
                    "text-tool",
                    "text-open",
                    "text-cancel",
                    "done",
                    "tools",
                ),
                events.toList(),
            )
        }
    }

    @Test
    fun hiddenDefaultSelectUsesFirstVisibleObjectInPersistedOrder() {
        val defaults = AppSettings().editor
        setReaderContent(
            settings = AppSettings(
                defaultTool = ReaderDefaultTool.VIEW,
                editor = defaults.copy(
                    objectOrder = defaults.objectOrder.reversed(),
                    visibleObjectTools = setOf("ellipse", "lasso"),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription("Annotation").performClick()

        composeRule.onNodeWithContentDescription("Draw").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ellipse").assertIsSelected()
        composeRule.onAllNodesWithContentDescription("Select").assertCountEquals(0)
    }

    @Test
    fun hiddenDefaultHighlighterUsesFirstVisibleMatchingPreset() {
        val defaults = AppSettings().editor
        setReaderContent(
            settings = AppSettings(
                defaultTool = ReaderDefaultTool.HIGHLIGHTER,
                editor = defaults.copy(
                    drawOrder = listOf("pen-2", "pen-1", "marker", "highlighter"),
                    presets = defaults.presets.map { preset ->
                        preset.copy(visible = preset.id in setOf("pen-1", "pen-2"))
                    },
                ),
            ),
        )

        composeRule.onNodeWithContentDescription("Annotation").performClick()

        composeRule.onNodeWithContentDescription("Objects").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pen 2").assertIsSelected()
        composeRule.onAllNodesWithContentDescription("Highlighter").assertCountEquals(0)
    }

    @Test
    fun strokeWidthUsesOneSixStopSlider() {
        val updates = mutableListOf<AppSettings>()
        setReaderContent(onSettings = updates::add)
        composeRule.onNodeWithContentDescription("Annotation").performClick()

        composeRule.onAllNodesWithContentDescription("Decrease stroke width").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Increase stroke width").assertCountEquals(0)
        composeRule.onNodeWithTag("stroke-width-preview").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stroke width")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(6f)
            }

        composeRule.runOnIdle {
            assertEquals(6, updates.last().editor.preset("pen-1").width)
        }
    }

    private fun assertToolbarHasOneColorControl() {

        composeRule.onAllNodesWithContentDescription("Color").assertCountEquals(1)
        listOf("Black", "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Pink").forEach {
            composeRule.onAllNodesWithContentDescription(it).assertCountEquals(0)
        }
    }

    @Test
    fun colorPanelShowsQuickRecentCustomOpacityAndEyedropper() {
        val editor = AppSettings().editor
        composeRule.setContent {
            SheetSetTheme {
                ColorPanel(
                    selected = AnnotationColor.BLACK,
                    opacity = 255,
                    quickColors = editor.quickColors,
                    recentColors = listOf(AnnotationColor(0xFF123456.toInt())),
                    onDismiss = {},
                    onEyedropper = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        listOf("Black", "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Pink")
            .forEach { composeRule.onAllNodesWithContentDescription(it).assertCountEquals(1) }
        composeRule.onAllNodesWithContentDescription("Custom color #123456").assertCountEquals(1)
        listOf("Recent colors", "Custom color", "Opacity", "Eyedropper").forEach { label ->
            composeRule.onNodeWithTag(COLOR_PANEL_SCROLL_TAG).performScrollToNode(hasText(label))
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onAllNodesWithText("Hue").assertCountEquals(0)
        composeRule.onNodeWithText("Custom color").performClick()
        composeRule.onNodeWithTag(COLOR_PANEL_SCROLL_TAG).performScrollToNode(hasTestTag("color-spectrum"))
        composeRule.onNodeWithTag("color-spectrum").assertIsDisplayed()
        listOf("Hue", "HEX").forEach { label ->
            composeRule.onNodeWithTag(COLOR_PANEL_SCROLL_TAG).performScrollToNode(hasText(label))
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onNode(hasSetTextAction() and hasText("#111111")).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("#12ABEF")
        composeRule.onNode(hasSetTextAction()).assertTextContains("#12ABEF")
        composeRule.onAllNodesWithText("#12ABEF").assertCountEquals(2)
        composeRule.onNodeWithText("Apply").assertIsEnabled()
        composeRule.onAllNodesWithText("Saturation").assertCountEquals(0)
        composeRule.onAllNodesWithText("Brightness").assertCountEquals(0)
    }

    @Test
    fun performanceToolsChangeLayoutAndJumpToBookmark() {
        var settings by mutableStateOf(AppSettings())
        var jumpedTo = -1
        val score = Score(
            "score-1",
            "Song",
            "score-1.pdf",
            4,
            1L,
            bookmarks = listOf(cz.teply.sheetset.data.Bookmark("chorus", "Chorus", 2)),
        )
        val reader = ReaderUiState(
            score = score,
            file = File("missing.pdf"),
            scoreIds = listOf(score.id),
            scoreIndex = 0,
            pageIndex = 0,
            annotations = DocumentAnnotations(),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(
                        catalog = LibraryCatalog(scores = listOf(score)),
                        reader = reader,
                        settings = settings,
                    ),
                    SheetSetActions(
                        updateSettings = { settings = it },
                        jumpToPage = { jumpedTo = it },
                    ),
                )
            }
        }

        listOf("Bookmark", "Page", "Gesture", "Tools", "Annotation").forEach {
            composeRule.onNodeWithContentDescription(it).assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription("Bookmark").performClick()
        composeRule.onNodeWithText("Chorus").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(2, jumpedTo) }
        composeRule.onNodeWithContentDescription("Page").performClick()
        composeRule.onNodeWithText("Single page").assertIsDisplayed()
        composeRule.onNodeWithText("Half page").performClick()
        composeRule.runOnIdle { assertEquals(ReaderLayout.HALF, settings.readerLayout) }
        composeRule.onAllNodesWithText("Two pages").assertCountEquals(0)
    }

    @Test
    fun bookmarkCanBeAddedFromCurrentPage() {
        var title = ""
        val score = Score("score-1", "Song", "score-1.pdf", 4, 1L)
        val reader = ReaderUiState(
            score = score,
            file = File("missing.pdf"),
            scoreIds = listOf(score.id),
            scoreIndex = 0,
            pageIndex = 1,
            annotations = DocumentAnnotations(),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(scores = listOf(score)), reader = reader),
                    SheetSetActions(addBookmark = { title = it }),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Bookmark").performClick()
        composeRule.onNodeWithText("Add bookmark").performClick()
        composeRule.onNodeWithText("Bookmark title").performTextInput("Verse")
        composeRule.onNodeWithText("Save").performClick()

        composeRule.runOnIdle { assertEquals("Verse", title) }
    }

    @Test
    fun performanceToolsDoNotExposeAutomaticScrolling() {
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
            SheetSetTheme {
                PerformanceToolsSheet(
                    section = ReaderPanel.TOOLS,
                    reader = reader,
                    settings = AppSettings(),
                    windowLayout = WindowLayout.COMPACT,
                    onSettings = {},
                    onJump = {},
                    onAddBookmark = {},
                    onRenameBookmark = { _, _ -> },
                    onDeleteBookmark = {},
                    onExport = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Automatic scrolling").assertCountEquals(0)
        composeRule.onAllNodesWithText("Scroll speed").assertCountEquals(0)
    }

    @Test
    fun readerUsesIconsInsteadOfTextGlyphs() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val reader = ReaderUiState(
            score = score,
            file = File("missing.pdf"),
            scoreIds = listOf(score.id),
            scoreIndex = 0,
            pageIndex = 0,
            annotations = DocumentAnnotations(),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(
                        catalog = LibraryCatalog(scores = listOf(score)),
                        reader = reader,
                    ),
                    SheetSetActions(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Annotation").performClick()

        listOf("×", "⇩", "‹", "›", "✎", "▰", "⌫", "↶", "↷", "✓").forEach { glyph ->
            composeRule.onAllNodesWithText(glyph).assertCountEquals(0)
        }
    }

    @Test
    fun systemBackClosesReader() {
        var closed = false
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val reader = ReaderUiState(
            score = score,
            file = File("missing.pdf"),
            scoreIds = listOf(score.id),
            scoreIndex = 0,
            pageIndex = 0,
            annotations = DocumentAnnotations(),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(
                        catalog = LibraryCatalog(scores = listOf(score)),
                        reader = reader,
                    ),
                    SheetSetActions(closeReader = { closed = true }),
                )
            }
        }

        Espresso.pressBack()

        composeRule.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun readerPreviewHandlesPedalBeforeFocusedControls() {
        var layout: ReaderLayout? = null
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val reader = ReaderUiState(
            score = score,
            file = File("missing.pdf"),
            scoreIds = listOf(score.id),
            scoreIndex = 0,
            pageIndex = 0,
            annotations = DocumentAnnotations(),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(catalog = LibraryCatalog(scores = listOf(score)), reader = reader),
                    SheetSetActions(nextPage = { layout = it }),
                )
            }
        }

        composeRule.onRoot().performKeyInput {
            keyDown(Key.PageDown)
            keyUp(Key.PageDown)
        }

        composeRule.runOnIdle { assertEquals(ReaderLayout.SINGLE, layout) }
    }

    @Test
    fun readerPageTapTogglesControls() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val reader = ReaderUiState(
            score = score,
            file = File("missing.pdf"),
            scoreIds = listOf(score.id),
            scoreIndex = 0,
            pageIndex = 0,
            annotations = DocumentAnnotations(),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(
                        catalog = LibraryCatalog(scores = listOf(score)),
                        reader = reader,
                    ),
                    SheetSetActions(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Annotation").assertIsDisplayed()
        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.onAllNodesWithContentDescription("Annotation").assertCountEquals(0)
        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.onNodeWithContentDescription("Annotation").assertIsDisplayed()
    }

    @Test
    fun readerAutoHidesControlsAfterManualReveal() {
        val score = Score("score-1", "Song", "score-1.pdf", 2, 1L)
        val reader = ReaderUiState(
            score = score,
            file = File("missing.pdf"),
            scoreIds = listOf(score.id),
            scoreIndex = 0,
            pageIndex = 0,
            annotations = DocumentAnnotations(),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(
                        catalog = LibraryCatalog(scores = listOf(score)),
                        reader = reader,
                    ),
                    SheetSetActions(),
                )
            }
        }

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onRoot().performTouchInput { click(center) }
            composeRule.mainClock.advanceTimeBy(200)
            composeRule.onAllNodesWithContentDescription("Annotation").assertCountEquals(0)
            composeRule.onRoot().performTouchInput { click(center) }
            composeRule.mainClock.advanceTimeBy(200)
            composeRule.onNodeWithContentDescription("Annotation").assertIsDisplayed()
            composeRule.mainClock.advanceTimeBy(1_200)
            composeRule.onNodeWithContentDescription("Annotation").assertIsDisplayed()
            composeRule.mainClock.advanceTimeBy(300)
            composeRule.onAllNodesWithContentDescription("Annotation").assertCountEquals(0)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun cancelledSetlistNameIsCleared() {
        composeRule.setContent {
            SheetSetTheme { SheetSetApp(LibraryUiState(), SheetSetActions()) }
        }
        composeRule.onNodeWithText("Setlists").performClick()
        composeRule.onNodeWithContentDescription("Create").performClick()
        composeRule.onNodeWithText("Setlist name").performTextInput("Old name")
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithContentDescription("Create").performClick()

        composeRule.onAllNodesWithText("Old name").assertCountEquals(0)
    }

    private fun sampleText(): TextBoxAnnotation = TextBoxAnnotation(
        id = "text",
        bounds = NormalizedRect(0.4f, 0.4f, 0.6f, 0.6f),
        text = "rit.",
        size = AnnotationTextSize.MEDIUM,
        lineHeight = 1.2f,
        alignment = AnnotationTextAlignment.CENTER,
        color = AnnotationColor.BLACK,
        opacity = 255,
    )

    private fun waitForPdfPage() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(PDF_PAGE_TEST_TAG).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG).assertIsDisplayed()
        composeRule.waitForIdle()
    }

    private fun setReaderContent(
        textAnnotation: TextBoxAnnotation? = null,
        settings: AppSettings = AppSettings(),
        windowLayout: WindowLayout = WindowLayout.COMPACT,
        file: File = File("missing.pdf"),
        onSettings: (AppSettings) -> Unit = {},
        onSaveAnnotations: (String, Int, DocumentAnnotations) -> Unit = { _, _, _ -> },
    ) {
        val score = Score("score-1", "Song", "score-1.pdf", 1, 1L)
        val annotations = textAnnotation?.let { DocumentAnnotations(mapOf(0 to listOf(it))) }
            ?: DocumentAnnotations()
        var currentSettings by mutableStateOf(settings)
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    state = LibraryUiState(
                        catalog = LibraryCatalog(scores = listOf(score)),
                        reader = ReaderUiState(
                            score = score,
                            file = file,
                            scoreIds = listOf(score.id),
                            scoreIndex = 0,
                            pageIndex = 0,
                            annotations = annotations,
                        ),
                        settings = currentSettings,
                    ),
                    actions = SheetSetActions(
                        saveAnnotations = onSaveAnnotations,
                        updateSettings = {
                            currentSettings = it
                            onSettings(it)
                        },
                    ),
                    windowLayout = windowLayout,
                )
            }
        }
    }

    private fun onePagePdf(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "sheetset-flow-${System.nanoTime()}.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            page.canvas.drawColor(AndroidColor.WHITE)
            document.finishPage(page)
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }
}
