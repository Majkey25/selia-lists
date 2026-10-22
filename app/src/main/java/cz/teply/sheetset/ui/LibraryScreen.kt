package cz.teply.sheetset.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cz.teply.sheetset.R
import cz.teply.sheetset.data.LibraryCatalog
import cz.teply.sheetset.data.MAX_LABEL_LENGTH
import cz.teply.sheetset.data.MAX_LABELS
import cz.teply.sheetset.data.MAX_TITLE_LENGTH
import cz.teply.sheetset.data.Score

@Composable
fun LibraryScreen(
    scores: List<Score>,
    onOpen: (Score) -> Unit,
    onOpenBookmark: (Score, Int) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onLabels: (String, List<String>) -> Unit,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuScoreId by remember { mutableStateOf<String?>(null) }
    var renameScoreId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteScore by remember { mutableStateOf<Score?>(null) }
    var labelScoreId by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.IMPORTED) }
    var direction by rememberSaveable { mutableStateOf(SortDirection.ASCENDING) }
    var sortMenu by remember { mutableStateOf(false) }
    val visibleResults = remember(scores, query, sort, direction) {
        queryScores(LibraryCatalog(scores = scores), query, sort, direction)
    }

    LaunchedEffect(searching) {
        if (!searching) query = ""
    }

    if (scores.isEmpty()) {
        AppEmptyState(
            R.string.no_pdfs,
            R.string.import_hint,
            modifier,
        )
        return
    }
    Column(modifier.fillMaxSize()) {
        if (searching) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = query,
                    onValueChange = { query = it.take(MAX_TITLE_LENGTH) },
                    label = { Text(stringResource(R.string.search_pdfs)) },
                    singleLine = true,
                )
                TextButton(onClick = { onSearchingChange(false) }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        } else {
            val sortDescription = stringResource(
                R.string.sort_state,
                stringResource(
                    when (sort) {
                        LibrarySort.TITLE -> R.string.sort_title
                        LibrarySort.IMPORTED -> R.string.sort_imported
                        LibrarySort.LAST_VIEWED -> R.string.sort_last_viewed
                    },
                ),
            )
            val directionDescription = stringResource(
                if (direction == SortDirection.ASCENDING) R.string.ascending else R.string.descending,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box {
                    IconButton(
                        modifier = Modifier.semantics { contentDescription = sortDescription },
                        onClick = { sortMenu = true },
                    ) {
                        Icon(painterResource(R.drawable.ic_sort_24), contentDescription = null)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(
                            LibrarySort.TITLE to R.string.sort_title,
                            LibrarySort.IMPORTED to R.string.sort_imported,
                            LibrarySort.LAST_VIEWED to R.string.sort_last_viewed,
                        ).forEach { (option, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = { sort = option; sortMenu = false },
                            )
                        }
                    }
                }
                IconButton(
                    modifier = Modifier.semantics { contentDescription = directionDescription },
                    onClick = {
                        direction = if (direction == SortDirection.ASCENDING) {
                            SortDirection.DESCENDING
                        } else {
                            SortDirection.ASCENDING
                        }
                    },
                ) {
                    Icon(
                        painterResource(
                            if (direction == SortDirection.ASCENDING) {
                                R.drawable.ic_arrow_up_24
                            } else {
                                R.drawable.ic_arrow_down_24
                            },
                        ),
                        contentDescription = null,
                    )
                }
                val searchDescription = stringResource(R.string.search_pdfs)
                IconButton(
                    modifier = Modifier.semantics { contentDescription = searchDescription },
                    onClick = { onSearchingChange(true) },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_24),
                        contentDescription = null,
                    )
                }
            }
        }
        if (visibleResults.isEmpty()) {
            AppEmptyState(R.string.no_search_results, R.string.search_pdfs)
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                itemsIndexed(
                    visibleResults,
                    key = { _, result -> result.key },
                ) { index, result ->
                    when (result) {
                        is LibraryResult.ScoreResult -> ScoreRow(
                            score = result.score,
                            index = index,
                            expanded = menuScoreId == result.score.id,
                            onOpen = onOpen,
                            onMore = { menuScoreId = result.score.id },
                            onDismissMenu = { menuScoreId = null },
                            onRename = { menuScoreId = null; renameScoreId = result.score.id },
                            onLabels = { menuScoreId = null; labelScoreId = result.score.id },
                            onDelete = { menuScoreId = null; deleteScore = result.score },
                        )
                        is LibraryResult.BookmarkResult -> BookmarkRow(
                            result = result,
                            onOpen = { onOpenBookmark(result.score, result.bookmark.pageIndex) },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    scores.firstOrNull { it.id == renameScoreId }?.let { score ->
        NameDialog(
            title = stringResource(R.string.rename_score),
            label = stringResource(R.string.name),
            initialValue = score.title,
            onDismiss = { renameScoreId = null },
            onSave = { onRename(score.id, it); renameScoreId = null },
        )
    }
    deleteScore?.let { score ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.delete_score),
            message = stringResource(R.string.delete_score_message),
            onDismiss = { deleteScore = null },
            onConfirm = { onDelete(score.id); deleteScore = null },
        )
    }
    scores.firstOrNull { it.id == labelScoreId }?.let { score ->
        LabelsDialog(
            initialLabels = score.labels,
            onDismiss = { labelScoreId = null },
            onSave = { onLabels(score.id, it); labelScoreId = null },
        )
    }
}

@Composable
private fun ScoreRow(
    score: Score,
    index: Int,
    expanded: Boolean,
    onOpen: (Score) -> Unit,
    onMore: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onLabels: () -> Unit,
    onDelete: () -> Unit,
) {
    val more = stringResource(R.string.more_options)
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(score) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            modifier = Modifier.width(32.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(score.title, style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(R.plurals.page_count, score.pageCount, score.pageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (score.labels.isNotEmpty()) {
                Text(
                    score.labels.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(
                modifier = Modifier.semantics { contentDescription = more },
                onClick = onMore,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert_24),
                    contentDescription = null,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    onClick = onRename,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.labels)) },
                    onClick = onLabels,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
internal fun AppEmptyState(
    title: Int,
    message: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp)
                .padding(horizontal = 24.dp, vertical = 72.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(width = 44.dp, height = 58.dp)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.width(24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(3) { HorizontalDivider() }
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BookmarkRow(result: LibraryResult.BookmarkResult, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_view_module_24), contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(result.bookmark.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${result.score.title} · " +
                    stringResource(R.string.bookmark_page, result.bookmark.pageIndex + 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LabelsDialog(
    initialLabels: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var value by rememberSaveable(initialLabels) { mutableStateOf(initialLabels.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.labels)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(MAX_LABELS * (MAX_LABEL_LENGTH + 2)) },
                label = { Text(stringResource(R.string.labels)) },
                supportingText = { Text(stringResource(R.string.labels_hint)) },
                singleLine = false,
                minLines = 2,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.split(',')) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun NameDialog(
    title: String,
    label: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(MAX_TITLE_LENGTH) },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onSave(value) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
