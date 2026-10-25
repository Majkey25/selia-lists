package cz.teply.sheetset.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cz.teply.sheetset.R
import cz.teply.sheetset.ReaderUiState
import cz.teply.sheetset.pdf.AnnotationColor
import cz.teply.sheetset.pdf.AnnotationEditorSettings
import cz.teply.sheetset.pdf.AnnotationHistory
import cz.teply.sheetset.pdf.AnnotationToolGroup
import cz.teply.sheetset.pdf.DrawingPreset
import cz.teply.sheetset.pdf.InkAnnotation
import cz.teply.sheetset.pdf.InkKind
import cz.teply.sheetset.pdf.MarkupAnnotation
import cz.teply.sheetset.pdf.NormalizedPoint
import cz.teply.sheetset.pdf.NormalizedRect
import cz.teply.sheetset.pdf.PageAnnotation
import cz.teply.sheetset.pdf.PdfPageView
import cz.teply.sheetset.pdf.ReaderTool
import cz.teply.sheetset.pdf.ShapeAnnotation
import cz.teply.sheetset.pdf.SymbolAnnotation
import cz.teply.sheetset.pdf.TextBoxAnnotation
import cz.teply.sheetset.pdf.canAppendAnnotations
import cz.teply.sheetset.pdf.duplicateSelection
import cz.teply.sheetset.pdf.manualMarkup
import cz.teply.sheetset.pdf.annotationWidthLevel
import cz.teply.sheetset.pdf.highlighterWidthLevel
import cz.teply.sheetset.pdf.normalizedAnnotationWidth
import cz.teply.sheetset.pdf.normalizedHighlighterWidth
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.ReaderLayout
import java.util.UUID
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val PDF_PAGE_TEST_TAG = "pdf-page"

private val textBoundsSaver = listSaver<NormalizedRect?, Float>(
    save = { bounds -> bounds?.let { listOf(it.left, it.top, it.right, it.bottom) }.orEmpty() },
    restore = { values ->
        if (values.isEmpty()) null else NormalizedRect(values[0], values[1], values[2], values[3])
    },
)

internal class EditorSettingsState(initial: AnnotationEditorSettings) {
    var current by mutableStateOf(initial)
        private set

    fun replace(value: AnnotationEditorSettings) {
        current = value
    }

    fun update(transform: (AnnotationEditorSettings) -> AnnotationEditorSettings): AnnotationEditorSettings =
        transform(current).also { current = it }
}

