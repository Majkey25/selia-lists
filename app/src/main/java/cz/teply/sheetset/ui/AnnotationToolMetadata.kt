package cz.teply.sheetset.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cz.teply.sheetset.R
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.AnnotationEditorSettings
import cz.teply.sheetset.pdf.DrawingPreset
import cz.teply.sheetset.pdf.DrawingPresetKind
import cz.teply.sheetset.pdf.PERSISTED_OBJECT_TOOLS
import cz.teply.sheetset.pdf.ReaderTool
import cz.teply.sheetset.pdf.persistedObjectToolId
import cz.teply.sheetset.settings.ReaderDefaultTool

internal data class ObjectToolMetadata(
    val id: String,
    val tool: ReaderTool,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
)

internal val objectToolMetadata = PERSISTED_OBJECT_TOOLS.map { persisted ->
    val (label, icon) = when (persisted.readerTool) {
        ReaderTool.SELECT -> R.string.select to R.drawable.ic_gesture_24
        ReaderTool.LASSO -> R.string.lasso to R.drawable.ic_select_24
        ReaderTool.TEXT_BOX -> R.string.plain_text_tool to R.drawable.ic_text_fields_24
        ReaderTool.SYMBOL -> R.string.musical_symbol to R.drawable.ic_music_note_24
        ReaderTool.UNDERLINE -> R.string.underline to R.drawable.ic_underline_24
        ReaderTool.STRIKE_THROUGH -> R.string.strike_through to R.drawable.ic_strikethrough_24
        ReaderTool.LINE -> R.string.line to R.drawable.ic_line_24
        ReaderTool.ARROW -> R.string.arrow to R.drawable.ic_arrow_forward_24
        ReaderTool.RECTANGLE -> R.string.rectangle to R.drawable.ic_rectangle_24
        ReaderTool.ELLIPSE -> R.string.ellipse to R.drawable.ic_ellipse_24
        else -> error("Unsupported persisted object tool: ${persisted.readerTool}")
    }
    ObjectToolMetadata(persisted.id, persisted.readerTool, label, icon)
}

internal fun objectToolMetadata(id: String): ObjectToolMetadata? =
    objectToolMetadata.firstOrNull { it.id == id }

internal fun ReaderDefaultTool.requestedReaderTool(): ReaderTool = when (this) {
    ReaderDefaultTool.VIEW -> ReaderTool.SELECT
    ReaderDefaultTool.PEN -> ReaderTool.PEN
    ReaderDefaultTool.HIGHLIGHTER -> ReaderTool.HIGHLIGHTER
}

internal fun DrawingPreset.readerTool(): ReaderTool = when (kind) {
    DrawingPresetKind.PEN, DrawingPresetKind.MARKER -> ReaderTool.PEN
    DrawingPresetKind.HIGHLIGHTER -> ReaderTool.HIGHLIGHTER
}

internal data class VisibleEditorSelection(
    val tool: ReaderTool,
    val preset: DrawingPreset,
)

internal fun AnnotationEditorSettings.resolveVisibleSelection(
    preferredTool: ReaderTool,
    preferredPresetId: String? = null,
): VisibleEditorSelection {
    val visiblePresets = drawOrder.map(::preset).filter(DrawingPreset::visible)
    val visibleObjects = objectOrder.asSequence()
        .filter(visibleObjectTools::contains)
        .mapNotNull(::objectToolMetadata)
        .toList()
    val preferredObjectId = preferredTool.persistedObjectToolId()
    val requestedPreset = visiblePresets.firstOrNull { preset ->
        preset.id == preferredPresetId && preset.matches(preferredTool)
    }
    val sameGroupTool = when {
        preferredTool == ReaderTool.ERASER -> ReaderTool.ERASER
        preferredTool in drawingReaderTools ->
            requestedPreset?.readerTool()
                ?: visiblePresets.firstOrNull { it.matches(preferredTool) }?.readerTool()
                ?: visiblePresets.firstOrNull()?.readerTool()
        preferredObjectId != null ->
            visibleObjects.firstOrNull { it.id == preferredObjectId }?.tool
                ?: visibleObjects.firstOrNull()?.tool
        else -> null
    }
    val resolvedTool = sameGroupTool
        ?: visiblePresets.firstOrNull()?.readerTool()
        ?: visibleObjects.first().tool
    val resolvedPreset = visiblePresets.firstOrNull { preset ->
        preset.id == preferredPresetId && preset.matches(resolvedTool)
    } ?: visiblePresets.firstOrNull { it.matches(resolvedTool) }
        ?: visiblePresets.first()
    return VisibleEditorSelection(resolvedTool, resolvedPreset)
}

private fun DrawingPreset.matches(tool: ReaderTool): Boolean = when (tool) {
    ReaderTool.HIGHLIGHTER -> kind == DrawingPresetKind.HIGHLIGHTER
    ReaderTool.PEN -> kind != DrawingPresetKind.HIGHLIGHTER
    else -> true
}

private val drawingReaderTools = setOf(ReaderTool.PEN, ReaderTool.HIGHLIGHTER)

@Composable
internal fun annotationColorLabel(color: AnnotationColor): String = stringResource(
    when (color) {
        AnnotationColor.BLACK -> R.string.color_black
        AnnotationColor.RED -> R.string.color_red
        AnnotationColor.ORANGE -> R.string.color_orange
        AnnotationColor.YELLOW -> R.string.color_yellow
        AnnotationColor.GREEN -> R.string.color_green
        AnnotationColor.BLUE -> R.string.color_blue
        AnnotationColor.PURPLE -> R.string.color_purple
        AnnotationColor.PINK -> R.string.color_pink
        else -> R.string.custom_color
    },
)
