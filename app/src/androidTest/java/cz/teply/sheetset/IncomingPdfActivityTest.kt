package cz.teply.sheetset

import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cz.teply.sheetset.data.LibraryRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class IncomingPdfActivityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val createdFiles = mutableListOf<File>()
    private val createdTitles = mutableSetOf<String>()

    @Before
    fun closeStaleMainActivities() {
        finishAllMainActivities()
    }

    @After
    fun cleanUpTestImports() {
        try {
            finishAllMainActivities()
            val titles = createdTitles.toSet()
            runBlocking {
                repository().load().scores
                    .filter { it.title in titles }
                    .forEach { repository().deleteScore(it.id) }
            }
        } finally {
            createdFiles.forEach(File::delete)
        }
    }

    @Test
    fun coldStartRequiresConfirmationAndImportsExactlyOnceAcrossRecreation() {
        val (title, uri) = createPdfFixture("cold")
        val activity = startMainActivity(
            pdfIntent(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        assertScoreCountRemains(title, 0)
        assertFalse(activity.isFinishing)
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)

        val recreated = recreate(activity)

        confirmImport()
        awaitScoreCount(title, 1)
        assertEquals(Intent.ACTION_MAIN, recreated.intent.action)
        recreate(recreated)
        assertScoreCountRemains(title, 1)
    }

    @Test
    fun onNewIntentImportsOnceAndMalformedIntentLeavesCatalogUnchanged() {
        val (title, uri) = createPdfFixture("new-intent")
        val activity = startMainActivity(defaultMainIntent())

        instrumentation.runOnMainSync { activity.startActivity(pdfIntent(uri)) }
        assertScoreCountRemains(title, 0)
        confirmImport()
        awaitScoreCount(title, 1)
        assertFalse(activity.isFinishing)
        assertEquals(Intent.ACTION_MAIN, activity.intent.action)

        val recreated = recreate(activity)

        assertScoreCountRemains(title, 1)
        val beforeMalformed = catalogScoreIds()
        instrumentation.runOnMainSync {
            recreated.startActivity(pdfIntent(uri).setType("image/png"))
        }
        assertCatalogRemains(beforeMalformed)
        assertFalse(recreated.isFinishing)
    }

    @Test
    fun cancelledExternalImportLeavesLibraryUnchanged() {
        val (title, uri) = createPdfFixture("cancelled")
        val activity = startMainActivity(pdfIntent(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        composeRule.onNodeWithText(activity.getString(R.string.cancel)).performClick()

        recreate(activity)
        assertScoreCountRemains(title, 0)
    }

    @Test
    fun anotherIntentCannotReplaceTheImportAwaitingConfirmation() {
        val (firstTitle, firstUri) = createPdfFixture("pending")
        val (secondTitle, secondUri) = createPdfFixture("replacement")
        val activity = startMainActivity(pdfIntent(firstUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        instrumentation.runOnMainSync { activity.startActivity(pdfIntent(secondUri)) }
        confirmImport()

        awaitScoreCount(firstTitle, 1)
        assertScoreCountRemains(secondTitle, 0)
    }

    @Test
    fun staleConfirmationCannotApproveDifferentPdfs() {
        val (firstTitle, firstUri) = createPdfFixture("stale")
        val (secondTitle, secondUri) = createPdfFixture("newer")
        val activity = startMainActivity(pdfIntent(firstUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        instrumentation.runOnMainSync {
            val model = ViewModelProvider(activity)[SheetSetViewModel::class.java]
            val previousRequest = model.state.value.incomingPdfs
            model.dismissIncomingPdfs()
            model.offerIncomingPdfs(listOf(secondUri))
            model.confirmIncomingPdfs(previousRequest)
            assertEquals(listOf(secondUri), model.state.value.incomingPdfs)
        }

        assertScoreCountRemains(firstTitle, 0)
        assertScoreCountRemains(secondTitle, 0)
    }

    private fun confirmImport() {
        val text = instrumentation.targetContext.getString(R.string.import_pdf)
        composeRule.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    }

    private fun startMainActivity(intent: Intent): MainActivity {
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        instrumentation.waitForIdleSync()
        return activity
    }

    private fun recreate(activity: MainActivity): MainActivity {
        instrumentation.runOnMainSync(activity::recreate)
        return awaitResumedMainActivity(activity)
    }

    private fun awaitResumedMainActivity(previous: MainActivity): MainActivity {
        val deadline = SystemClock.uptimeMillis() + LIFECYCLE_TIMEOUT_MS
        do {
            resumedMainActivities().firstOrNull { it !== previous }?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Recreated MainActivity did not resume")
    }

    private fun defaultMainIntent(): Intent {
        val context = instrumentation.targetContext
        return Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun pdfIntent(uri: Uri): Intent {
        val context = instrumentation.targetContext
        return Intent(Intent.ACTION_SEND)
            .setClass(context, MainActivity::class.java)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun createPdfFixture(label: String): Pair<String, Uri> {
        val context = instrumentation.targetContext
        val title = "task-11-$label-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, "shared-backups")
        check(directory.exists() || directory.mkdirs())
        val file = File(directory, "$title.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        createdFiles += file
        createdTitles += title
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return title to uri
    }

    private fun awaitScoreCount(title: String, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + IMPORT_TIMEOUT_MS
        var count: Int
        do {
            count = runBlocking { repository().load().scores.count { it.title == title } }
            if (count == expected) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, count)
    }

    private fun assertScoreCountRemains(title: String, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + STABILITY_WINDOW_MS
        do {
            val count = runBlocking { repository().load().scores.count { it.title == title } }
            assertEquals(expected, count)
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private fun assertCatalogRemains(expectedIds: Set<String>) {
        val deadline = SystemClock.uptimeMillis() + STABILITY_WINDOW_MS
        do {
            assertEquals(expectedIds, catalogScoreIds())
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private fun catalogScoreIds(): Set<String> = runBlocking {
        repository().load().scores.mapTo(mutableSetOf()) { it.id }
    }

    private fun repository(): LibraryRepository {
        val context = instrumentation.targetContext
        return LibraryRepository(File(context.filesDir, "library"))
    }

    private fun resumedMainActivities(): List<MainActivity> = mainActivitiesIn(Stage.RESUMED)

    private fun finishAllMainActivities() {
        val deadline = SystemClock.uptimeMillis() + LIFECYCLE_TIMEOUT_MS
        do {
            val activities = ACTIVE_STAGES.flatMap(::mainActivitiesIn).distinct()
            if (activities.isEmpty()) return
            instrumentation.runOnMainSync {
                activities.filterNot { it.isFinishing }.forEach(MainActivity::finish)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue(ACTIVE_STAGES.flatMap(::mainActivitiesIn).isEmpty())
    }

    private fun mainActivitiesIn(stage: Stage): List<MainActivity> {
        var activities = emptyList<MainActivity>()
        instrumentation.runOnMainSync {
            activities = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(stage)
                .filterIsInstance<MainActivity>()
        }
        return activities
    }

    private companion object {
        const val IMPORT_TIMEOUT_MS = 5_000L
        const val LIFECYCLE_TIMEOUT_MS = 5_000L
        const val STABILITY_WINDOW_MS = 1_000L
        const val POLL_INTERVAL_MS = 25L
        val ACTIVE_STAGES = listOf(
            Stage.CREATED,
            Stage.STARTED,
            Stage.RESUMED,
            Stage.PAUSED,
            Stage.STOPPED,
            Stage.RESTARTED,
        )
    }
}