@Composable
fun ReaderScreen(
    reader: ReaderUiState,
    settings: AppSettings,
    windowLayout: WindowLayout,
    actions: SheetSetActions,
) {
    val initialSelection = settings.editor.resolveVisibleSelection(
        settings.defaultTool.requestedReaderTool(),
    )
    val editorState = remember(reader.score.id) { EditorSettingsState(settings.editor) }
    val editor = editorState.current
    var tool by remember(reader.score.id) { mutableStateOf(ReaderTool.VIEW) }
    var lastEditorTool by remember(reader.score.id) { mutableStateOf(initialSelection.tool) }
    var controlsVisible by remember { mutableStateOf(true) }
    var autoHideRequest by remember { mutableIntStateOf(0) }
    var selectedAnnotationIds by remember(reader.score.id, reader.pageIndex) {
        mutableStateOf(emptySet<String>())
    }
    var group by remember(reader.score.id) { mutableStateOf(AnnotationToolGroup.OBJECTS) }
    var activePresetId by remember(reader.score.id) {
        mutableStateOf(initialSelection.preset.id)
    }
    var objectColor by remember(reader.score.id) { mutableStateOf(AnnotationColor.BLACK) }
    var objectOpacity by remember(reader.score.id) { mutableIntStateOf(255) }
    var objectWidth by remember(reader.score.id) { mutableIntStateOf(2) }
    var straightLine by remember(reader.score.id) { mutableStateOf(false) }
    var textBounds by key(reader.score.id, reader.pageIndex) {
        rememberSaveable(stateSaver = textBoundsSaver) { mutableStateOf<NormalizedRect?>(null) }
    }
    var editingTextId by key(reader.score.id, reader.pageIndex) {
        rememberSaveable { mutableStateOf<String?>(null) }
    }
    var symbolPoint by remember { mutableStateOf<NormalizedPoint?>(null) }
    var selectedSymbolId by remember { mutableStateOf("sharp") }
    var colorPanelOpen by remember { mutableStateOf(false) }
    var pageView by remember { mutableStateOf<PdfPageView?>(null) }
    var readerPanel by rememberSaveable(reader.score.id) { mutableStateOf<ReaderPanel?>(null) }
    var history by remember(reader.score.id, reader.pageIndex) {
        mutableStateOf(AnnotationHistory(reader.annotations.pages[reader.pageIndex].orEmpty()))
    }
    val editingText = history.annotations.firstOrNull { it.id == editingTextId } as? TextBoxAnnotation
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val sliderDragging by sliderInteractionSource.collectIsDraggedAsState()
    val sliderPressed by sliderInteractionSource.collectIsPressedAsState()
    val platformView = LocalView.current
    val readerFocusRequester = remember { FocusRequester() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> uri?.let(actions.exportPdf) }
    val effectiveLayout = if (tool != ReaderTool.VIEW) {
        ReaderLayout.SINGLE
    } else {
        effectiveReaderLayout(settings.readerLayout, windowLayout != WindowLayout.COMPACT)
    }
    val activePreset = editor.resolveVisibleSelection(lastEditorTool, activePresetId).preset
    val selectedAnnotations = history.annotations.filter { it.id in selectedAnnotationIds }
    val selectedAnnotation = selectedAnnotations.singleOrNull()
    val currentColor = selectedAnnotations.firstOrNull()?.annotationColor()
        ?: if (group == AnnotationToolGroup.DRAW) activePreset.color else objectColor
    val currentOpacity = selectedAnnotations.firstOrNull()?.annotationOpacity()
        ?: if (group == AnnotationToolGroup.DRAW) activePreset.opacity else objectOpacity
    val currentWidth = selectedAnnotation?.toolbarWidth()
        ?: if (group == AnnotationToolGroup.DRAW) activePreset.width else objectWidth
    val pageDescription = stringResource(
        R.string.pdf_page,
        reader.pageIndex + 1,
        reader.score.pageCount,
    )
    val actionFailed = stringResource(R.string.action_failed)

    LaunchedEffect(settings.editor) {
        editorState.replace(settings.editor)
    }
    DisposableEffect(platformView, settings.keepScreenAwake) {
        val previous = platformView.keepScreenOn
        platformView.keepScreenOn = settings.keepScreenAwake
        onDispose { platformView.keepScreenOn = previous }
    }
    LaunchedEffect(reader.score.id, reader.pageIndex) {
        selectedAnnotationIds = emptySet()
    }
    LaunchedEffect(reader.score.id) {
        readerFocusRequester.requestFocus()
    }
    LaunchedEffect(autoHideRequest, settings.autoHideControls, sliderDragging, sliderPressed) {
        if (autoHideRequest > 0 && settings.autoHideControls && !sliderDragging && !sliderPressed) {
            delay(1_500)
            if (tool == ReaderTool.VIEW && controlsVisible) controlsVisible = false
        }
    }

    fun updateHistory(next: AnnotationHistory) {
        if (next == history) return
        history = next
        actions.saveAnnotations(
            reader.score.id,
            reader.pageIndex,
            reader.annotations.withPage(reader.pageIndex, next.annotations),
        )
    }

    fun addAnnotation(annotation: PageAnnotation) {
        if (!canAppendAnnotations(history.annotations.size, 1)) {
            Toast.makeText(platformView.context, actionFailed, Toast.LENGTH_SHORT).show()
            return
        }
        updateHistory(history.add(annotation))
    }

    fun updateEditor(transform: (AnnotationEditorSettings) -> AnnotationEditorSettings) {
        val next = editorState.update(transform)
        actions.updateSettings(settings.copy(editor = next))
    }

    fun updatePreset(transform: (DrawingPreset) -> DrawingPreset) = updateEditor { latest ->
        latest.copy(
            presets = latest.presets.map { preset ->
                if (preset.id == activePresetId) transform(preset) else preset
            },
        )
    }

    fun saveRecentColor(color: AnnotationColor) {
        if (color in editorState.current.quickColors) return
        updateEditor { latest -> latest.copy(recentColors = latest.recentColors.withRecent(color)) }
    }

    fun applyAppearance(color: AnnotationColor, opacity: Int, addRecent: Boolean = false) {
        if (selectedAnnotationIds.isNotEmpty()) {
            val next = history.annotations.map { annotation ->
                if (annotation.id in selectedAnnotationIds) {
                    annotation.withAppearance(color, opacity)
                } else {
                    annotation
                }
            }
            updateHistory(history.commit(next))
            if (addRecent) {
                saveRecentColor(color)
            }
        } else if (group == AnnotationToolGroup.DRAW) {
            updateEditor { latest ->
                latest.copy(
                    presets = latest.presets.map { preset ->
                        if (preset.id == activePresetId) {
                        preset.copy(color = color, opacity = opacity)
                        } else {
                            preset
                        }
                    },
                    recentColors = if (addRecent && color !in latest.quickColors) {
                        latest.recentColors.withRecent(color)
                    } else {
                        latest.recentColors
                    },
                )
            }
        } else {
            objectColor = color
            objectOpacity = opacity
            if (addRecent) saveRecentColor(color)
        }
    }

    fun changeWidth(width: Int) {
        if (selectedAnnotationIds.isNotEmpty()) {
            val next = history.annotations.map { annotation ->
                if (annotation.id in selectedAnnotationIds) annotation.withToolbarWidth(width) else annotation
            }
            updateHistory(history.commit(next))
        } else if (group == AnnotationToolGroup.DRAW) {
            updatePreset { it.copy(width = width) }
        } else {
            objectWidth = width
        }
    }

    fun switchTool(next: ReaderTool) {
        if (next == ReaderTool.VIEW) {
            tool = next
        } else {
            val resolved = editorState.current.resolveVisibleSelection(next, activePresetId)
            tool = resolved.tool
            lastEditorTool = resolved.tool
            activePresetId = resolved.preset.id
        }
        selectedAnnotationIds = emptySet()
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .focusRequester(readerFocusRequester).focusable().onPreviewKeyEvent { event ->
            val direction = pedalDirection(event.nativeKeyEvent.keyCode, repeatCount = 0)
            if (direction == null || textBounds != null || readerPanel != null || colorPanelOpen || symbolPoint != null) {
                false
            } else {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    when (direction) {
                        PageDirection.PREVIOUS -> actions.previousPage(effectiveLayout)
                        PageDirection.NEXT -> actions.nextPage(effectiveLayout)
                    }
                }
                true
            }
        },
    ) {
        if (effectiveLayout == ReaderLayout.TWO_PAGE) {
            TwoPageReader(
                reader = reader,
                settings = settings,
                actions = actions,
                onToggleControls = {
                    controlsVisible = !controlsVisible
                    if (controlsVisible && settings.autoHideControls) autoHideRequest++
                },
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag(PDF_PAGE_TEST_TAG).semantics {
                    contentDescription = pageDescription
                },
                factory = ::PdfPageView,
                update = { view ->
                if (pageView !== view) pageView = view
                view.contentDescription = pageDescription
                view.tool = tool
                view.editorSettings = editor
                view.activeDrawingPreset = activePreset
                view.annotations = history.annotations
                view.selectedAnnotationIds = selectedAnnotationIds
                view.annotationColor = currentColor
                view.annotationOpacity = currentOpacity
                view.shapeWidth = objectWidth.normalizedAnnotationWidth()
                view.straightLine = straightLine
                view.textSize = settings.textSize
                view.pageFit = if (effectiveLayout == ReaderLayout.HALF) {
                    cz.teply.sheetset.settings.PageFit.WIDTH
                } else {
                    settings.pageFit
                }
                view.setHalfPagePart(
                    if (effectiveLayout == ReaderLayout.HALF) reader.pagePart else 0,
                    halfPageMode = effectiveLayout == ReaderLayout.HALF,
                )
                view.pageTurnTaps = settings.pageTurnTaps
                view.pageTurnSwipes = settings.pageTurnSwipes
                view.onPreviousPage = {
                    actions.previousPage(effectiveLayout)
                }
                view.onNextPage = {
                    actions.nextPage(effectiveLayout)
                }
                view.onPageClick = {
                    if (tool == ReaderTool.VIEW) {
                        controlsVisible = !controlsVisible
                        if (controlsVisible && settings.autoHideControls) autoHideRequest++
                    }
                }
                view.onSelectionChange = { selectedAnnotationIds = it }
                view.onAddAnnotation = ::addAnnotation
                view.onUpdateAnnotations = { updateHistory(history.commit(it)) }
                view.onDeleteAnnotations = { ids ->
                    selectedAnnotationIds = selectedAnnotationIds - ids
                    updateHistory(history.commit(history.annotations.filterNot { it.id in ids }))
                }
                view.onRequestText = { bounds ->
                    editingTextId = null
                    textBounds = bounds
                }
                view.onRequestSymbol = { point ->
                    selectedSymbolId = "sharp"
                    symbolPoint = point
                }
                view.onSampleColor = { sampled ->
                    applyAppearance(sampled, currentOpacity, addRecent = true)
                }
                view.onRequestMarkup = { kind, start, end ->
                    addAnnotation(
                        MarkupAnnotation(
                            id = UUID.randomUUID().toString(),
                            kind = kind,
                            bounds = manualMarkup(start, end),
                            color = currentColor,
                            opacity = currentOpacity,
                        ),
                    )
                }
                view.onRenderError = {
                    Toast.makeText(view.context, actionFailed, Toast.LENGTH_SHORT).show()
                }
                view.showPage(reader.file, reader.pageIndex)
                },
            )
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(140)),
        ) {
            ReaderTopBar(
                title = reader.score.title,
                onClose = actions.closeReader,
                onExport = {
                    exportLauncher.launch(
                        reader.score.title.replace(Regex("[\\/:*?\"<>|]"), "_")
                            .take(100) + "-annotated.pdf",
                    )
                },
            )
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(140)),
        ) {
            if (tool == ReaderTool.VIEW) {
                ReaderNavigationBar(
                    reader = reader,
                    layout = effectiveLayout,
                    onPrevious = { actions.previousPage(effectiveLayout) },
                    onNext = { actions.nextPage(effectiveLayout) },
                    onJump = actions.jumpToPage,
                    onPanel = { readerPanel = it },
                    sliderInteractionSource = sliderInteractionSource,
                ) {
                    val restored = editor.resolveVisibleSelection(lastEditorTool, activePresetId)
                    lastEditorTool = restored.tool
                    activePresetId = restored.preset.id
                    tool = restored.tool
                    group = if (restored.tool in setOf(ReaderTool.PEN, ReaderTool.HIGHLIGHTER, ReaderTool.ERASER)) {
                        AnnotationToolGroup.DRAW
                    } else {
                        AnnotationToolGroup.OBJECTS
                    }
                    controlsVisible = true
                }
            } else {
                val previousEnabled = reader.pageIndex > 0 || reader.scoreIndex > 0
                val nextEnabled = reader.pageIndex < reader.score.pageCount - 1 ||
                    reader.scoreIndex < reader.scoreIds.lastIndex
                AnnotationToolbar(
                    state = AnnotationToolbarState(
                        group = group,
                        tool = tool,
                        preset = activePreset,
                        editor = editor,
                        selectedIds = selectedAnnotationIds,
                        selectedAnnotation = selectedAnnotation,
                        canUndo = history.canUndo,
                        canRedo = history.canRedo,
                        previousEnabled = previousEnabled,
                        nextEnabled = nextEnabled,
                        expanded = windowLayout == WindowLayout.EXPANDED,
                        straightLine = straightLine,
                        width = currentWidth,
                        color = currentColor,
                    ),
                    onGroup = { nextGroup ->
                        group = nextGroup
                        val resolved = editor.resolveVisibleSelection(
                            preferredTool = if (nextGroup == AnnotationToolGroup.DRAW) {
                                activePreset.readerTool()
                            } else {
                                ReaderTool.SELECT
                            },
                            preferredPresetId = activePresetId,
                        )
                        activePresetId = resolved.preset.id
                        switchTool(resolved.tool)
                    },
                    onTool = { switchTool(it) },
                    onPreset = { presetId ->
                        activePresetId = presetId
                        group = AnnotationToolGroup.DRAW
                        switchTool(editor.preset(presetId).readerTool())
                    },
                    onWidth = ::changeWidth,
                    onColor = { colorPanelOpen = true },
                    onEyedropper = { pageView?.startEyedropper() },
                    onStraightLine = { straightLine = !straightLine },
                    onEditText = {
                        val text = selectedAnnotation as? TextBoxAnnotation
                        editingTextId = text?.id
                        textBounds = text?.bounds
                    },
                    onDuplicate = duplicate@{
                        val before = history.annotations
                        val selectedCount = before.count { it.id in selectedAnnotationIds }
                        if (!canAppendAnnotations(before.size, selectedCount)) {
                            Toast.makeText(
                                platformView.context,
                                actionFailed,
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@duplicate
                        }
                        val duplicated = before.duplicateSelection(selectedAnnotationIds) {
                            UUID.randomUUID().toString()
                        }
                        selectedAnnotationIds = duplicated.drop(before.size)
                            .mapTo(mutableSetOf(), PageAnnotation::id)
                        updateHistory(history.commit(duplicated))
                    },
                    onDelete = {
                        if (selectedAnnotationIds.isNotEmpty()) {
                            val ids = selectedAnnotationIds
                            selectedAnnotationIds = emptySet()
                            updateHistory(history.commit(history.annotations.filterNot { it.id in ids }))
                        }
                    },
                    onUndo = {
                        selectedAnnotationIds = emptySet()
                        updateHistory(history.undo())
                    },
                    onRedo = {
                        selectedAnnotationIds = emptySet()
                        updateHistory(history.redo())
                    },
                    onDone = {
                        selectedAnnotationIds = emptySet()
                        tool = ReaderTool.VIEW
                        controlsVisible = true
                    },
                    onPrevious = {
                        selectedAnnotationIds = emptySet()
                        actions.previousPage(ReaderLayout.SINGLE)
                    },
                    onNext = {
                        selectedAnnotationIds = emptySet()
                        actions.nextPage(ReaderLayout.SINGLE)
                    },
                )
            }
        }
    }

    readerPanel?.let { panel ->
        PerformanceToolsSheet(
            section = panel,
            reader = reader,
            settings = settings,
            windowLayout = windowLayout,
            onSettings = actions.updateSettings,
            onJump = actions.jumpToPage,
            onAddBookmark = actions.addBookmark,
            onRenameBookmark = actions.renameBookmark,
            onDeleteBookmark = actions.deleteBookmark,
            onExport = {
                exportLauncher.launch(
                    reader.score.title.replace(Regex("[\\/:*?\"<>|]"), "_")
                        .take(100) + "-annotated.pdf",
                )
            },
            onDismiss = { readerPanel = null },
        )
    }

    if (colorPanelOpen) {
        ColorPanel(
            selected = currentColor,
            opacity = currentOpacity,
            quickColors = editor.quickColors,
            recentColors = editor.recentColors,
            onDismiss = { colorPanelOpen = false },
            onEyedropper = {
                colorPanelOpen = false
                pageView?.startEyedropper()
            },
            onConfirm = { nextColor, nextOpacity ->
                applyAppearance(
                    nextColor,
                    nextOpacity,
                    addRecent = nextColor !in editor.quickColors,
                )
                colorPanelOpen = false
            },
        )
    }

    key(reader.score.id, reader.pageIndex) {
        textBounds?.let { bounds ->
            TextAnnotationDialog(
                initial = editingText,
                bounds = bounds,
                defaultSize = settings.textSize,
                defaultColor = objectColor,
                defaultOpacity = objectOpacity,
                quickColors = editor.quickColors,
                recentColors = editor.recentColors,
                onDismiss = {
                    textBounds = null
                    editingTextId = null
                },
                onConfirm = { annotation ->
                    if (editingText == null) {
                        addAnnotation(annotation)
                    } else {
                        updateHistory(history.update(annotation))
                    }
                    saveRecentColor(annotation.color)
                    textBounds = null
                    editingTextId = null
                },
            )
        }
    }

    symbolPoint?.let { point ->
        MusicalSymbolChooser(
            selected = selectedSymbolId,
            onSelected = { selectedSymbolId = it },
            onDismiss = { symbolPoint = null },
            onConfirm = { symbolId ->
                addAnnotation(
                    SymbolAnnotation(
                        id = UUID.randomUUID().toString(),
                        symbolId = symbolId,
                        center = point,
                        size = (objectWidth / 50f).coerceIn(0.01f, 0.5f),
                        rotationDegrees = 0f,
                        color = objectColor,
                        opacity = objectOpacity,
                    ),
                )
                symbolPoint = null
            },
        )
    }
}

