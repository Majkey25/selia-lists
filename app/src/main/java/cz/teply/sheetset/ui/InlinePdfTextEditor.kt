package cz.teply.sheetset.ui

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cz.teply.sheetset.R
import cz.teply.sheetset.pdf.NormalizedPoint
import kotlin.math.roundToInt

@Composable
internal fun InlinePdfTextEditor(
    page: RectF,
    anchor: NormalizedPoint,
    text: String,
    fontSize: Float,
    color: Color,
    onText: (String) -> Unit,
    onCommit: () -> Boolean,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    val commit: () -> Unit = {
        if (onCommit()) keyboard?.hide()
    }
    val latestCommit = rememberUpdatedState(commit)
    val label = stringResource(R.string.insert_text)
    val topInset = WindowInsets.statusBars.getTop(density)
    val bottomInset = maxOf(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density))
    val leftInset = WindowInsets.safeDrawing.getLeft(density, layoutDirection)
    val rightInset = WindowInsets.safeDrawing.getRight(density, layoutDirection)
    BackHandler { commit() }
    LaunchedEffect(requester) {
        requester.requestFocus()
        keyboard?.show()
    }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures { latestCommit.value() }
    }) {
        val availableLeft = leftInset.coerceAtMost(constraints.maxWidth)
        val availableRight = (constraints.maxWidth - rightInset).coerceAtLeast(availableLeft)
        val minWidth = with(density) { 192.dp.roundToPx() }.coerceAtMost(availableRight - availableLeft)
        val paperLeft = page.left.roundToInt().coerceIn(availableLeft, availableRight)
        val paperRight = page.right.roundToInt().coerceIn(paperLeft, availableRight)
        val left = paperLeft.coerceAtMost(availableRight - minWidth)
        val right = paperRight.coerceAtLeast(left + minWidth)
        val x = (page.left + anchor.x * page.width()).roundToInt().coerceIn(left, right - minWidth)
        val usableBottom = (constraints.maxHeight - bottomInset).coerceAtLeast(topInset)
        val editorHeight = with(density) { 112.dp.roundToPx() }
        val y = (page.top + anchor.y * page.height()).roundToInt()
            .coerceIn(topInset, (usableBottom - editorHeight).coerceAtLeast(topInset))
        val size = with(density) {
            (page.height() * fontSize).coerceIn(16.dp.toPx(), 46.dp.toPx()).toSp()
        }
        val draftBackground = if (x < paperLeft || right > paperRight || y < page.top || y + editorHeight > page.bottom) {
            Color.White
        } else {
            Color.Transparent
        }
        val ink = if (draftBackground == Color.White && color.luminance() > 0.46f) Color.Black else color
        Row(
            Modifier.offset { IntOffset(x, y) }
                .width(with(density) { (right - x).toDp() })
                .heightIn(max = with(density) { (usableBottom - y).coerceAtLeast(1).toDp() }),
            verticalAlignment = Alignment.Top,
        ) {
            BasicTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.weight(1f).focusRequester(requester).semantics { contentDescription = label },
                textStyle = TextStyle(color = ink, fontSize = size),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                decorationBox = { input ->
                    Box(Modifier.background(draftBackground)) {
                        if (text.isEmpty()) Text(label, color = ink.copy(alpha = 0.65f), fontSize = size)
                        input()
                    }
                },
            )
            FilledIconButton(onClick = commit) {
                Icon(painterResource(R.drawable.ic_done_24), stringResource(R.string.done))
            }
        }
    }
}
