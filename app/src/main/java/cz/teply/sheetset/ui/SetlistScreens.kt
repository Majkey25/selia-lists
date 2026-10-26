package cz.teply.sheetset.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cz.teply.sheetset.R
import cz.teply.sheetset.data.MAX_TITLE_LENGTH
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.data.Setlist
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class SetlistScoreEntry(val key: String, val scoreId: String)
private val SetlistRowHeight = 68.dp

internal fun targetIndexForDrag(
    origin: Int,
    distance: Float,
    rowHeight: Float,
    lastIndex: Int,
): Int {
    require(origin in 0..lastIndex) { "Invalid drag origin" }
    require(rowHeight > 0f) { "Row height must be positive" }
    return (origin + (distance / rowHeight).roundToInt()).coerceIn(0, lastIndex)
}

internal fun setlistOccurrenceKeys(scoreIds: List<String>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return scoreIds.map { id ->
        val occurrence = occurrences.getOrDefault(id, 0)
        occurrences[id] = occurrence + 1
        "$id-$occurrence"
    }
}

internal fun setlistDragScale(dragging: Boolean): Float = if (dragging) 0.96f else 1f

private fun List<String>.toSetlistEntries(): List<SetlistScoreEntry> =
    zip(setlistOccurrenceKeys(this)) { id, key -> SetlistScoreEntry(key, id) }

private fun <T> List<T>.moved(fromIndex: Int, toIndex: Int): List<T> =
    toMutableList().apply { add(toIndex, removeAt(fromIndex)) }

