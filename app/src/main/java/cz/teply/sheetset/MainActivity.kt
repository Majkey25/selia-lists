package cz.teply.sheetset

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.teply.sheetset.settings.AppLanguages
import cz.teply.sheetset.settings.ThemeMode
import cz.teply.sheetset.ui.SheetSetActions
import cz.teply.sheetset.ui.SheetSetApp
import cz.teply.sheetset.ui.SheetSetTheme
import cz.teply.sheetset.ui.WindowLayout

class MainActivity : AppCompatActivity() {
    private val viewModel by viewModels<SheetSetViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguages.initialize(this)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val view = LocalView.current
            SideEffect {
                val lightBars = state.settings.themeMode == ThemeMode.LIGHT
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
            SheetSetTheme(state.settings.themeMode) {
                BoxWithConstraints {
                    SheetSetApp(
                        state = state,
                        actions = SheetSetActions(
                            importPdfs = viewModel::importPdfs,
                            createSetlist = viewModel::createSetlist,
                            openScore = viewModel::openScore,
                            openScoreAt = viewModel::openScoreAt,
                            openSetlistScore = viewModel::openSetlistScore,
                            closeReader = viewModel::closeReader,
                            previousPage = viewModel::previousPage,
                            nextPage = viewModel::nextPage,
                            jumpToPage = viewModel::jumpToPage,
                            addBookmark = viewModel::addBookmark,
                            renameBookmark = viewModel::renameBookmark,
                            deleteBookmark = viewModel::deleteBookmark,
                            saveAnnotations = viewModel::saveAnnotations,
                            exportPdf = viewModel::exportPdf,
                            renameScore = viewModel::renameScore,
                            deleteScore = viewModel::deleteScore,
                            renameSetlist = viewModel::renameSetlist,
                            deleteSetlist = viewModel::deleteSetlist,
                            updateScoreLabels = viewModel::updateScoreLabels,
                            updateSetlistLabels = viewModel::updateSetlistLabels,
                            addScores = viewModel::addScores,
                            removeScore = viewModel::removeScore,
                            reorderScores = viewModel::reorderScores,
                            updateSettings = viewModel::updateSettings,
                            selectLanguage = { languageTag ->
                                AppLanguages.select(this@MainActivity, languageTag)
                            },
                            createBackup = viewModel::createBackup,
                            shareBackup = {
                                viewModel.createSharedBackup(::openBackupShareSheet)
                            },
                            restoreBackup = viewModel::restoreBackup,
                        ),
                        windowLayout = WindowLayout.fromWidth(maxWidth),
                    )
                }
                val incomingPdfs = state.incomingPdfs
                if (incomingPdfs.isNotEmpty()) {
                    AlertDialog(
                        onDismissRequest = viewModel::dismissIncomingPdfs,
                        title = { Text(stringResource(R.string.import_pdf)) },
                        text = {
                            Text(stringResource(R.string.confirm_incoming_pdfs, incomingPdfs.size))
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmIncomingPdfs(incomingPdfs) }) {
                                Text(stringResource(R.string.import_pdf))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::dismissIncomingPdfs) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                }
            }
        }
        handleIncomingPdfIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingPdfIntent(intent)
    }

    private fun handleIncomingPdfIntent(source: Intent) {
        val uris = IncomingPdfIntent.uris(source)
        if (uris.isEmpty()) return
        viewModel.offerIncomingPdfs(uris)
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    private fun openBackupShareSheet(uri: Uri) {
        val share = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        share.clipData = ClipData.newUri(contentResolver, "SeliaLists backup", uri)
        startActivity(Intent.createChooser(share, getString(R.string.share_backup_chooser)))
    }
}