@Composable
private fun TwoPageReader(
    reader: ReaderUiState,
    settings: AppSettings,
    actions: SheetSetActions,
    onToggleControls: () -> Unit,
) {
    val actionFailed = stringResource(R.string.action_failed)
    val pages = spreadPages(
        ReaderPosition(reader.scoreIndex, reader.pageIndex, reader.pagePart),
        reader.score.pageCount,
    )
    Row(Modifier.fillMaxSize()) {
        pages.forEach { pageIndex ->
            val pageLabel = stringResource(R.string.bookmark_page, pageIndex + 1)
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxSize()
                    .semantics { contentDescription = pageLabel },
                factory = { context -> PdfPageView(context) },
                update = { view ->
                    view.tool = ReaderTool.VIEW
                    view.annotations = reader.annotations.pages[pageIndex].orEmpty()
                    view.selectedAnnotationIds = emptySet()
                    view.pageFit = cz.teply.sheetset.settings.PageFit.PAGE
                    view.setHalfPagePart(0)
                    view.pageTurnTaps = settings.pageTurnTaps
                    view.pageTurnSwipes = settings.pageTurnSwipes
                    view.onPreviousPage = { actions.previousPage(ReaderLayout.TWO_PAGE) }
                    view.onNextPage = { actions.nextPage(ReaderLayout.TWO_PAGE) }
                    view.onPageClick = onToggleControls
                    view.onRenderError = {
                        Toast.makeText(view.context, actionFailed, Toast.LENGTH_SHORT).show()
                    }
                    view.showPage(reader.file, pageIndex)
                },
            )
        }
        if (pages.size == 1) Box(Modifier.weight(1f).fillMaxSize())
    }
}

