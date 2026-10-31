package cz.teply.sheetset.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cz.teply.sheetset.R
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.DrawingPreset
import cz.teply.sheetset.pdf.MAX_ANNOTATION_WIDTH
import cz.teply.sheetset.pdf.MIN_ANNOTATION_WIDTH
import cz.teply.sheetset.settings.AnnotationTextSize
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.ReaderDefaultTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class AnnotationChoice { DEFAULT_TOOL, TEXT_SIZE }
private enum class ReorderGroup { DRAWING, OBJECTS, COLORS }
private data class PendingOrder(val group: ReorderGroup, val values: List<String>)

private val ReorderRowHeight = 72.dp

@Composable
internal fun AnnotationSettings(
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: (AppSettings) -> Unit,
) {
    var choice by remember { mutableStateOf<AnnotationChoice?>(null) }
    var editingPresetId by remember { mutableStateOf<String?>(null) }
    var validationMessage by remember { mutableStateOf<Int?>(null) }
    val editor = settings.editor
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowHeight = with(LocalDensity.current) { ReorderRowHeight.toPx() }
    val maxAutoScroll = with(LocalDensity.current) { 28.dp.toPx() }
    val displayedDrawing = remember { mutableStateListOf<String>() }
    val displayedObjects = remember { mutableStateListOf<String>() }
    val displayedColors = remember { mutableStateListOf<String>() }
    var dragGroup by remember { mutableStateOf<ReorderGroup?>(null) }
    var dragOrigin by remember { mutableIntStateOf(-1) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var dragSource by remember { mutableStateOf<List<String>>(emptyList()) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    var autoScrollDelta by remember { mutableFloatStateOf(0f) }
    var pendingOrder by remember { mutableStateOf<PendingOrder?>(null) }

    fun persisted(group: ReorderGroup): List<String> = when (group) {
        ReorderGroup.DRAWING -> editor.drawOrder
        ReorderGroup.OBJECTS -> editor.objectOrder
        ReorderGroup.COLORS -> editor.quickColors.map(AnnotationColor::encoded)
    }

    fun displayed(group: ReorderGroup): SnapshotStateList<String> = when (group) {
        ReorderGroup.DRAWING -> displayedDrawing
        ReorderGroup.OBJECTS -> displayedObjects
        ReorderGroup.COLORS -> displayedColors
    }

    fun syncDisplayed() {
        displayedDrawing.replaceWith(editor.drawOrder)
        displayedObjects.replaceWith(editor.objectOrder)
        displayedColors.replaceWith(editor.quickColors.map(AnnotationColor::encoded))
    }

    LaunchedEffect(editor.drawOrder, editor.objectOrder, editor.quickColors, dragGroup) {
        pendingOrder?.let { pending ->
            if (persisted(pending.group) == pending.values) pendingOrder = null
        }
        if (dragGroup == null && pendingOrder == null) syncDisplayed()
    }

    fun updateDragPreview() {
        val group = dragGroup ?: return
        val sourceIndex = dragOrigin.takeIf { it >= 0 } ?: return
        val target = targetIndexForDrag(sourceIndex, dragOffset, rowHeight, dragSource.lastIndex)
        if (target == draggedIndex) return
        draggedIndex = target
        displayed(group).replaceWith(dragSource.moved(sourceIndex, target))
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

    fun commit(group: ReorderGroup, values: List<String>) {
        val nextEditor = when (group) {
            ReorderGroup.DRAWING -> editor.copy(drawOrder = values)
            ReorderGroup.OBJECTS -> editor.copy(objectOrder = values)
            ReorderGroup.COLORS -> editor.copy(quickColors = values.map(AnnotationColor::decode))
        }
        pendingOrder = PendingOrder(group, values)
        onSettings(settings.copy(editor = nextEditor))
    }

    fun finishDrag(commit: Boolean) {
        stopAutoScroll()
        val group = dragGroup
        if (group != null) {
            if (commit && dragOrigin >= 0 && draggedIndex >= 0) {
                val values = displayed(group).toList()
                if (values != dragSource) commit(group, values)
            } else {
                displayed(group).replaceWith(dragSource)
            }
        }
        dragGroup = null
        dragOrigin = -1
        draggedIndex = -1
        draggedKey = null
        dragOffset = 0f
        dragPointerY = 0f
        dragSource = emptyList()
    }

    fun startDrag(group: ReorderGroup, index: Int, key: String) {
        val values = displayed(group)
        dragGroup = group
        dragOrigin = index
        draggedIndex = index
        draggedKey = key
        dragSource = values.toList()
        dragOffset = 0f
        dragPointerY = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == key }
            ?.let { it.offset + it.size / 2f }
            ?: 0f
    }

    fun dragBy(amount: Float) {
        val origin = dragOrigin.takeIf { it >= 0 } ?: return
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
        startAutoScroll(overflow.coerceIn(-maxAutoScroll, maxAutoScroll), origin)
    }

    fun move(group: ReorderGroup, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || pendingOrder != null) return
        val values = displayed(group).toList().moved(fromIndex, toIndex)
        displayed(group).replaceWith(values)
        commit(group, values)
    }

    DisposableEffect(Unit) {
        onDispose { autoScrollJob?.cancel() }
    }

    SettingsPage(R.string.annotation_tools, onBack, listState) {
        item { SettingsSectionTitle(R.string.default_tool) }
        item {
            SettingsChoiceRow(R.string.default_tool, defaultToolLabel(settings.defaultTool)) {
                choice = AnnotationChoice.DEFAULT_TOOL
            }
        }
        item { SettingsSectionTitle(R.string.drawing_presets) }
        items(displayedDrawing.size, key = { "drawing:${displayedDrawing[it]}" }) { index ->
            val id = displayedDrawing[index]
            val key = "drawing:$id"
            val preset = editor.preset(id)
            SettingsReorderSwitchRow(
                label = presetLabel(id),
                summary = stringResource(
                    R.string.drawing_preset_summary,
                    preset.width,
                    (preset.opacity * 100f / 255f).roundToInt(),
                ),
                checked = preset.visible,
                itemKey = id,
                dragging = draggedKey == key,
                dragTranslation = dragTranslation(key, index, dragOrigin, draggedIndex, dragOffset, rowHeight),
                index = index,
                lastIndex = displayedDrawing.lastIndex,
                onClick = { editingPresetId = id },
                onChecked = { visible ->
                    if (!visible && editor.presets.count(DrawingPreset::visible) == 1) {
                        validationMessage = R.string.keep_one_drawing_tool
                    } else {
                        onSettings(
                            settings.copy(
                                editor = editor.copy(
                                    presets = editor.presets.map {
                                        if (it.id == id) it.copy(visible = visible) else it
                                    },
                                ),
                            ),
                        )
                    }
                },
                onDragStart = { startDrag(ReorderGroup.DRAWING, index, key) },
                onDrag = ::dragBy,
                onDragEnd = { finishDrag(true) },
                onDragCancel = { finishDrag(false) },
                onMove = { target -> move(ReorderGroup.DRAWING, index, target) },
            )
        }
        item { SettingsSectionTitle(R.string.toolbar_tools) }
        items(displayedObjects.size, key = { "objects:${displayedObjects[it]}" }) { index ->
            val id = displayedObjects[index]
            val key = "objects:$id"
            val definition = requireNotNull(objectToolMetadata(id))
            SettingsReorderSwitchRow(
                label = definition.label,
                summary = stringResource(
                    if (id in editor.visibleObjectTools) R.string.tool_visible else R.string.tool_hidden,
                ),
                checked = id in editor.visibleObjectTools,
                itemKey = id,
                dragging = draggedKey == key,
                dragTranslation = dragTranslation(key, index, dragOrigin, draggedIndex, dragOffset, rowHeight),
                index = index,
                lastIndex = displayedObjects.lastIndex,
                onChecked = { visible ->
                    if (!visible && editor.visibleObjectTools.size == 1) {
                        validationMessage = R.string.keep_one_object_tool
                    } else {
                        onSettings(
                            settings.copy(
                                editor = editor.copy(
                                    visibleObjectTools = if (visible) {
                                        editor.visibleObjectTools + id
                                    } else {
                                        editor.visibleObjectTools - id
                                    },
                                ),
                            ),
                        )
                    }
                },
                onDragStart = { startDrag(ReorderGroup.OBJECTS, index, key) },
                onDrag = ::dragBy,
                onDragEnd = { finishDrag(true) },
                onDragCancel = { finishDrag(false) },
                onMove = { target -> move(ReorderGroup.OBJECTS, index, target) },
            )
        }
        item { SettingsSectionTitle(R.string.quick_colors) }
        items(displayedColors.size, key = { "colors:${displayedColors[it]}" }) { index ->
            val encoded = displayedColors[index]
            val key = "colors:$encoded"
            val color = AnnotationColor.decode(encoded)
            SettingsReorderRow(
                label = annotationColorLabel(color),
                itemKey = encoded,
                dragging = draggedKey == key,
                dragTranslation = dragTranslation(key, index, dragOrigin, draggedIndex, dragOffset, rowHeight),
                index = index,
                lastIndex = displayedColors.lastIndex,
                leading = { ColorDot(color) },
                onDragStart = { startDrag(ReorderGroup.COLORS, index, key) },
                onDrag = ::dragBy,
                onDragEnd = { finishDrag(true) },
                onDragCancel = { finishDrag(false) },
                onMove = { target -> move(ReorderGroup.COLORS, index, target) },
            )
        }
        item { SettingsSectionTitle(R.string.settings_text) }
        item {
            SettingsChoiceRow(R.string.text_size, textSizeLabel(settings.textSize)) {
                choice = AnnotationChoice.TEXT_SIZE
            }
        }
        item { SettingsSectionTitle(R.string.settings_stylus) }
        item {
            SettingsSwitchRow(
                R.string.palm_rejection,
                R.string.palm_rejection_summary,
                editor.palmRejection,
            ) {
                onSettings(settings.copy(editor = editor.copy(palmRejection = it)))
            }
        }
    }

    when (choice) {
        AnnotationChoice.DEFAULT_TOOL -> SettingsChoiceDialog(
            title = R.string.default_tool,
            selected = settings.defaultTool,
            options = listOf(
                ReaderDefaultTool.VIEW to R.string.select,
                ReaderDefaultTool.PEN to R.string.pen_1,
                ReaderDefaultTool.HIGHLIGHTER to R.string.highlighter,
            ),
            onDismiss = { choice = null },
        ) {
            onSettings(settings.copy(defaultTool = it))
            choice = null
        }
        AnnotationChoice.TEXT_SIZE -> SettingsChoiceDialog(
            title = R.string.text_size,
            selected = settings.textSize,
            options = listOf(
                AnnotationTextSize.SMALL to R.string.small,
                AnnotationTextSize.MEDIUM to R.string.medium,
                AnnotationTextSize.LARGE to R.string.large,
            ),
            onDismiss = { choice = null },
        ) {
            onSettings(settings.copy(textSize = it))
            choice = null
        }
        null -> Unit
    }

    editingPresetId?.let { id ->
        DrawingPresetDialog(
            preset = editor.preset(id),
            colors = editor.quickColors,
            onDismiss = { editingPresetId = null },
        ) { updated ->
            onSettings(
                settings.copy(
                    editor = editor.copy(
                        presets = editor.presets.map { if (it.id == id) updated else it },
                    ),
                ),
            )
            editingPresetId = null
        }
    }
    validationMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { validationMessage = null },
            title = { Text(stringResource(R.string.setting_not_changed)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = { validationMessage = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun SettingsReorderSwitchRow(
    @StringRes label: Int,
    summary: String,
    checked: Boolean,
    itemKey: String,
    dragging: Boolean,
    dragTranslation: Float,
    index: Int,
    lastIndex: Int,
    onClick: (() -> Unit)? = null,
    onChecked: (Boolean) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMove: (Int) -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().height(ReorderRowHeight)
            .testTag("reorder-row-$itemKey")
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragging) dragTranslation else 0f
                shadowElevation = if (dragging) 8.dp.toPx() else 0f
            }.then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        headlineContent = { Text(stringResource(label), maxLines = 1) },
        supportingContent = { Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = checked,
                    onCheckedChange = onChecked,
                    modifier = Modifier.testTag("visibility-$itemKey"),
                )
                ReorderHandle(
                    itemKey,
                    index,
                    lastIndex,
                    onDragStart,
                    onDrag,
                    onDragEnd,
                    onDragCancel,
                    onMove,
                )
            }
        },
    )
}

