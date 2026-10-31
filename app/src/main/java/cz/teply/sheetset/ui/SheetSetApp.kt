package cz.teply.sheetset.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.teply.sheetset.LibraryUiState
import cz.teply.sheetset.R
import cz.teply.sheetset.data.MAX_TITLE_LENGTH
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.ReaderLayout
import kotlinx.coroutines.launch

data class SheetSetActions(
    val importPdfs: (List<Uri>) -> Unit = {},
    val createSetlist: (String) -> Unit = {},
    val openScore: (Score) -> Unit = {},
    val openScoreAt: (Score, Int) -> Unit = { _, _ -> },
    val openSetlistScore: (String, Int) -> Unit = { _, _ -> },
    val closeReader: () -> Unit = {},
    val previousPage: (ReaderLayout) -> Unit = {},
    val nextPage: (ReaderLayout) -> Unit = {},
    val jumpToPage: (Int) -> Unit = {},
    val addBookmark: (String) -> Unit = {},
    val renameBookmark: (String, String) -> Unit = { _, _ -> },
    val deleteBookmark: (String) -> Unit = {},
    val saveAnnotations: (String, Int, DocumentAnnotations) -> Unit = { _, _, _ -> },
    val exportPdf: (Uri) -> Unit = {},
    val renameScore: (String, String) -> Unit = { _, _ -> },
    val deleteScore: (String) -> Unit = {},
    val renameSetlist: (String, String) -> Unit = { _, _ -> },
    val deleteSetlist: (String) -> Unit = {},
    val updateScoreLabels: (String, List<String>) -> Unit = { _, _ -> },
    val updateSetlistLabels: (String, List<String>) -> Unit = { _, _ -> },
    val addScores: (String, List<String>) -> Unit = { _, _ -> },
    val removeScore: (String, Int) -> Unit = { _, _ -> },
    val reorderScores: (String, List<String>) -> Unit = { _, _ -> },
    val updateSettings: (AppSettings) -> Unit = {},
    val selectLanguage: (String?) -> Unit = {},
    val createBackup: (Uri) -> Unit = {},
    val shareBackup: () -> Unit = {},
    val restoreBackup: (Uri) -> Unit = {},
)

