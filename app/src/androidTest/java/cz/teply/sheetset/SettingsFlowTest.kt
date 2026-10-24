package cz.teply.sheetset

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.Espresso
import cz.teply.sheetset.pdf.AnnotationEditorSettings
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.settings.ThemeMode
import cz.teply.sheetset.ui.SheetSetActions
import cz.teply.sheetset.ui.SheetSetApp
import cz.teply.sheetset.ui.SheetSetTheme
import cz.teply.sheetset.ui.AppDestination
import cz.teply.sheetset.ui.SettingsDrawer
import cz.teply.sheetset.ui.SettingsSwitchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class SettingsFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun menuGroupsReadingDataAndAppSettings() {
        setSettingsContent()

        composeRule.onNodeWithContentDescription("Menu").performClick()

        listOf("Library", "Reading", "Data", "App").forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        listOf(
            "Reader and page layout",
            "Gestures",
            "Annotation tools",
            "Backup and restore",
        ).forEach { composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Share backup").assertCountEquals(0)
    }

    @Test
    fun appearanceChoiceUpdatesThemeMode() {
        var settings by mutableStateOf(AppSettings())
        var updates = 0
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(settings = settings),
                    SheetSetActions(
                        updateSettings = {
                            settings = it
                            updates++
                        },
                    ),
                )
            }
        }

        openMenuPage("Appearance")
        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        composeRule.onAllNodes(radioRole and hasClickAction(), useUnmergedTree = true)
            .assertCountEquals(2)
        composeRule.onNodeWithText("Light").assertIsSelected()
        composeRule.onNodeWithText("Dark").assert(radioRole).assertIsNotSelected()
            .performClick().assertIsSelected()

        composeRule.runOnIdle {
            assertEquals(ThemeMode.DARK, settings.themeMode)
            assertEquals(1, updates)
        }
    }

    @Test
    fun switchRowHasOneLabeledToggleAction() {
        var checked by mutableStateOf(true)
        var updates = 0
        composeRule.setContent {
            SheetSetTheme {
                SettingsSwitchRow(
                    R.string.keep_screen_awake,
                    R.string.keep_screen_awake_summary,
                    checked,
                ) {
                    checked = it
                    updates++
                }
            }
        }

        composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText("Keep screen awake").assertCountEquals(1)
        composeRule.onNodeWithText("Keep screen awake")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOn().performClick().assertIsOff()
        composeRule.runOnIdle { assertEquals(1, updates) }
    }

    @Test
    fun readerChoicesShowCurrentValueAndOpenOneDialog() {
        setSettingsContent(settings = AppSettings(readerLayout = ReaderLayout.HALF))
        openMenuPage("Reader and page layout")

        composeRule.onNodeWithText("Half page").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Single page").assertIsDisplayed()
        composeRule.onAllNodesWithText("Half page").assertCountEquals(2)
        composeRule.onNodeWithText("Two pages").assertIsDisplayed()
        composeRule.onAllNodesWithText("Width").assertCountEquals(0)
    }

    @Test
    fun readerPageAndLayoutDialogSurviveStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            SheetSetTheme {
                SheetSetApp(LibraryUiState(), SheetSetActions())
            }
        }
        openMenuPage("Reader and page layout")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Single page").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Half page").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Half page").assertIsDisplayed()
        composeRule.onNodeWithText("Two pages").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Single page").assertIsDisplayed()
    }

    @Test
    fun readerAndGestureRowsUpdateStateDirectly() {
        var settings by mutableStateOf(AppSettings())
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(settings = settings),
                    SheetSetActions(updateSettings = { settings = it }),
                )
            }
        }

        openMenuPage("Reader and page layout")
        assertSettingsIcon(R.string.page_layout, R.drawable.ic_view_module_24)
        assertSettingsIcon(R.string.page_fit, R.drawable.ic_straighten_24)
        assertSettingsIcon(R.string.gestures, R.drawable.ic_gesture_24)
        scrollToText("Keep screen awake")
        composeRule.onNodeWithText("Keep screen awake").performClick()
        composeRule.runOnIdle { assertFalse(settings.keepScreenAwake) }
        composeRule.onNodeWithText("Single page").performClick()
        composeRule.onNodeWithText("Two pages").performClick()
        composeRule.runOnIdle { assertEquals(ReaderLayout.TWO_PAGE, settings.readerLayout) }

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Gestures").performClick()
        composeRule.onNodeWithText("Page-turn taps").performClick()
        scrollToText("Palm rejection")
        composeRule.onNodeWithText("Palm rejection").performClick()
        composeRule.runOnIdle {
            assertFalse(settings.pageTurnTaps)
            assertTrue(settings.editor.palmRejection)
        }
    }

    @Test
    fun backupActionsCallDistinctCallbacks() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            SheetSetTheme {
                SettingsDrawer(
                    drawerState = rememberDrawerState(DrawerValue.Open),
                    destination = AppDestination.PDF,
                    settings = AppSettings(),
                    onDestination = {},
                    onSettings = {},
                    onLanguage = {},
                    onBackup = { calls += "create" },
                    onShareBackup = { calls += "share" },
                    onRestore = { calls += "restore" },
                ) {}
            }
        }
        composeRule.onNodeWithText("Backup and restore").performScrollTo().performClick()

        assertSettingsIcon(R.string.create_backup, R.drawable.ic_download_24)
        assertSettingsIcon(R.string.share_backup, R.drawable.ic_share_24)
        assertSettingsIcon(R.string.restore_backup, R.drawable.ic_upload_file_24)

        composeRule.onNodeWithText("Create backup").performClick()
        composeRule.onNodeWithText("Share backup").performClick()
        composeRule.onNodeWithText("Restore backup").performClick()

        composeRule.runOnIdle { assertEquals(listOf("create", "share", "restore"), calls) }
    }

    @Test
    fun annotationVisibilityRejectsEmptyGroupsAndKeepsHiddenToolsRestorable() {
        val defaults = AnnotationEditorSettings.defaults()
        var settings by mutableStateOf(
            AppSettings(
                editor = defaults.copy(
                    presets = defaults.presets.map { it.copy(visible = it.id == "pen-1") },
                    visibleObjectTools = setOf("select"),
                ),
            ),
        )
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(settings = settings),
                    SheetSetActions(updateSettings = { settings = it }),
                )
            }
        }
        openMenuPage("Annotation tools")

        composeRule.onNodeWithTag("visibility-pen-1").performClick()
        composeRule.onNodeWithText("Keep at least one drawing preset visible.").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        scrollToTag("visibility-select")
        composeRule.onNodeWithTag("visibility-select").performClick()
        composeRule.onNodeWithText("Keep at least one object tool visible.").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        scrollToTag("visibility-lasso")
        composeRule.onNodeWithTag("visibility-lasso").performClick()

        composeRule.runOnIdle {
            assertEquals(setOf("select", "lasso"), settings.editor.visibleObjectTools)
            assertTrue(settings.editor.preset("pen-1").visible)
        }
    }

    @Test
    fun annotationOrderAccessibilityActionPersistsMove() {
        var settings by mutableStateOf(AppSettings())
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(
                    LibraryUiState(settings = settings),
                    SheetSetActions(updateSettings = { settings = it }),
                )
            }
        }
        openMenuPage("Annotation tools")
        scrollToTag("reorder-row-pen-1")

        val moveDown = composeRule.onNodeWithTag("reorder-pen-1", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label == "Move down" }
        composeRule.runOnIdle { assertTrue(moveDown.action()) }

        composeRule.runOnIdle {
            assertEquals(listOf("pen-2", "pen-1", "marker", "highlighter"), settings.editor.drawOrder)
        }
    }

    @Test
    fun languagePageCallsLanguageCallback() {
        var language: String? = "unchanged"
        setSettingsContent(actions = SheetSetActions(selectLanguage = { language = it }))
        openMenuPage("Language")

        composeRule.onNodeWithText("Čeština").performClick()

        composeRule.runOnIdle { assertEquals("cs", language) }
    }

    @Test
    fun appDetailsGatesDonationsByChannel() {
        setSettingsContent()
        openMenuPage("App details")

        scrollToText("Version")
        composeRule.onNodeWithText("Version").assertIsDisplayed()
        assertSettingsIcon(R.string.app_version, R.drawable.ic_info_24)
        scrollToText("Privacy policy")
        composeRule.onNodeWithText("Privacy policy").assertIsDisplayed()
        assertSettingsIcon(R.string.privacy_policy, R.drawable.ic_info_24)
        scrollToText("GitHub repository")
        assertSettingsIcon(R.string.github_repository, R.drawable.ic_share_24)
        if (BuildConfig.EXTERNAL_DONATIONS_ENABLED) {
            scrollToText("Support this app → Buy Me a Coffee")
            composeRule.onNodeWithText("Support this app → Buy Me a Coffee")
                .assertIsDisplayed()
        } else {
            composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("app-details-end"))
            composeRule.onNodeWithText("Support this app → Buy Me a Coffee").assertDoesNotExist()
        }
    }

    @Test
    fun backFromPageReturnsToGroupedDrawerThenClosesIt() {
        setSettingsContent()
        openMenuPage("Reader and page layout")
        scrollToText("Keep screen awake")
        composeRule.onNodeWithText("Keep screen awake").assertIsDisplayed()

        Espresso.pressBack()

        composeRule.onNodeWithText("Library").assertIsDisplayed()
        composeRule.onAllNodesWithText("Keep screen awake").assertCountEquals(0)

        Espresso.pressBack()

        composeRule.onAllNodesWithText("Library").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Menu").assertIsDisplayed()
    }

    private fun setSettingsContent(
        settings: AppSettings = AppSettings(),
        actions: SheetSetActions = SheetSetActions(),
    ) {
        composeRule.setContent {
            SheetSetTheme {
                SheetSetApp(LibraryUiState(settings = settings), actions)
            }
        }
    }

    private fun openMenuPage(name: String) {
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(name).performScrollTo().performClick()
        composeRule.waitForIdle()
        val backCount = composeRule.onAllNodesWithContentDescription(
            "Back",
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        val listCount = composeRule.onAllNodes(
            hasTestTag("settings-list"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertTrue("backCount=$backCount listCount=$listCount", backCount > 0 && listCount > 0)
    }

    private fun scrollToText(text: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText(text))
    }

    private fun scrollToTag(tag: String) {
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(tag))
    }

    private fun assertSettingsIcon(label: Int, icon: Int) {
        composeRule.onNodeWithTag("settings-icon:$label:$icon", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