@Composable
private fun SettingsReorderRow(
    label: String,
    itemKey: String,
    dragging: Boolean,
    dragTranslation: Float,
    index: Int,
    lastIndex: Int,
    leading: @Composable () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMove: (Int) -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().height(ReorderRowHeight)
            .testTag("reorder-row-$itemKey")
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragging) dragTranslation else 0f
                shadowElevation = if (dragging) 8.dp.toPx() else 0f
            },
        headlineContent = { Text(label, maxLines = 1) },
        leadingContent = leading,
        trailingContent = {
            ReorderHandle(
                itemKey,
                index,
                lastIndex,
                onDragStart,
                onDrag,
                onDragEnd,
                onDragCancel,
                onMove,
            )
        },
    )
}

@Composable
private fun ReorderHandle(
    itemKey: String,
    index: Int,
    lastIndex: Int,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val reorder = stringResource(R.string.reorder)
    val moveUp = stringResource(R.string.move_up)
    val moveDown = stringResource(R.string.move_down)
    val actions = buildList {
        if (index > 0) add(CustomAccessibilityAction(moveUp) {
            onMove(index - 1)
            true
        })
        if (index < lastIndex) add(CustomAccessibilityAction(moveDown) {
            onMove(index + 1)
            true
        })
    }
    Box(
        Modifier.size(48.dp).testTag("reorder-$itemKey").semantics {
            contentDescription = reorder
            role = Role.Button
            customActions = actions
        }.pointerInput(itemKey) {
            detectDragGestures(
                onDragStart = { currentOnDragStart() },
                onDragEnd = { currentOnDragEnd() },
                onDragCancel = { currentOnDragCancel() },
            ) { change, amount ->
                change.consume()
                currentOnDrag(amount.y)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_drag_handle_24), contentDescription = null)
    }
}