@Composable
private fun readerPositionText(reader: ReaderUiState, layout: ReaderLayout): String = when (layout) {
    ReaderLayout.SINGLE -> stringResource(
        R.string.page_position,
        reader.pageIndex + 1,
        reader.score.pageCount,
    )
    ReaderLayout.HALF -> stringResource(
        R.string.half_page_position,
        reader.pageIndex + 1,
        stringResource(if (reader.pagePart == 0) R.string.page_top else R.string.page_bottom),
        reader.score.pageCount,
    )
    ReaderLayout.TWO_PAGE -> {
        val pages = spreadPages(
            ReaderPosition(reader.scoreIndex, reader.pageIndex, reader.pagePart),
            reader.score.pageCount,
        )
        if (pages.size == 1) {
            stringResource(R.string.page_position, pages.single() + 1, reader.score.pageCount)
        } else {
            stringResource(
                R.string.spread_position,
                pages.first() + 1,
                pages.last() + 1,
                reader.score.pageCount,
            )
        }
    }
}

@Composable
private fun ReaderTopBar(title: String, onClose: () -> Unit, onExport: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderControl(stringResource(R.string.close), R.drawable.ic_close_24, onClick = onClose)
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ReaderControl(stringResource(R.string.export), R.drawable.ic_download_24, onClick = onExport)
        }
    }
}

