package cz.teply.sheetset.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cz.teply.sheetset.R
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.ThemeMode
import cz.teply.sheetset.settings.ThemePalette

private val PreviewShape = RoundedCornerShape(8.dp)

@Composable
internal fun AppearanceSettings(
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: (AppSettings) -> Unit,
) {
    var editingCustom by rememberSaveable { mutableStateOf(false) }
    SettingsPage(R.string.appearance, onBack) {
        item { SettingsSectionTitle(R.string.theme_mode) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, PreviewShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                ).forEach { (mode, label) ->
                    val selected = settings.themeMode == mode
                    val colors = MaterialTheme.colorScheme
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(PreviewShape)
                            .then(if (selected) Modifier.background(colors.primary) else Modifier)
                            .selectable(selected, role = Role.RadioButton) {
                                if (!selected) onSettings(settings.copy(themeMode = mode))
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(label),
                            color = if (selected) colors.onPrimary else colors.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        item {
            SettingsSectionTitle(R.string.theme_palette)
            Text(
                stringResource(R.string.theme_preview_summary),
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemePalette.entries.forEach { palette ->
                    val selected = settings.themePalette == palette
                    ThemeOption(palette, selected, settings.customAccentRgb) {
                        if (palette == ThemePalette.CUSTOM) {
                            editingCustom = true
                        } else if (!selected) {
                            onSettings(settings.copy(themePalette = palette))
                        }
                    }
                }
            }
        }
    }
    if (editingCustom) {
        CustomAccentDialog(settings.customAccentRgb, onDismiss = { editingCustom = false }) { rgb ->
            onSettings(settings.copy(themePalette = ThemePalette.CUSTOM, customAccentRgb = rgb))
            editingCustom = false
        }
    }
}

@Composable
private fun ThemeOption(palette: ThemePalette, selected: Boolean, customAccentRgb: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = when (palette) {
        ThemePalette.MONOCHROME -> R.string.theme_monochrome
        ThemePalette.OCEAN -> R.string.theme_ocean
        ThemePalette.GROVE -> R.string.theme_grove
        ThemePalette.EMBER -> R.string.theme_ember
        ThemePalette.IRIS -> R.string.theme_iris
        ThemePalette.LAGOON -> R.string.theme_lagoon
        ThemePalette.ROSE -> R.string.theme_rose
        ThemePalette.SLATE -> R.string.theme_slate
        ThemePalette.CUSTOM -> R.string.theme_custom
    }
    Row(
        Modifier.fillMaxWidth().clip(PreviewShape)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, PreviewShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemePreviewPair(palette, customAccentRgb)
        Text(
            stringResource(label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
        )
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun CustomAccentDialog(initialRgb: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var hex by rememberSaveable { mutableStateOf("#${initialRgb.toString(16).uppercase().padStart(6, '0')}") }
    val digits = hex.trim().removePrefix("#")
    val rgb = digits.takeIf { value -> value.length == 6 && value.all { it.digitToIntOrNull(16) != null } }
        ?.toIntOrNull(16)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(stringResource(R.string.custom_color)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThemePreviewPair(ThemePalette.CUSTOM, rgb ?: initialRgb)
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.theme_accent_hex)) },
                    supportingText = { Text(stringResource(R.string.theme_accent_hex_hint)) },
                    singleLine = true,
                    isError = rgb == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = rgb != null, onClick = { rgb?.let(onSave) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ThemePreviewPair(palette: ThemePalette, customAccentRgb: Int) {
    val light = remember(palette, customAccentRgb) {
        themeColors(palette, dark = false, customAccentRgb = customAccentRgb)
    }
    val dark = remember(palette, customAccentRgb) {
        themeColors(palette, dark = true, customAccentRgb = customAccentRgb)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ThemePreview(light)
        ThemePreview(dark)
    }
}

@Composable
private fun ThemePreview(colors: ColorScheme) {
    Canvas(
        Modifier.size(width = 48.dp, height = 52.dp).clip(PreviewShape)
            .background(colors.background).border(1.dp, colors.outlineVariant, PreviewShape),
    ) {
        val radius = CornerRadius(3.dp.toPx())
        val inset = 6.dp.toPx()
        val width = size.width - 2 * inset
        drawCircle(colors.primary, 2.5.dp.toPx(), Offset(inset + 2.5.dp.toPx(), 10.dp.toPx()))
        drawRoundRect(colors.onSurface, Offset(15.dp.toPx(), 9.dp.toPx()), Size(16.dp.toPx(), 2.dp.toPx()), radius)
        for (index in 0..1) {
            val y = (19 + 13 * index).dp.toPx()
            drawRoundRect(colors.surfaceContainerHighest, Offset(inset, y), Size(width, 10.dp.toPx()), radius)
            drawRoundRect(colors.primary, Offset(inset + 4.dp.toPx(), y + 3.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()), radius)
            drawRoundRect(colors.onSurfaceVariant, Offset(inset + 12.dp.toPx(), y + 4.dp.toPx()), Size(15.dp.toPx(), 2.dp.toPx()), radius)
        }
    }
}