@Composable
private fun DrawingPresetDialog(
    preset: DrawingPreset,
    colors: List<AnnotationColor>,
    onDismiss: () -> Unit,
    onSave: (DrawingPreset) -> Unit,
) {
    var width by remember(preset.id) { mutableFloatStateOf(preset.width.toFloat()) }
    var opacity by remember(preset.id) { mutableFloatStateOf(preset.opacity.toFloat()) }
    var color by remember(preset.id) { mutableStateOf(preset.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(presetLabel(preset.id))) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsSlider(
                    stringResource(R.string.stroke_width),
                    width,
                    MIN_ANNOTATION_WIDTH.toFloat()..MAX_ANNOTATION_WIDTH.toFloat(),
                    width.roundToInt().toString(),
                    steps = MAX_ANNOTATION_WIDTH - MIN_ANNOTATION_WIDTH - 1,
                ) { width = it }
                SettingsSlider(
                    stringResource(R.string.opacity),
                    opacity,
                    0f..255f,
                    "${(opacity * 100f / 255f).roundToInt()}%",
                ) { opacity = it }
                Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    colors.forEach { option ->
                        val label = annotationColorLabel(option)
                        Box(
                            Modifier.size(48.dp).semantics {
                                contentDescription = label
                                selected = option == color
                            }.clickable { color = option }.padding(10.dp)
                                .background(Color(option.argb), CircleShape),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        preset.copy(
                            width = width.roundToInt(),
                            opacity = opacity.roundToInt(),
                            color = color,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    steps: Int = 0,
    onValue: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value, onValue, Modifier.weight(1f), valueRange = valueRange, steps = steps)
        Text(valueText, Modifier.width(48.dp), fontSize = 12.sp)
    }
}

@Composable
private fun ColorDot(color: AnnotationColor) {
    Box(Modifier.size(32.dp).padding(5.dp).background(Color(color.argb), CircleShape))
}

@StringRes
private fun defaultToolLabel(value: ReaderDefaultTool): Int = when (value) {
    ReaderDefaultTool.VIEW -> R.string.select
    ReaderDefaultTool.PEN -> R.string.pen_1
    ReaderDefaultTool.HIGHLIGHTER -> R.string.highlighter
}

@StringRes
private fun textSizeLabel(value: AnnotationTextSize): Int = when (value) {
    AnnotationTextSize.SMALL -> R.string.small
    AnnotationTextSize.MEDIUM -> R.string.medium
    AnnotationTextSize.LARGE -> R.string.large
}

@StringRes
private fun presetLabel(id: String): Int = when (id) {
    "pen-1" -> R.string.pen
    "pen-2" -> R.string.pen_2
    "marker" -> R.string.marker
    "highlighter" -> R.string.highlighter
    else -> error("Unknown drawing preset: $id")
}

private fun dragTranslation(
    key: String,
    index: Int,
    origin: Int,
    target: Int,
    offset: Float,
    rowHeight: Float,
): Float = if (key.isNotEmpty() && origin >= 0 && index == target) {
    offset - (target - origin) * rowHeight
} else {
    0f
}

private fun SnapshotStateList<String>.replaceWith(values: List<String>) {
    clear()
    addAll(values)
}

private fun <T> List<T>.moved(fromIndex: Int, toIndex: Int): List<T> =
    toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