@Composable
private fun ReaderNavigationBar(
    reader: ReaderUiState,
    layout: ReaderLayout,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJump: (Int) -> Unit,
    onPanel: (ReaderPanel) -> Unit,
    sliderInteractionSource: MutableInteractionSource,
    onAnnotate: () -> Unit,
) {
    val previousEnabled = reader.scoreIndex > 0 || when (layout) {
        ReaderLayout.SINGLE -> reader.pageIndex > 0
        ReaderLayout.HALF -> reader.pageIndex > 0 || reader.pagePart == 1
        ReaderLayout.TWO_PAGE -> reader.pageIndex - reader.pageIndex % 2 > 0
    }
    val nextEnabled = reader.scoreIndex < reader.scoreIds.lastIndex || when (layout) {
        ReaderLayout.SINGLE -> reader.pageIndex < reader.score.pageCount - 1
        ReaderLayout.HALF -> reader.pagePart == 0 || reader.pageIndex < reader.score.pageCount - 1
        ReaderLayout.TWO_PAGE -> reader.pageIndex - reader.pageIndex % 2 + 2 < reader.score.pageCount
    }
    val maxPage = (reader.score.pageCount - 1).coerceAtLeast(0)
    var sliderValue by remember(reader.score.id, reader.pageIndex) {
        mutableFloatStateOf(reader.pageIndex.toFloat())
    }
    val positionLabel = readerPositionText(reader, layout)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderControl(
                    stringResource(R.string.previous),
                    R.drawable.ic_chevron_left_24,
                    enabled = previousEnabled,
                    onClick = onPrevious,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onJump(sliderValue.roundToInt()) },
                    valueRange = 0f..maxPage.coerceAtLeast(1).toFloat(),
                    steps = (maxPage - 1).coerceAtLeast(0),
                    enabled = maxPage > 0,
                    interactionSource = sliderInteractionSource,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = positionLabel
                    },
                )
                Text(
                    positionLabel,
                    modifier = Modifier.width(72.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                )
                ReaderControl(
                    stringResource(R.string.next),
                    R.drawable.ic_chevron_right_24,
                    enabled = nextEnabled,
                    onClick = onNext,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(64.dp)) {
                ReaderDestinationControl(
                    stringResource(R.string.reader_tab_bookmark),
                    R.drawable.ic_bookmark_24,
                ) { onPanel(ReaderPanel.BOOKMARK) }
                ReaderDestinationControl(
                    stringResource(R.string.reader_tab_page),
                    R.drawable.ic_pdf_24,
                ) { onPanel(ReaderPanel.PAGE) }
                ReaderDestinationControl(
                    stringResource(R.string.reader_tab_gesture),
                    R.drawable.ic_gesture_24,
                ) { onPanel(ReaderPanel.GESTURE) }
                ReaderDestinationControl(
                    stringResource(R.string.reader_tab_tools),
                    R.drawable.ic_view_module_24,
                ) { onPanel(ReaderPanel.TOOLS) }
                ReaderDestinationControl(
                    stringResource(R.string.reader_tab_annotation),
                    R.drawable.ic_edit_24,
                    onClick = onAnnotate,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ReaderDestinationControl(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReaderControl(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        modifier = Modifier.size(48.dp)
            .background(
                if (selected == true) MaterialTheme.colorScheme.surface else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .semantics {
                contentDescription = label
                selected?.let { this.selected = it }
            },
        enabled = enabled,
        onClick = onClick,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

private fun List<AnnotationColor>.withRecent(color: AnnotationColor): List<AnnotationColor> =
    (listOf(color) + filterNot { it == color }).take(4)

private fun PageAnnotation.annotationColor(): AnnotationColor = when (this) {
    is InkAnnotation -> color
    is MarkupAnnotation -> color
    is TextBoxAnnotation -> color
    is ShapeAnnotation -> color
    is SymbolAnnotation -> color
}

private fun PageAnnotation.annotationOpacity(): Int = when (this) {
    is InkAnnotation -> opacity
    is MarkupAnnotation -> opacity
    is TextBoxAnnotation -> opacity
    is ShapeAnnotation -> opacity
    is SymbolAnnotation -> opacity
}

private fun PageAnnotation.withAppearance(color: AnnotationColor, opacity: Int): PageAnnotation =
    when (this) {
        is InkAnnotation -> copy(color = color, opacity = opacity)
        is MarkupAnnotation -> copy(color = color, opacity = opacity)
        is TextBoxAnnotation -> copy(color = color, opacity = opacity)
        is ShapeAnnotation -> copy(color = color, opacity = opacity)
        is SymbolAnnotation -> copy(color = color, opacity = opacity)
    }

private fun PageAnnotation.toolbarWidth(): Int = when (this) {
    is InkAnnotation -> if (kind == InkKind.HIGHLIGHTER) {
        width.highlighterWidthLevel()
    } else {
        width.annotationWidthLevel()
    }
    is ShapeAnnotation -> width.annotationWidthLevel()
    is SymbolAnnotation -> (size * 50f).roundToInt().coerceIn(1, 10)
    is MarkupAnnotation, is TextBoxAnnotation -> 2
}

private fun PageAnnotation.withToolbarWidth(width: Int): PageAnnotation = when (this) {
    is InkAnnotation -> copy(
        width = if (kind == InkKind.HIGHLIGHTER) {
            width.normalizedHighlighterWidth()
        } else {
            width.normalizedAnnotationWidth()
        },
    )
    is ShapeAnnotation -> copy(width = width.normalizedAnnotationWidth())
    is SymbolAnnotation -> copy(size = (width / 50f).coerceIn(0.01f, 0.5f))
    is MarkupAnnotation, is TextBoxAnnotation -> this
}
