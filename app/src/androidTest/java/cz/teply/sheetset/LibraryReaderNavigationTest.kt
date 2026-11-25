package cz.teply.sheetset

import android.content.Context
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cz.teply.sheetset.data.Bookmark
import cz.teply.sheetset.data.LibraryRepository
import cz.teply.sheetset.data.PdfImport
import cz.teply.sheetset.settings.PageFit
import cz.teply.sheetset.settings.ReaderDefaultTool
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.settings.SettingsStore
import cz.teply.sheetset.ui.PDF_PAGE_TEST_TAG
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class LibraryReaderNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun swipesAndTapsFollowTheSortedSearchResults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LibraryRepository(File(context.filesDir, "library"))
        val settings = SettingsStore(context.getSharedPreferences("sheetset-settings", Context.MODE_PRIVATE))
        val previousSettings = settings.load()
        val group = "Library ${UUID.randomUUID().toString().take(8)}"
        val filter = "Match $group"
        val first = "Z $group"
        val hidden = "M $group"
        val last = "A $group"
        val pdf = File.createTempFile("library-navigation-", ".pdf", context.cacheDir)

        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
                document.finishPage(page)
                pdf.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            runBlocking {
                for (title in listOf(last, first, hidden)) {
                    val score = repository.importPdf(
                        PdfImport("$title.pdf", "application/pdf", pdf.length(), pdf::inputStream),
                    )
                    if (title != hidden) repository.updateScoreLabels(score.id, listOf(filter))
                    if (title == last) repository.addBookmark(score.id, Bookmark("reprise", "$filter reprise", 0))
                }
            }
            settings.save(previousSettings.copy(
                pageFit = PageFit.WIDTH,
                defaultTool = ReaderDefaultTool.VIEW,
                readerLayout = ReaderLayout.SINGLE,
                pageTurnTaps = true,
                pageTurnSwipes = true,
                autoHideControls = false,
            ))

            ActivityScenario.launch(MainActivity::class.java).use {
                val importSort = context.getString(R.string.sort_state, context.getString(R.string.sort_imported))
                composeRule.onNodeWithContentDescription(importSort).performClick()
                composeRule.onNodeWithText(context.getString(R.string.sort_title)).performClick()
                composeRule.onNodeWithContentDescription(context.getString(R.string.ascending)).performClick()
                composeRule.onNodeWithContentDescription(context.getString(R.string.search_pdfs)).performClick()
                val search = composeRule.onNode(hasSetTextAction())
                search.performTextReplacement(filter)
                composeRule.onNodeWithText(first).performClick()

                val page = composeRule.onNodeWithTag(PDF_PAGE_TEST_TAG)
                composeRule.waitUntil(5_000) { page.isDisplayed() }
                page.performTouchInput { swipe(center.copy(x = width * 0.8f), center.copy(x = width * 0.2f)) }
                assertReaderTitle(last)

                page.performTouchInput { click(center.copy(x = width * 0.85f)) }
                assertReaderTitle(last)
                page.performTouchInput { click(center.copy(x = width * 0.15f)) }
                assertReaderTitle(first)

                composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
                search.performTextReplacement(group)
                composeRule.onNodeWithText(first).performClick()
                composeRule.waitUntil(5_000) { page.isDisplayed() }
                page.performTouchInput { click(center.copy(x = width * 0.85f)) }
                assertReaderTitle(hidden)
            }
        } finally {
            runBlocking {
                repository.load().scores.filter { it.title.endsWith(group) }
                    .forEach { repository.deleteScore(it.id) }
            }
            settings.save(previousSettings)
            pdf.delete()
        }
    }

    private fun assertReaderTitle(title: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(title).fetchSemanticsNodes().size == 1 }
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }
}
