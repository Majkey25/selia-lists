package cz.teply.sheetset.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.teply.sheetset.R
import cz.teply.sheetset.ReaderUiState
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.PageFit
import cz.teply.sheetset.settings.ReaderLayout

internal enum class ReaderPanel { BOOKMARK, PAGE, GESTURE, TOOLS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PerformanceToolsSheet(
    section: ReaderPanel,
    reader: ReaderUiState,
    settings: AppSettings,
    windowLayout: WindowLayout,
    onSettings: (AppSettings) -> Unit,
    onJump: (Int) -> Unit,
    onAddBookmark: (String) -> Unit,
    onRenameBookmark: (String, String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var addBookmark by rememberSaveable(reader.score.id) { mutableStateOf(false) }
    var renameBookmarkId by rememberSaveable(reader.score.id) { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(
                    when (section) {
                        ReaderPanel.BOOKMARK -> R.string.reader_tab_bookmark
                        ReaderPanel.PAGE -> R.string.reader_tab_page
                        ReaderPanel.GESTURE -> R.string.reader_tab_gesture
                        ReaderPanel.TOOLS -> R.string.reader_tab_tools
                    },
                ),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            when (section) {
                ReaderPanel.BOOKMARK -> {
                    ListItem(
                        modifier = Modifier.clickable { addBookmark = true },
                        headlineContent = { Text(stringResource(R.string.add_bookmark)) },
                        leadingContent = {
                            Icon(painterResource(R.drawable.ic_add_24), contentDescription = null)
                        },
                    )
                    if (reader.score.bookmarks.isEmpty()) {
                        Text(
                            stringResource(R.string.no_bookmarks),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    reader.score.bookmarks.forEach { bookmark ->
                        ListItem(
                            modifier = Modifier.clickable {
                                onJump(bookmark.pageIndex)
                                onDismiss()
                            },
                            headlineContent = { Text(bookmark.title) },
                            supportingContent = {
                                Text(stringResource(R.string.bookmark_page, bookmark.pageIndex + 1))
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { renameBookmarkId = bookmark.id }) {
                                        Icon(
                                            painterResource(R.drawable.ic_edit_24),
                                            contentDescription = stringResource(R.string.rename),
                                        )
                                    }
                                    IconButton(onClick = { onDeleteBookmark(bookmark.id) }) {
                                        Icon(
                                            painterResource(R.drawable.ic_delete_24),
                                            contentDescription = stringResource(R.string.delete),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                ReaderPanel.PAGE -> {
                    SectionTitle(R.string.page_layout)
                    LayoutRow(R.string.single_page, settings.readerLayout == ReaderLayout.SINGLE) {
                        onSettings(settings.copy(readerLayout = ReaderLayout.SINGLE))
                    }
                    LayoutRow(R.string.half_page, settings.readerLayout == ReaderLayout.HALF) {
                        onSettings(settings.copy(readerLayout = ReaderLayout.HALF))
                    }
                    if (windowLayout != WindowLayout.COMPACT) {
                        LayoutRow(R.string.two_pages, settings.readerLayout == ReaderLayout.TWO_PAGE) {
                            onSettings(settings.copy(readerLayout = ReaderLayout.TWO_PAGE))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionTitle(R.string.page_fit)
                    LayoutRow(R.string.fit_page, settings.pageFit == PageFit.PAGE) {
                        onSettings(settings.copy(pageFit = PageFit.PAGE))
                    }
                    LayoutRow(R.string.fit_width, settings.pageFit == PageFit.WIDTH) {
                        onSettings(settings.copy(pageFit = PageFit.WIDTH))
                    }
                }
                ReaderPanel.GESTURE -> {
                    ToggleRow(R.string.page_turn_taps, settings.pageTurnTaps) {
                        onSettings(settings.copy(pageTurnTaps = it))
                    }
                    ToggleRow(R.string.page_turn_swipes, settings.pageTurnSwipes) {
                        onSettings(settings.copy(pageTurnSwipes = it))
                    }
                    ToggleRow(R.string.palm_rejection, settings.editor.palmRejection) {
                        onSettings(settings.copy(editor = settings.editor.copy(palmRejection = it)))
                    }
                }
                ReaderPanel.TOOLS -> {
                    ListItem(
                        modifier = Modifier.clickable {
                            onDismiss()
                            onExport()
                        },
                        headlineContent = { Text(stringResource(R.string.export)) },
                        leadingContent = {
                            Icon(painterResource(R.drawable.ic_download_24), contentDescription = null)
                        },
                    )
                    ToggleRow(R.string.keep_screen_awake, settings.keepScreenAwake) {
                        onSettings(settings.copy(keepScreenAwake = it))
                    }
                    ToggleRow(R.string.auto_hide_controls, settings.autoHideControls) {
                        onSettings(settings.copy(autoHideControls = it))
                    }
                }
            }
        }
    }

    if (addBookmark) {
        BookmarkDialog(
            initialValue = "",
            fallback = stringResource(R.string.bookmark_page, reader.pageIndex + 1),
            onDismiss = { addBookmark = false },
            onSave = {
                onAddBookmark(it)
                addBookmark = false
            },
        )
    }
    reader.score.bookmarks.firstOrNull { it.id == renameBookmarkId }?.let { bookmark ->
        key(bookmark.id) {
            BookmarkDialog(
                initialValue = bookmark.title,
                fallback = bookmark.title,
                onDismiss = { renameBookmarkId = null },
                onSave = {
                    onRenameBookmark(bookmark.id, it)
                    renameBookmarkId = null
                },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, onChecked: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChecked(!checked) },
        headlineContent = { Text(stringResource(label)) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChecked)
        },
    )
}

@Composable
private fun SectionTitle(label: Int) {
    Text(
        stringResource(label),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun LayoutRow(label: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
    )
}

@Composable
private fun BookmarkDialog(
    initialValue: String,
    fallback: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_bookmark)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(120) },
                label = { Text(stringResource(R.string.bookmark_title)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.trim().ifEmpty { fallback }) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