@Composable
fun SheetSetApp(
    state: LibraryUiState,
    actions: SheetSetActions,
    windowLayout: WindowLayout = WindowLayout.COMPACT,
) {
    val errorMessage = state.error?.let { stringResource(it) }
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        if (errorMessage != null) snackbarHost.showSnackbar(errorMessage)
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        SheetSetContent(state, actions, windowLayout)
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

@Composable
private fun SheetSetContent(
    state: LibraryUiState,
    actions: SheetSetActions,
    windowLayout: WindowLayout,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var activeSetlistId by rememberSaveable { mutableStateOf<String?>(null) }
    var createSetlist by rememberSaveable { mutableStateOf(false) }
    var librarySearching by rememberSaveable { mutableStateOf(false) }
    var importOptions by rememberSaveable { mutableStateOf(false) }
    var pendingRestore by rememberSaveable { mutableStateOf<Uri?>(null) }
    var setlistName by rememberSaveable { mutableStateOf("") }
    val libraryState = rememberSaveableStateHolder()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
        actions.importPdfs,
    )
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(actions.createBackup) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingRestore = uri }
    val activeSetlist = state.catalog.setlists.firstOrNull { it.id == activeSetlistId }
    val closeCreateDialog = {
        setlistName = ""
        createSetlist = false
    }

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            state.reader != null -> actions.closeReader()
            activeSetlistId != null -> activeSetlistId = null
        }
    }

    state.reader?.let { reader ->
        key(reader.score.id) {
            ReaderScreen(reader, state.settings, windowLayout, actions)
        }
        return
    }

    if (selectedTab == 1 && activeSetlist != null && windowLayout != WindowLayout.EXPANDED) {
        SetlistDetail(
            setlist = activeSetlist,
            scores = state.catalog.scores,
            actions = actions,
            busy = state.loading,
            onBack = { activeSetlistId = null },
        )
        return
    }

    SettingsDrawer(
        drawerState = drawerState,
        destination = if (selectedTab == 0) AppDestination.PDF else AppDestination.SETLISTS,
        settings = state.settings,
        onDestination = { destination ->
            selectedTab = if (destination == AppDestination.PDF) 0 else 1
            if (destination != AppDestination.PDF) librarySearching = false
        },
        onSettings = actions.updateSettings,
        onLanguage = actions.selectLanguage,
        onBackup = { backupLauncher.launch("SheetSet-Backup.zip") },
        onShareBackup = actions.shareBackup,
        onRestore = {
            restoreLauncher.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            )
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    SheetHeader(
                        actionLabel = if (selectedTab == 0) R.string.import_pdf else R.string.create,
                        actionDescription = if (selectedTab == 0) R.string.import_pdf else R.string.create,
                        actionIcon = if (selectedTab == 0) R.drawable.ic_upload_file_24 else R.drawable.ic_add_24,
                        onMenu = { scope.launch { drawerState.open() } },
                        onAction = {
                            if (selectedTab == 0) importOptions = true
                            else createSetlist = true
                        },
                    )
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            bottomBar = {
                if (windowLayout == WindowLayout.COMPACT) {
                    SheetNavigation(
                        windowLayout = windowLayout,
                        destination = if (selectedTab == 0) {
                            AppDestination.PDF
                        } else {
                            AppDestination.SETLISTS
                        },
                    ) { destination ->
                        selectedTab = if (destination == AppDestination.PDF) 0 else 1
                        if (destination != AppDestination.PDF) librarySearching = false
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (windowLayout != WindowLayout.COMPACT) {
                    SheetNavigation(
                        windowLayout = windowLayout,
                        destination = if (selectedTab == 0) {
                            AppDestination.PDF
                        } else {
                            AppDestination.SETLISTS
                        },
                    ) { destination ->
                        selectedTab = if (destination == AppDestination.PDF) 0 else 1
                        if (destination != AppDestination.PDF) librarySearching = false
                    }
                }
                if (selectedTab == 0) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        libraryState.SaveableStateProvider("library") {
                            LibraryScreen(
                                scores = state.catalog.scores,
                                onOpen = actions.openScore,
                                onOpenBookmark = actions.openScoreAt,
                                onRename = actions.renameScore,
                                onDelete = actions.deleteScore,
                                onLabels = actions.updateScoreLabels,
                                searching = librarySearching,
                                onSearchingChange = { librarySearching = it },
                                modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight(),
                            )
                        }
                    }
                } else if (windowLayout == WindowLayout.EXPANDED) {
                    SetlistsScreen(
                        setlists = state.catalog.setlists,
                        onOpen = { activeSetlistId = it.id },
                        onRename = actions.renameSetlist,
                        onDelete = actions.deleteSetlist,
                        onLabels = actions.updateSetlistLabels,
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                    )
                    VerticalDivider(Modifier.fillMaxHeight())
                    if (activeSetlist != null) {
                        SetlistDetail(
                            setlist = activeSetlist,
                            scores = state.catalog.scores,
                            actions = actions,
                            busy = state.loading,
                            onBack = { activeSetlistId = null },
                            modifier = Modifier.weight(1f),
                            embedded = true,
                        )
                    } else {
                        Box(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SetlistsScreen(
                            setlists = state.catalog.setlists,
                            onOpen = { activeSetlistId = it.id },
                            onRename = actions.renameSetlist,
                            onDelete = actions.deleteSetlist,
                            onLabels = actions.updateSetlistLabels,
                            modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    if (importOptions) {
        ImportSourceSheet(
            onDismiss = { importOptions = false },
            onFiles = { importLauncher.launch(arrayOf("application/pdf")) },
            onScan = { openSeliaScan(context) },
        )
    }

    if (createSetlist) {
        AlertDialog(
            onDismissRequest = closeCreateDialog,
            title = { Text(stringResource(R.string.new_setlist)) },
            text = {
                OutlinedTextField(
                    value = setlistName,
                    onValueChange = { setlistName = it.take(MAX_TITLE_LENGTH) },
                    label = { Text(stringResource(R.string.setlist_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = setlistName.isNotBlank(),
                    onClick = {
                        actions.createSetlist(setlistName)
                        closeCreateDialog()
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = closeCreateDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.restore_backup)) },
            text = { Text(stringResource(R.string.restore_backup_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRestore = null
                        actions.restoreBackup(uri)
                    },
                ) {
                    Text(stringResource(R.string.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private const val SELIA_SCAN_PACKAGE = "com.majkeylab.scanit"

private fun openSeliaScan(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(SELIA_SCAN_PACKAGE)
    if (launchIntent != null && runCatching { context.startActivity(launchIntent) }.isSuccess) return

    val market = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$SELIA_SCAN_PACKAGE"),
    )
    val web = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$SELIA_SCAN_PACKAGE"),
    )
    runCatching { context.startActivity(market) }
        .recoverCatching { context.startActivity(web) }
}
