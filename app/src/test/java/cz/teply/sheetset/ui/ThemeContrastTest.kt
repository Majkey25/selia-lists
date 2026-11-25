package cz.teply.sheetset.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import cz.teply.sheetset.settings.ThemePalette
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun textAndPrimaryActionsMeetNormalTextContrast() {
        for (palette in ThemePalette.entries) {
            for (dark in listOf(false, true)) {
                val colors = themeColors(palette, dark)
                val pairs = listOf(
                    colors.onPrimary to colors.primary,
                    colors.primary to colors.surface,
                    colors.onSurface to colors.surface,
                    colors.onBackground to colors.background,
                    colors.onSurfaceVariant to colors.surfaceVariant,
                    colors.onSecondaryContainer to colors.secondaryContainer,
                )
                for ((foreground, background) in pairs) {
                    assertMinimumContrast(foreground, background, "$palette dark=$dark")
                }
            }
        }
    }

    @Test
    fun customAccentsRemainLegibleOnBothModes() {
        val accents = listOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x757575)
        for (accent in accents) {
            for (dark in listOf(false, true)) {
                val colors = themeColors(ThemePalette.CUSTOM, dark, accent)
                val description = "accent=$accent dark=$dark"
                assertMinimumContrast(colors.onPrimary, colors.primary, description)
                assertMinimumContrast(colors.primary, colors.background, description)
                assertMinimumContrast(colors.primary, colors.surfaceContainerHighest, description)
                assertMinimumContrast(colors.onSurface, colors.surface, description)
                assertMinimumContrast(colors.onPrimaryContainer, colors.primaryContainer, description)
            }
        }
    }

    private fun assertMinimumContrast(foreground: Color, background: Color, description: String) {
        val first = foreground.luminance()
        val second = background.luminance()
        val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
        assertTrue("$description contrast=$ratio", ratio >= 4.5f)
    }
}
