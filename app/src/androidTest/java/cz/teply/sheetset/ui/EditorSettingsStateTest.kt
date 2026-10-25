package cz.teply.sheetset.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.AnnotationTextAlignment
import cz.teply.sheetset.pdf.NormalizedRect
import cz.teply.sheetset.pdf.TextBoxAnnotation
import cz.teply.sheetset.settings.AnnotationTextSize
import cz.teply.sheetset.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorSettingsStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun repeatedDragsUseCurrentOrderAndKeepNewSettings() {
        var settings by mutableStateOf(AppSettings())
        composeRule.setContent {
            SheetSetTheme {
                AnnotationSettings(settings, onBack = {}, onSettings = { settings = it })
            }
        }
        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasTestTag("reorder-row-pen-1"))
        composeRule.onNodeWithTag("reorder-pen-1", useUnmergedTree = true)
            .performTouchInput { click() }
        composeRule.onNodeWithTag("visibility-pen-2").performClick()

        composeRule.onNodeWithTag("reorder-pen-1", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, height * 1.5f), durationMillis = 500)
        }
        composeRule.runOnIdle {
            assertEquals(listOf("pen-2", "pen-1", "marker", "highlighter"), settings.editor.drawOrder)
            assertTrue(settings.editor.preset("pen-2").visible)
        }
        composeRule.onNodeWithTag("reorder-pen-1", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, height * 1.5f), durationMillis = 500)
        }
        composeRule.runOnIdle {
            assertEquals(listOf("pen-2", "marker", "pen-1", "highlighter"), settings.editor.drawOrder)
            assertTrue(settings.editor.preset("pen-2").visible)
        }
    }

    @Test
    fun textDialogRestoresDraftAndAppearance() {
        val restoration = StateRestorationTester(composeRule)
        var saved: TextBoxAnnotation? = null
        restoration.setContent {
            SheetSetTheme {
                TextAnnotationDialog(
                    initial = null,
                    bounds = NormalizedRect(0.1f, 0.1f, 0.6f, 0.5f),
                    defaultSize = AnnotationTextSize.MEDIUM,
                    defaultColor = AnnotationColor.BLACK,
                    defaultOpacity = 255,
                    quickColors = listOf(AnnotationColor.RED),
                    recentColors = emptyList(),
                    onDismiss = {},
                    onConfirm = { saved = it },
                )
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Rozpracovaný text")
        composeRule.onNodeWithText("Large").performScrollTo().performClick()
        composeRule.onNodeWithText("More options").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Line height")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.7f) }
        composeRule.onNodeWithText("Center").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Color").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Red").performClick()
        composeRule.onNodeWithContentDescription("Opacity")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(128f) }
        composeRule.onNodeWithText("Apply").performClick()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNode(hasSetTextAction()).assertTextContains("Rozpracovaný text")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.runOnIdle {
            val annotation = requireNotNull(saved)
            assertEquals("Rozpracovaný text", annotation.text)
            assertEquals(AnnotationTextSize.LARGE, annotation.size)
            assertEquals(1.7f, annotation.lineHeight, 0.001f)
            assertEquals(AnnotationTextAlignment.CENTER, annotation.alignment)
            assertEquals(AnnotationColor.RED, annotation.color)
            assertEquals(128, annotation.opacity)
        }
    }

    @Test
    fun textDraftDoesNotCarryIntoAnotherAnnotation() {
        var initial by mutableStateOf(
            TextBoxAnnotation(
                id = "first",
                bounds = NormalizedRect(0.1f, 0.1f, 0.6f, 0.5f),
                text = "First text",
                size = AnnotationTextSize.MEDIUM,
            ),
        )
        composeRule.setContent {
            SheetSetTheme {
                TextAnnotationDialog(
                    initial = initial,
                    bounds = initial.bounds,
                    defaultSize = AnnotationTextSize.MEDIUM,
                    defaultColor = AnnotationColor.BLACK,
                    defaultOpacity = 255,
                    quickColors = listOf(AnnotationColor.RED),
                    recentColors = emptyList(),
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Draft for first")

        composeRule.runOnIdle { initial = initial.copy(id = "second", text = "Second text") }

        composeRule.onNode(hasSetTextAction()).assertTextContains("Second text")
    }
}
