@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cz.teply.sheetset.ui

import android.graphics.Color as AndroidColor
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cz.teply.sheetset.R
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.AnnotationEditorSettings
import cz.teply.sheetset.pdf.AnnotationRenderer
import cz.teply.sheetset.pdf.AnnotationTextAlignment
import cz.teply.sheetset.pdf.AnnotationToolGroup
import cz.teply.sheetset.pdf.DrawingPreset
import cz.teply.sheetset.pdf.DrawingPresetKind
import cz.teply.sheetset.pdf.InkAnnotation
import cz.teply.sheetset.pdf.MAX_ANNOTATION_WIDTH
import cz.teply.sheetset.pdf.MAX_TEXT_LENGTH
import cz.teply.sheetset.pdf.MIN_ANNOTATION_WIDTH
import cz.teply.sheetset.pdf.NormalizedRect
import cz.teply.sheetset.pdf.PageAnnotation
import cz.teply.sheetset.pdf.ReaderTool
import cz.teply.sheetset.pdf.ShapeAnnotation
import cz.teply.sheetset.pdf.SUPPORTED_SYMBOL_IDS
import cz.teply.sheetset.pdf.SymbolAnnotation
import cz.teply.sheetset.pdf.TextBoxAnnotation
import cz.teply.sheetset.settings.AnnotationTextSize
import java.util.UUID
import kotlin.math.roundToInt

internal const val COLOR_PANEL_SCROLL_TAG = "color-panel-scroll"

private val EditorToolbarAccent = Color(0xFF67558D)

internal fun presetIconColor(preset: DrawingPreset): Color = Color(preset.color.argb)

internal fun parseHexAnnotationColor(raw: String): AnnotationColor? {
    val digits = raw.trim().removePrefix("#")
    if (digits.length != 6 || digits.any { it.digitToIntOrNull(16) == null }) return null
    val rgb = digits.toIntOrNull(16) ?: return null
    return AnnotationColor(0xFF000000.toInt() or rgb)
}

internal fun AnnotationColor.rgbHex(): String =
    "#" + (argb and 0x00FFFFFF).toString(16).uppercase().padStart(6, '0')

internal data class AnnotationToolbarState(
    val group: AnnotationToolGroup,
    val tool: ReaderTool,
    val preset: DrawingPreset,
    val editor: AnnotationEditorSettings,
    val selectedIds: Set<String>,
    val selectedAnnotation: PageAnnotation?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val previousEnabled: Boolean,
    val nextEnabled: Boolean,
    val expanded: Boolean,
    val straightLine: Boolean,
    val width: Int,
    val color: AnnotationColor,
)

