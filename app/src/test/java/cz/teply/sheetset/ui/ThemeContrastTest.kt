package cz.teply.sheetset.ui

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
                    val first = foreground.luminance()
                    val second = background.luminance()
                    val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
                    assertTrue("$palette dark=$dark contrast=$ratio", ratio >= 4.5f)
                }
            }
        }
    }
}
