package cz.teply.sheetset

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.NormalizedRect
import cz.teply.sheetset.pdf.TextBoxAnnotation
import cz.teply.sheetset.settings.AnnotationTextSize
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.ui.COLOR_PANEL_SCROLL_TAG
import cz.teply.sheetset.ui.ColorPanel
import cz.teply.sheetset.ui.SheetSetActions
import cz.teply.sheetset.ui.SheetSetApp
import cz.teply.sheetset.ui.SheetSetTheme
import cz.teply.sheetset.ui.TextAnnotationDialog
import cz.teply.sheetset.ui.WindowLayout
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

@Suppress("DEPRECATION")
class KeyboardInsetsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    @get:Rule
    val testName = TestName()
    private lateinit var keyboardRoot: View

    @After
    fun hideKeyboard() {
        composeRule.runOnIdle {
            if (::keyboardRoot.isInitialized) {
                ViewCompat.getWindowInsetsController(keyboardRoot)?.hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    @Test
    fun librarySearchKeepsListAboveRealKeyboard() {
        val scores = List(30) { index -> Score("ime-$index", "IME score $index", "ime-$index.pdf", 1, index.toLong()) }
        setContent {
            BoxWithConstraints {
                SheetSetApp(
                    state = LibraryUiState(catalog = LibraryCatalog(scores = scores)),
                    actions = SheetSetActions(),
                    windowLayout = WindowLayout.fromWidth(maxWidth),
                )
            }
        }
        composeRule.onNodeWithContentDescription(text(R.string.search_pdfs)).performTouchInput { click() }
        showKeyboard(composeRule.onNode(hasSetTextAction()))

        assertAboveKeyboard(composeRule.onNode(hasSetTextAction()))
        assertAboveKeyboard(composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)))
        composeRule.onNodeWithText("IME score 0").assertIsDisplayed()
    }

    @Test
    fun textDialogKeepsOptionsAndActionsReachableWithRealKeyboard() {
        val draft = List(10) { "Draft line $it" }.joinToString("\n")
        var saved: TextBoxAnnotation? = null
        setContent {
            TextAnnotationDialog(
                initial = TextBoxAnnotation(
                    id = "ime-text",
                    bounds = NormalizedRect(0.1f, 0.1f, 0.8f, 0.8f),
                    text = draft,
                    size = AnnotationTextSize.MEDIUM,
                ),
                bounds = NormalizedRect(0.1f, 0.1f, 0.8f, 0.8f),
                defaultSize = AnnotationTextSize.MEDIUM,
                defaultColor = AnnotationColor.BLACK,
                defaultOpacity = 255,
                quickColors = AppSettings().editor.quickColors,
                recentColors = emptyList(),
                onDismiss = {},
                onConfirm = { saved = it },
            )
        }
        showKeyboard(composeRule.onNode(hasSetTextAction()).performScrollTo())
        composeRule.onNodeWithText(text(R.string.more_options)).performScrollTo().performTouchInput { click() }
        val alignment = composeRule.onNodeWithText(text(R.string.alignment_end)).performScrollTo()

        assertAboveKeyboard(alignment)
        assertAboveKeyboard(composeRule.onNodeWithText(text(R.string.cancel)))
        val save = composeRule.onNodeWithText(text(R.string.save))
        assertAboveKeyboard(save)
        save.performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(draft, saved?.text) }
    }

    @Test
    fun customColorApplyRemainsReachableWithRealKeyboard() {
        val selected = AnnotationColor(0xFF12ABEF.toInt())
        var applied: AnnotationColor? = null
        setContent {
            ColorPanel(
                selected = selected,
                opacity = 255,
                quickColors = AppSettings().editor.quickColors,
                recentColors = emptyList(),
                onDismiss = {},
                onEyedropper = null,
                onConfirm = { color, _ -> applied = color },
            )
        }
        val scroll = composeRule.onNodeWithTag(COLOR_PANEL_SCROLL_TAG)
        scroll.performScrollToNode(hasText(text(R.string.custom_color)) and hasClickAction())
        composeRule.onNode(hasText(text(R.string.custom_color)) and hasClickAction()).performTouchInput { click() }
        scroll.performScrollToNode(hasSetTextAction())
        showKeyboard(composeRule.onNode(hasSetTextAction()))
        scroll.performScrollToNode(hasText(text(R.string.apply)))

        val apply = composeRule.onNodeWithText(text(R.string.apply))
        assertAboveKeyboard(apply)
        apply.performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(selected, applied) }
    }

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.runOnIdle {
            composeRule.activity.setContent { SheetSetTheme { content() } }
        }
        composeRule.waitForIdle()
    }

    private fun showKeyboard(field: SemanticsNodeInteraction) {
        field.performTouchInput { click() }
        val root = requireNotNull(field.fetchSemanticsNode().root) as ViewRootForTest
        keyboardRoot = root.view.rootView
        var previousBottom = 0
        var changedAt = SystemClock.uptimeMillis()
        composeRule.waitUntil(5_000) {
            val bottom = composeRule.runOnIdle {
                val insets = ViewCompat.getRootWindowInsets(keyboardRoot)
                if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                } else 0
            }
            if (bottom != previousBottom) {
                previousBottom = bottom
                changedAt = SystemClock.uptimeMillis()
            }
            bottom > 0 && SystemClock.uptimeMillis() - changedAt >= 250
        }
        composeRule.waitForIdle()
    }

    private fun assertAboveKeyboard(interaction: SemanticsNodeInteraction) {
        try {
            interaction.assertIsDisplayed()
            val node = interaction.fetchSemanticsNode()
            composeRule.runOnIdle {
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(keyboardRoot))
                assertTrue("Software keyboard must remain visible", insets.isVisible(WindowInsetsCompat.Type.ime()))
                val keyboardHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                assertTrue("Software keyboard must occupy window space", keyboardHeight > 0)
                val display = DisplayMetrics()
                requireNotNull(keyboardRoot.display).getRealMetrics(display)
                val keyboardTop = display.heightPixels - keyboardHeight
                val bottom = node.positionOnScreen.y + node.size.height
                assertTrue("Control bottom $bottom overlaps keyboard top $keyboardTop", bottom <= keyboardTop + 1f)
            }
        } catch (failure: AssertionError) {
            runCatching {
                val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try {
                    File(composeRule.activity.cacheDir, "keyboard-${testName.methodName}.png").outputStream().use {
                        check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    screenshot.recycle()
                }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun text(resource: Int): String = composeRule.activity.getString(resource)
}