@Composable
internal fun AnnotationToolbar(
    state: AnnotationToolbarState,
    onGroup: (AnnotationToolGroup) -> Unit,
    onTool: (ReaderTool) -> Unit,
    onPreset: (String) -> Unit,
    onWidth: (Int) -> Unit,
    onColor: () -> Unit,
    onEyedropper: () -> Unit,
    onStraightLine: () -> Unit,
    onEditText: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDone: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val hasSelection = state.selectedIds.isNotEmpty()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarControl(
                    stringResource(R.string.previous),
                    R.drawable.ic_chevron_left_24,
                    enabled = state.previousEnabled,
                    onClick = onPrevious,
                )
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showsWidth(state)) {
                        WidthSliderControl(state.width, state.color, onWidth)
                    }
                    if (!hasSelection && state.tool in setOf(ReaderTool.PEN, ReaderTool.HIGHLIGHTER)) {
                        ToolbarControl(
                            stringResource(R.string.straight_line),
                            R.drawable.ic_straighten_24,
                            selected = state.straightLine,
                            onClick = onStraightLine,
                        )
                    }
                    if (state.tool != ReaderTool.ERASER) {
                        CurrentColorControl(state.color, onColor)
                        if (state.expanded) {
                            ToolbarControl(
                                stringResource(R.string.eyedropper),
                                R.drawable.ic_colorize_24,
                                onClick = onEyedropper,
                            )
                        }
                    }
                    if (hasSelection) {
                        if (state.selectedAnnotation is TextBoxAnnotation) {
                            ToolbarControl(
                                stringResource(R.string.edit_text),
                                R.drawable.ic_text_fields_24,
                                onClick = onEditText,
                            )
                        }
                        ToolbarControl(
                            stringResource(R.string.duplicate),
                            R.drawable.ic_content_copy_24,
                            onClick = onDuplicate,
                        )
                        ToolbarControl(
                            stringResource(R.string.delete_annotation),
                            R.drawable.ic_delete_24,
                            onClick = onDelete,
                        )
                    }
                }
                ToolbarControl(
                    stringResource(R.string.next),
                    R.drawable.ic_chevron_right_24,
                    enabled = state.nextEnabled,
                    onClick = onNext,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_drag_handle_24),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val targetGroup = if (state.group == AnnotationToolGroup.DRAW) {
                        AnnotationToolGroup.OBJECTS
                    } else {
                        AnnotationToolGroup.DRAW
                    }
                    ToolbarControl(
                        stringResource(
                            if (targetGroup == AnnotationToolGroup.DRAW) {
                                R.string.draw
                            } else {
                                R.string.objects
                            },
                        ),
                        if (targetGroup == AnnotationToolGroup.DRAW) {
                            R.drawable.ic_edit_24
                        } else {
                            R.drawable.ic_view_module_24
                        },
                    ) { onGroup(targetGroup) }
                    if (state.group == AnnotationToolGroup.DRAW) {
                        state.editor.drawOrder.mapNotNull { id ->
                            state.editor.presets.firstOrNull { it.id == id && it.visible }
                        }.forEach { preset ->
                            PresetControl(
                                preset = preset,
                                selected = state.tool != ReaderTool.ERASER && preset.id == state.preset.id,
                                onClick = { onPreset(preset.id) },
                            )
                        }
                    } else {
                        orderedObjectTools(state.editor).forEach { definition ->
                            ToolbarControl(
                                stringResource(definition.label),
                                definition.icon,
                                selected = state.tool == definition.tool,
                            ) { onTool(definition.tool) }
                        }
                    }
                }
                if (state.group == AnnotationToolGroup.DRAW) {
                    ToolbarControl(
                        stringResource(R.string.eraser),
                        R.drawable.ic_eraser_24,
                        selected = state.tool == ReaderTool.ERASER,
                    ) { onTool(ReaderTool.ERASER) }
                }
                ToolbarControl(
                    stringResource(R.string.undo),
                    R.drawable.ic_undo_24,
                    enabled = state.canUndo,
                    onClick = onUndo,
                )
                ToolbarControl(
                    stringResource(R.string.redo),
                    R.drawable.ic_redo_24,
                    enabled = state.canRedo,
                    onClick = onRedo,
                )
                ToolbarControl(
                    stringResource(R.string.done),
                    R.drawable.ic_done_24,
                    onClick = onDone,
                )
            }
        }
    }
}