@Composable
fun SetlistsScreen(
    setlists: List<Setlist>,
    onOpen: (Setlist) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onLabels: (String, List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuSetlistId by remember { mutableStateOf<String?>(null) }
    var renameSetlist by remember { mutableStateOf<Setlist?>(null) }
    var deleteSetlist by remember { mutableStateOf<Setlist?>(null) }
    var labelSetlist by remember { mutableStateOf<Setlist?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SetlistSort.CREATED) }
    var direction by rememberSaveable { mutableStateOf(SortDirection.ASCENDING) }
    var sortMenu by remember { mutableStateOf(false) }
    if (setlists.isEmpty()) {
        AppEmptyState(
            R.string.no_setlists,
            R.string.setlist_hint,
            modifier,
        )
        return
    }
    val visibleSetlists = remember(setlists, query, sort, direction) {
        val needle = query.trim()
        sortSetlists(
            setlists.filter { setlist ->
                needle.isEmpty() ||
                    setlist.name.contains(needle, ignoreCase = true) ||
                    setlist.labels.any { it.contains(needle, ignoreCase = true) }
            },
            sort,
            direction,
        )
    }
    Column(modifier.fillMaxSize()) {
        if (searching) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = query,
                    onValueChange = { query = it.take(MAX_TITLE_LENGTH) },
                    label = { Text(stringResource(R.string.search_setlists)) },
                    singleLine = true,
                )
                TextButton(onClick = { searching = false; query = "" }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        } else {
            val sortDescription = stringResource(
                R.string.sort_state,
                stringResource(
                    if (sort == SetlistSort.TITLE) R.string.sort_title else R.string.sort_created,
                ),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(painterResource(R.drawable.ic_sort_24), sortDescription)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(
                            SetlistSort.TITLE to R.string.sort_title,
                            SetlistSort.CREATED to R.string.sort_created,
                        ).forEach { (option, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = { sort = option; sortMenu = false },
                            )
                        }
                    }
                }
                IconButton(
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
                        stringResource(
                            if (direction == SortDirection.ASCENDING) {
                                R.string.ascending
                            } else {
                                R.string.descending
                            },
                        ),
                    )
                }
                IconButton(onClick = { searching = true }) {
                    Icon(
                        painterResource(R.drawable.ic_search_24),
                        stringResource(R.string.search_setlists),
                    )
                }
            }
        }
        if (visibleSetlists.isEmpty()) {
            AppEmptyState(
                R.string.no_search_results,
                R.string.search_setlists,
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                itemsIndexed(visibleSetlists, key = { _, setlist -> setlist.id }) { index, setlist ->
                    SetlistRow(
                        setlist = setlist,
                        index = index,
                        expanded = menuSetlistId == setlist.id,
                        onOpen = onOpen,
                        onMore = { menuSetlistId = setlist.id },
                        onDismissMenu = { menuSetlistId = null },
                        onRename = { menuSetlistId = null; renameSetlist = setlist },
                        onLabels = { menuSetlistId = null; labelSetlist = setlist },
                        onDelete = { menuSetlistId = null; deleteSetlist = setlist },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    renameSetlist?.let { setlist ->
        NameDialog(
            title = stringResource(R.string.rename_setlist),
            label = stringResource(R.string.setlist_name),
            initialValue = setlist.name,
            onDismiss = { renameSetlist = null },
            onSave = { onRename(setlist.id, it); renameSetlist = null },
        )
    }
    deleteSetlist?.let { setlist ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.delete_setlist),
            message = stringResource(R.string.delete_setlist_message),
            onDismiss = { deleteSetlist = null },
            onConfirm = { onDelete(setlist.id); deleteSetlist = null },
        )
    }
    labelSetlist?.let { setlist ->
        LabelsDialog(
            initialLabels = setlist.labels,
            onDismiss = { labelSetlist = null },
            onSave = { onLabels(setlist.id, it); labelSetlist = null },
        )
    }
}

@Composable
private fun SetlistRow(
    setlist: Setlist,
    index: Int,
    expanded: Boolean,
    onOpen: (Setlist) -> Unit,
    onMore: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onLabels: () -> Unit,
    onDelete: () -> Unit,
) {
    val more = stringResource(R.string.more_options)
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(setlist) }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(setlist.name, style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(
                    R.plurals.score_count,
                    setlist.scoreIds.size,
                    setlist.scoreIds.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (setlist.labels.isNotEmpty()) {
                Text(
                    setlist.labels.joinToString(" · "),
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
fun SetlistDetail(
    setlist: Setlist,
    scores: List<Score>,
    actions: SheetSetActions,
    busy: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    var addDialog by rememberSaveable(setlist.id) { mutableStateOf(false) }
    var editing by rememberSaveable(setlist.id) { mutableStateOf(false) }
    val scoreById = remember(scores) { scores.associateBy(Score::id) }
    val displayedScores = remember(setlist.id) {
        mutableStateListOf<SetlistScoreEntry>().apply {
            addAll(setlist.scoreIds.toSetlistEntries())
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val maxAutoScroll = with(LocalDensity.current) { 28.dp.toPx() }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOrigin by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var dragSource by remember { mutableStateOf<List<SetlistScoreEntry>>(emptyList()) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    var autoScrollDelta by remember { mutableFloatStateOf(0f) }
    val interactionEnabled = !busy

    LaunchedEffect(setlist.scoreIds, busy) {
        if (!busy && dragOrigin < 0) {
            displayedScores.clear()
            displayedScores.addAll(setlist.scoreIds.toSetlistEntries())
        }
    }

    fun requestReorder(fromIndex: Int, toIndex: Int) {
        if (!interactionEnabled || fromIndex == toIndex) return
        val scoreIds = displayedScores.toList().moved(fromIndex, toIndex).map(SetlistScoreEntry::scoreId)
        if (scoreIds == setlist.scoreIds) return
        actions.reorderScores(setlist.id, scoreIds)
    }

    fun updateDragPreview() {
        val from = dragOrigin.takeIf { it >= 0 } ?: return
        val target = targetIndexForDrag(
            origin = from,
            distance = dragOffset,
            rowHeight = rowHeight,
            lastIndex = dragSource.lastIndex,
        )
        val current = draggedIndex
        if (target == current) return
        displayedScores.add(target, displayedScores.removeAt(current))
        draggedIndex = target
    }

    fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
        autoScrollDelta = 0f
    }

    fun startAutoScroll(delta: Float, origin: Int) {
        if (delta == 0f) {
            stopAutoScroll()
            return
        }
        autoScrollDelta = delta
        if (autoScrollJob?.isActive == true) return
        autoScrollJob = scope.launch {
            while (isActive && dragOrigin == origin && autoScrollDelta != 0f) {
                val scrolled = listState.scrollBy(autoScrollDelta)
                if (scrolled == 0f || dragOrigin != origin) break
                dragOffset += scrolled
                updateDragPreview()
                delay(16)
            }
            autoScrollJob = null
            autoScrollDelta = 0f
        }
    }

    fun finishDrag(commit: Boolean) {
        stopAutoScroll()
        val from = dragOrigin
        val to = draggedIndex
        val scoreIds = displayedScores.map(SetlistScoreEntry::scoreId)
        if (commit && from != to && from in setlist.scoreIds.indices &&
            to in setlist.scoreIds.indices && scoreIds != setlist.scoreIds
        ) {
            actions.reorderScores(setlist.id, scoreIds)
        }
        displayedScores.clear()
        displayedScores.addAll(setlist.scoreIds.toSetlistEntries())
        draggedIndex = -1
        dragOrigin = -1
        dragOffset = 0f
        dragPointerY = 0f
        dragSource = emptyList()
        draggedKey = null
    }

    fun dragBy(amount: Float) {
        val currentIndex = dragOrigin.takeIf { it >= 0 } ?: return
        dragOffset += amount
        dragPointerY += amount
        updateDragPreview()
        val layout = listState.layoutInfo
        val top = dragPointerY - rowHeight / 2f
        val bottom = dragPointerY + rowHeight / 2f
        val overflow = when {
            top < layout.viewportStartOffset -> top - layout.viewportStartOffset
            bottom > layout.viewportEndOffset -> bottom - layout.viewportEndOffset
            else -> 0f
        }
        startAutoScroll(
            overflow.coerceIn(-maxAutoScroll, maxAutoScroll),
            currentIndex,
        )
    }
    val detail: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(contentModifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = interactionEnabled,
                    shape = MaterialTheme.shapes.small,
                    onClick = { addDialog = true },
                ) {
                    Text(stringResource(R.string.add_pdfs))
                }
                if (setlist.scoreIds.isNotEmpty()) {
                    TextButton(
                        enabled = interactionEnabled,
                        onClick = { editing = !editing },
                    ) {
                        Text(stringResource(if (editing) R.string.done else R.string.edit_order))
                    }
                }
            }
            HorizontalDivider()
            if (setlist.scoreIds.isEmpty()) {
                AppEmptyState(
                    R.string.no_scores_in_setlist,
                    if (scores.isEmpty()) R.string.import_first else R.string.choose_pdfs,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                ) {
                    itemsIndexed(
                        displayedScores,
                        key = { _, entry -> entry.key },
                    ) { index, entry ->
                        scoreById[entry.scoreId]?.let { score ->
                            SetlistScoreRow(
                                setlist = setlist,
                                score = score,
                                dragKey = entry.key,
                                index = index,
                                editing = editing,
                                enabled = interactionEnabled,
                                dragging = entry.key == draggedKey,
                                dragOffset = if (entry.key == draggedKey) {
                                    dragOffset - (draggedIndex - dragOrigin) * rowHeight
                                } else {
                                    0f
                                },
                                actions = actions,
                                onDragStart = start@{
                                    val item = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == entry.key } ?: return@start
                                    rowHeight = item.size.toFloat()
                                    dragSource = displayedScores.toList()
                                    draggedKey = entry.key
                                    draggedIndex = index
                                    dragOrigin = index
                                    dragOffset = 0f
                                    dragPointerY = item.offset + item.size / 2f
                                },
                                onDrag = ::dragBy,
                                onDragEnd = { finishDrag(commit = true) },
                                onDragCancel = { finishDrag(commit = false) },
                                onMove = { target -> requestReorder(index, target) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
    if (embedded) {
        Column(modifier.fillMaxSize()) {
            SetlistHeader(setlist.name, onBack, statusBarPadding = false)
            HorizontalDivider()
            detail(Modifier.weight(1f))
        }
    } else {
        Scaffold(
            modifier = modifier,
            topBar = { SetlistHeader(setlist.name, onBack) },
        ) { padding ->
            detail(Modifier.padding(padding))
        }
    }
    if (addDialog) {
        AddScoresDialog(
            scores = scores,
            onDismiss = { addDialog = false },
            onAdd = { selected ->
                actions.addScores(setlist.id, selected)
                addDialog = false
            },
        )
    }
}

@Composable
private fun SetlistHeader(
    title: String,
    onBack: () -> Unit,
    statusBarPadding: Boolean = true,
) {
    val back = stringResource(R.string.back)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
                .height(68.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                modifier = Modifier.semantics { contentDescription = back },
                onClick = onBack,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left_24),
                    contentDescription = null,
                )
            }
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
private fun SetlistScoreRow(
    setlist: Setlist,
    score: Score,
    dragKey: String,
    index: Int,
    editing: Boolean,
    enabled: Boolean,
    dragging: Boolean,
    dragOffset: Float,
    actions: SheetSetActions,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    val remove = stringResource(R.string.remove)
    val reorder = stringResource(R.string.reorder)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val dragScale by animateFloatAsState(setlistDragScale(dragging), label = "setlist-drag-scale")
    Row(
        Modifier.fillMaxWidth().height(SetlistRowHeight)
            .zIndex(if (dragging) 1f else 0f).graphicsLayer {
            translationY = if (dragging) dragOffset else 0f
            shadowElevation = if (dragging) 8.dp.toPx() else 0f
            scaleX = dragScale
            scaleY = dragScale
        }.clickable(enabled = !editing && enabled) {
            actions.openSetlistScore(setlist.id, index)
        }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            modifier = Modifier.width(40.dp),
            fontWeight = FontWeight.Bold,
        )
        Text(
            score.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (editing) {
            val accessibilityActions = buildList {
                if (enabled && index > 0) add(CustomAccessibilityAction(up) {
                    onMove(index - 1)
                    true
                })
                if (enabled && index < setlist.scoreIds.lastIndex) {
                    add(CustomAccessibilityAction(down) {
                        onMove(index + 1)
                        true
                    })
                }
            }
            Box(
                Modifier.size(48.dp).semantics {
                    contentDescription = reorder
                    role = Role.Button
                    customActions = accessibilityActions
                }.pointerInput(dragKey, enabled) {
                    if (enabled) {
                        detectDragGestures(
                            onDragStart = { currentOnDragStart() },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragCancel() },
                        ) { change, amount ->
                            change.consume()
                            currentOnDrag(amount.y)
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle_24),
                    contentDescription = null,
                )
            }
            IconButton(
                modifier = Modifier.semantics { contentDescription = remove },
                enabled = enabled,
                onClick = { actions.removeScore(setlist.id, index) },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_24),
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun AddScoresDialog(
    scores: List<Score>,
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit,
) {
    val selected = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }
    val scoreIds = remember(scores) { scores.mapTo(mutableSetOf(), Score::id) }
    val currentSelection = selected.filter(scoreIds::contains)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_pdfs)) },
        text = {
            if (scores.isEmpty()) {
                Text(stringResource(R.string.import_first))
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(scores, key = { _, score -> score.id }) { _, score ->
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = score.id in selected,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    if (checked) {
                                        if (score.id !in selected) selected.add(score.id)
                                    } else selected.remove(score.id)
                                },
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = score.id in selected,
                                onCheckedChange = null,
                            )
                            Text(score.title)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = currentSelection.isNotEmpty(), onClick = { onAdd(currentSelection) }) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