@Composable
internal fun ColorPanel(
    selected: AnnotationColor,
    opacity: Int,
    quickColors: List<AnnotationColor>,
    recentColors: List<AnnotationColor>,
    onDismiss: () -> Unit,
    onEyedropper: (() -> Unit)?,
    onConfirm: (AnnotationColor, Int) -> Unit,
) {
    val initialHsv = remember(selected) { selected.toHsv() }
    var hue by remember(selected) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(selected) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(selected) { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember(opacity) { mutableIntStateOf(opacity) }
    var advanced by remember(selected) { mutableStateOf(false) }
    var hexInput by remember(selected) { mutableStateOf(selected.rgbHex()) }
    val preview = hsvAnnotationColor(hue, saturation, value)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f

    fun choose(color: AnnotationColor) {
        val hsv = color.toHsv()
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        hexInput = color.rgbHex()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxContentHeight)
                .testTag(COLOR_PANEL_SCROLL_TAG).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.color), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.quick_colors),
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            SwatchRows(quickColors.take(8), preview, includeEncoded = false, onColor = ::choose)
            if (recentColors.isNotEmpty()) {
                Text(
                    stringResource(R.string.recent_colors),
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                SwatchRows(
                    recentColors.take(4),
                    preview,
                    includeEncoded = true,
                    onColor = ::choose,
                )
            }
            LabeledSlider(
                label = stringResource(R.string.opacity),
                state = "${(alpha * 100f / 255f).roundToInt()}%",
                value = alpha.toFloat(),
                range = 0f..255f,
                onValue = { alpha = it.roundToInt() },
            )
            TextButton(onClick = { advanced = !advanced }) {
                Text(stringResource(R.string.custom_color))
            }
            if (advanced) {
                ColorSpectrum(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                ) { nextSaturation, nextValue ->
                    saturation = nextSaturation
                    value = nextValue
                    hexInput = hsvAnnotationColor(hue, nextSaturation, nextValue).rgbHex()
                }
                ColorSlider(R.string.hue, hue, 0f..360f) { nextHue ->
                    hue = nextHue
                    hexInput = hsvAnnotationColor(nextHue, saturation, value).rgbHex()
                }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input.uppercase().take(7)
                        parseHexAnnotationColor(hexInput)?.let { color ->
                            val hsv = color.toHsv()
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                        }
                    },
                    label = { Text("HEX") },
                    singleLine = true,
                    isError = parseHexAnnotationColor(hexInput) == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorPreview(preview, alpha)
                Text(
                    if (advanced) preview.rgbHex() else annotationColorLabel(preview),
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (onEyedropper != null) {
                    TextButton(onClick = onEyedropper) {
                        Icon(painterResource(R.drawable.ic_colorize_24), contentDescription = null)
                        Text(stringResource(R.string.eyedropper), Modifier.padding(start = 8.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    enabled = parseHexAnnotationColor(hexInput) != null,
                    onClick = { onConfirm(preview, alpha) },
                ) {
                    Text(stringResource(R.string.apply))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun TextAnnotationDialog(
    initial: TextBoxAnnotation?,
    bounds: NormalizedRect,
    defaultSize: AnnotationTextSize,
    defaultColor: AnnotationColor,
    defaultOpacity: Int,
    quickColors: List<AnnotationColor>,
    recentColors: List<AnnotationColor>,
    onDismiss: () -> Unit,
    onConfirm: (TextBoxAnnotation) -> Unit,
) {
    var text by rememberSaveable(initial?.id, bounds) { mutableStateOf(initial?.text.orEmpty()) }
    var size by rememberSaveable(initial?.id, bounds) { mutableStateOf(initial?.size ?: defaultSize) }
    var lineHeight by rememberSaveable(initial?.id, bounds) { mutableFloatStateOf(initial?.lineHeight ?: 1.2f) }
    var alignment by rememberSaveable(initial?.id, bounds) {
        mutableStateOf(initial?.alignment ?: AnnotationTextAlignment.START)
    }
    var colorArgb by rememberSaveable(initial?.id, bounds) { mutableIntStateOf((initial?.color ?: defaultColor).argb) }
    val color = AnnotationColor(colorArgb)
    var opacity by rememberSaveable(initial?.id, bounds) { mutableIntStateOf(initial?.opacity ?: defaultOpacity) }
    var colorOpen by rememberSaveable(initial?.id, bounds) { mutableStateOf(false) }
    var moreOpen by rememberSaveable(initial?.id, bounds) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(stringResource(if (initial == null) R.string.text_box else R.string.edit_text)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_TEXT_LENGTH) },
                    label = { Text(stringResource(R.string.text_box)) },
                    supportingText = { Text("${text.length} / $MAX_TEXT_LENGTH") },
                    minLines = 3,
                    maxLines = 8,
                )
                Text(stringResource(R.string.text_size), Modifier.padding(top = 12.dp))
                ChoiceButtons(
                    values = AnnotationTextSize.entries,
                    selected = size,
                    label = { stringResource(it.label()) },
                    onSelect = { size = it },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CurrentColorControl(color) { colorOpen = true }
                    Text(annotationColorLabel(color), Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = { moreOpen = !moreOpen }) {
                    Text(stringResource(R.string.more_options))
                }
                if (moreOpen) {
                    LabeledSlider(
                        label = stringResource(R.string.line_height),
                        state = String.format(java.util.Locale.ROOT, "%.1f", lineHeight),
                        value = lineHeight,
                        range = 0.8f..2f,
                        onValue = { lineHeight = it },
                    )
                    Text(stringResource(R.string.alignment), Modifier.padding(top = 8.dp))
                    ChoiceButtons(
                        values = AnnotationTextAlignment.entries,
                        selected = alignment,
                        label = { stringResource(it.label()) },
                        onSelect = { alignment = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onConfirm(
                        TextBoxAnnotation(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            bounds = initial?.bounds ?: bounds,
                            text = text.trim(),
                            size = size,
                            lineHeight = lineHeight,
                            alignment = alignment,
                            color = color,
                            opacity = opacity,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    if (colorOpen) {
        ColorPanel(
            selected = color,
            opacity = opacity,
            quickColors = quickColors,
            recentColors = recentColors,
            onDismiss = { colorOpen = false },
            onEyedropper = null,
            onConfirm = { nextColor, nextOpacity ->
                colorArgb = nextColor.argb
                opacity = nextOpacity
                colorOpen = false
            },
        )
    }
}

@Composable
internal fun MusicalSymbolChooser(
    selected: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val symbolFont = FontFamily(Font(R.font.noto_music_regular))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.musical_symbol)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SUPPORTED_SYMBOL_IDS.sorted().chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { id ->
                            val label = stringResource(symbolLabel(id))
                            TextButton(
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                                    contentDescription = label
                                    this.selected = id == selected
                                },
                                onClick = { onSelected(id) },
                            ) {
                                Text(
                                    AnnotationRenderer.symbolGlyph(id),
                                    fontFamily = symbolFont,
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                Text(label, Modifier.padding(start = 8.dp))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PresetControl(preset: DrawingPreset, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(preset.label())
    ToolbarControl(
        label = label,
        icon = if (preset.kind == DrawingPresetKind.HIGHLIGHTER) {
            R.drawable.ic_highlighter_24
        } else {
            R.drawable.ic_edit_24
        },
        selected = selected,
        tint = presetIconColor(preset),
        state = preset.color.rgbHex(),
        onClick = onClick,
    )
}

@Composable
private fun ToolbarControl(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean? = null,
    tint: Color? = null,
    state: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        modifier = Modifier.size(48.dp)
            .background(
                if (selected == true) MaterialTheme.colorScheme.surface else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .then(
                if (selected == true) {
                    Modifier.border(2.dp, EditorToolbarAccent, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            ).semantics {
            contentDescription = label
            selected?.let { this.selected = it }
            state?.let { stateDescription = it }
        },
        enabled = enabled,
        onClick = onClick,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                tint != null -> tint
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun WidthSliderControl(width: Int, color: AnnotationColor, onWidth: (Int) -> Unit) {
    val label = stringResource(R.string.stroke_width)
    val outline = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.width(220.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(48.dp).height(32.dp).testTag("stroke-width-preview")) {
            val strokeWidth = (1f + (width - MIN_ANNOTATION_WIDTH) * 2.6f).dp.toPx()
            val start = Offset(6.dp.toPx(), size.height / 2f)
            val end = Offset(size.width - 6.dp.toPx(), size.height / 2f)
            drawLine(
                outline,
                start,
                end,
                strokeWidth + 2.dp.toPx(),
                StrokeCap.Round,
            )
            drawLine(Color(color.argb), start, end, strokeWidth, StrokeCap.Round)
        }
        Slider(
            value = width.toFloat(),
            onValueChange = { value ->
                val next = value.roundToInt().coerceIn(MIN_ANNOTATION_WIDTH, MAX_ANNOTATION_WIDTH)
                if (next != width) onWidth(next)
            },
            valueRange = MIN_ANNOTATION_WIDTH.toFloat()..MAX_ANNOTATION_WIDTH.toFloat(),
            steps = MAX_ANNOTATION_WIDTH - MIN_ANNOTATION_WIDTH - 1,
            modifier = Modifier.width(172.dp).semantics {
                contentDescription = label
                stateDescription = "$width / $MAX_ANNOTATION_WIDTH"
            },
        )
    }
}

@Composable
private fun CurrentColorControl(color: AnnotationColor, onClick: () -> Unit) {
    val label = stringResource(R.string.color)
    val colorName = annotationColorDescription(color, includeEncoded = true)
    val outline = MaterialTheme.colorScheme.onSurface
    IconButton(
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = label
            stateDescription = colorName
        },
        onClick = onClick,
    ) {
        Canvas(Modifier.size(32.dp)) {
            drawCircle(Color(color.argb), radius = 11.dp.toPx())
            drawCircle(outline, radius = 14.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun ColorSwatch(
    color: AnnotationColor,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.onSurface
    IconButton(
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = label
            this.selected = selected
        },
        onClick = onClick,
    ) {
        Canvas(Modifier.size(32.dp)) {
            drawCircle(Color(color.argb), radius = 11.dp.toPx())
            drawCircle(
                if (selected) outline else Color.Gray,
                radius = if (selected) 15.dp.toPx() else 13.dp.toPx(),
                style = Stroke(if (selected) 3.dp.toPx() else 1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun SwatchRows(
    colors: List<AnnotationColor>,
    selected: AnnotationColor,
    includeEncoded: Boolean,
    onColor: (AnnotationColor) -> Unit,
) {
    colors.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            row.forEach { color ->
                ColorSwatch(
                    color = color,
                    label = annotationColorDescription(color, includeEncoded),
                    selected = color == selected,
                    onClick = { onColor(color) },
                )
            }
            repeat(4 - row.size) { Spacer(Modifier.size(48.dp)) }
        }
    }
}

@Composable
private fun ColorSpectrum(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    val label = stringResource(R.string.custom_color)
    val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    fun update(point: Offset, width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        onChange(
            (point.x / width).coerceIn(0f, 1f),
            (1f - point.y / height).coerceIn(0f, 1f),
        )
    }
    Canvas(
        Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(12.dp))
            .testTag("color-spectrum")
            .semantics {
                contentDescription = label
                stateDescription = hsvAnnotationColor(hue, saturation, value).rgbHex()
            }
            .pointerInput(hue) {
                detectDragGestures(
                    onDragStart = { point -> update(point, size.width.toFloat(), size.height.toFloat()) },
                    onDrag = { change, _ ->
                        change.consume()
                        update(change.position, size.width.toFloat(), size.height.toFloat())
                    },
                )
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val center = Offset(saturation * size.width, (1f - value) * size.height)
        drawCircle(Color.Black, radius = 10.dp.toPx(), center = center, style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, radius = 8.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun ColorSlider(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    LabeledSlider(
        label = stringResource(label),
        state = if (range.endInclusive == 360f) value.roundToInt().toString() else {
            "${(value * 100f).roundToInt()}%"
        },
        value = value,
        range = range,
        onValue = onValue,
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    state: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = label
                stateDescription = state
            },
        )
        Text(state, Modifier.width(48.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun ColorPreview(color: AnnotationColor, opacity: Int) {
    val description = stringResource(R.string.current_color_preview)
    Canvas(Modifier.size(48.dp).semantics { contentDescription = description }) {
        drawCircle(Color(color.argb).copy(alpha = opacity / 255f), radius = 18.dp.toPx())
        drawCircle(Color.Gray, radius = 20.dp.toPx(), style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun <T> ChoiceButtons(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { value ->
            val text = label(value)
            val isSelected = value == selected
            TextButton(
                modifier = Modifier.weight(1f)
                    .background(
                        if (isSelected) EditorToolbarAccent.copy(alpha = 0.12f) else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, EditorToolbarAccent, RoundedCornerShape(10.dp))
                        } else {
                            Modifier
                        },
                    ).semantics { this.selected = isSelected },
                onClick = { onSelect(value) },
            ) { Text(text, maxLines = 1) }
        }
    }
}

private fun orderedObjectTools(editor: AnnotationEditorSettings): List<ObjectToolMetadata> =
    editor.objectOrder.mapNotNull { id ->
        objectToolMetadata(id)?.takeIf { id in editor.visibleObjectTools }
    }

private fun showsWidth(state: AnnotationToolbarState): Boolean = when {
    state.selectedIds.isNotEmpty() ->
        state.selectedAnnotation is InkAnnotation ||
            state.selectedAnnotation is ShapeAnnotation ||
            state.selectedAnnotation is SymbolAnnotation
    else -> state.tool in setOf(
        ReaderTool.PEN,
        ReaderTool.HIGHLIGHTER,
        ReaderTool.LINE,
        ReaderTool.ARROW,
        ReaderTool.RECTANGLE,
        ReaderTool.ELLIPSE,
    )
}

@Composable
private fun annotationColorDescription(color: AnnotationColor, includeEncoded: Boolean): String {
    val label = annotationColorLabel(color)
    return if (includeEncoded) "$label ${color.rgbHex()}" else label
}

private fun AnnotationColor.toHsv(): FloatArray = FloatArray(3).also {
    AndroidColor.colorToHSV(argb, it)
}

private fun hsvAnnotationColor(hue: Float, saturation: Float, value: Float): AnnotationColor =
    AnnotationColor(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))

@StringRes
private fun DrawingPreset.label(): Int = when (id) {
    "pen-1" -> R.string.pen
    "pen-2" -> R.string.pen_2
    "marker" -> R.string.marker
    "highlighter" -> R.string.highlighter
    else -> R.string.pen
}

@StringRes
private fun AnnotationTextSize.label(): Int = when (this) {
    AnnotationTextSize.SMALL -> R.string.small
    AnnotationTextSize.MEDIUM -> R.string.medium
    AnnotationTextSize.LARGE -> R.string.large
}

@StringRes
private fun AnnotationTextAlignment.label(): Int = when (this) {
    AnnotationTextAlignment.START -> R.string.alignment_start
    AnnotationTextAlignment.CENTER -> R.string.alignment_center
    AnnotationTextAlignment.END -> R.string.alignment_end
}

@StringRes
private fun symbolLabel(id: String): Int = when (id) {
    "sharp" -> R.string.symbol_sharp
    "flat" -> R.string.symbol_flat
    "natural" -> R.string.symbol_natural
    "fermata" -> R.string.symbol_fermata
    "accent" -> R.string.symbol_accent
    "breath" -> R.string.symbol_breath
    "crescendo" -> R.string.symbol_crescendo
    "decrescendo" -> R.string.symbol_decrescendo
    "p" -> R.string.symbol_p
    "mf" -> R.string.symbol_mf
    "f" -> R.string.symbol_f
    "ff" -> R.string.symbol_ff
    else -> error("Unsupported symbol ID")
}
