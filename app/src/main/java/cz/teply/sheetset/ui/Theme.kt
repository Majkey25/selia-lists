package cz.teply.sheetset.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.teply.sheetset.settings.DEFAULT_CUSTOM_ACCENT_RGB
import cz.teply.sheetset.settings.ThemeMode
import cz.teply.sheetset.settings.ThemePalette

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E7E7),
    onPrimaryContainer = Color(0xFF111111),
    inversePrimary = Color(0xFFDDDDDD),
    secondary = Color(0xFF333333),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E7E7),
    onSecondaryContainer = Color(0xFF111111),
    tertiary = Color(0xFF555555),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7E7E7),
    onTertiaryContainer = Color(0xFF111111),
    background = Color(0xFFF7F7F4),
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF0F0EC),
    onSurfaceVariant = Color(0xFF444444),
    surfaceTint = Color(0xFF111111),
    inverseSurface = Color(0xFF111111),
    inverseOnSurface = Color.White,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE0E0E0),
    surfaceContainer = Color(0xFFF7F7F4),
    surfaceContainerHigh = Color(0xFFF0F0EC),
    surfaceContainerHighest = Color(0xFFE8E8E3),
    surfaceContainerLow = Color(0xFFFAFAF8),
    surfaceContainerLowest = Color.White,
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFFD0D0D0),
    error = Color(0xFF111111),
    onError = Color.White,
    errorContainer = Color(0xFFE7E7E7),
    onErrorContainer = Color(0xFF111111),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF383838),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFD8D8D8),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF353535),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFBDBDBD),
    onTertiary = Color(0xFF111111),
    background = Color(0xFF101010),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF181818),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFD2D2D2),
    surfaceTint = Color(0xFFF2F2F2),
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = Color(0xFF181818),
    surfaceBright = Color(0xFF363636),
    surfaceDim = Color(0xFF101010),
    surfaceContainer = Color(0xFF1D1D1D),
    surfaceContainerHigh = Color(0xFF292929),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceContainerLow = Color(0xFF171717),
    surfaceContainerLowest = Color(0xFF0C0C0C),
    outline = Color(0xFFA0A0A0),
    outlineVariant = Color(0xFF484848),
    error = Color(0xFFF2F2F2),
    onError = Color(0xFF111111),
    errorContainer = Color(0xFF383838),
    onErrorContainer = Color.White,
    scrim = Color.Black,
)

private val PaletteColors = mapOf(
    ThemePalette.OCEAN to (
        LightColors.tinted(0xFF195BA3, 0xFFF0F6FD, 0xFFF8FBFF) to
            DarkColors.tinted(0xFF91C6FF, 0xFF0D1724, 0xFF142234)
        ),
    ThemePalette.GROVE to (
        LightColors.tinted(0xFF26634B, 0xFFEFF6EE, 0xFFF7FBF6) to
            DarkColors.tinted(0xFF98D4AC, 0xFF0E1A15, 0xFF17271E)
        ),
    ThemePalette.EMBER to (
        LightColors.tinted(0xFF923F27, 0xFFFCF0E7, 0xFFFFF8F1) to
            DarkColors.tinted(0xFFFFB595, 0xFF201510, 0xFF2D201A)
        ),
    ThemePalette.IRIS to (
        LightColors.tinted(0xFF6543A0, 0xFFF4EFFB, 0xFFFBF7FF) to
            DarkColors.tinted(0xFFC9AEFF, 0xFF191324, 0xFF241B32)
        ),
    ThemePalette.LAGOON to (
        LightColors.tinted(0xFF176D72, 0xFFEAF7F6, 0xFFF4FCFC) to
            DarkColors.tinted(0xFF75D8D1, 0xFF0C1B1C, 0xFF132A2B)
        ),
    ThemePalette.ROSE to (
        LightColors.tinted(0xFF96365E, 0xFFFAEDF3, 0xFFFFF7FA) to
            DarkColors.tinted(0xFFFFA8CA, 0xFF25131C, 0xFF321D28)
        ),
    ThemePalette.SLATE to (
        LightColors.tinted(0xFF3E5870, 0xFFEAF0F5, 0xFFF5F8FB) to
            DarkColors.tinted(0xFFB5C9DF, 0xFF131C26, 0xFF202A35)
        ),
)

private fun ColorScheme.tinted(accent: Long, canvas: Long, panel: Long): ColorScheme =
    tinted(Color(accent), Color(canvas), Color(panel))

private fun ColorScheme.tinted(accent: Color, canvas: Color, panel: Color): ColorScheme {
    val selection = lerp(panel, accent, 0.16f)
    return copy(
        primary = accent,
        secondary = accent,
        tertiary = accent,
        primaryContainer = selection,
        secondaryContainer = selection,
        tertiaryContainer = selection,
        onPrimaryContainer = onSurface,
        onSecondaryContainer = onSurface,
        onTertiaryContainer = onSurface,
        background = canvas,
        surface = panel,
        surfaceTint = accent,
        surfaceVariant = lerp(panel, accent, 0.08f),
        surfaceBright = lerp(panel, accent, 0.12f),
        surfaceDim = canvas,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = panel,
        surfaceContainer = lerp(panel, accent, 0.04f),
        surfaceContainerHigh = lerp(panel, accent, 0.08f),
        surfaceContainerHighest = lerp(panel, accent, 0.12f),
    )
}

internal fun themeColors(
    palette: ThemePalette,
    dark: Boolean,
    customAccentRgb: Int = DEFAULT_CUSTOM_ACCENT_RGB,
): ColorScheme {
    if (palette == ThemePalette.CUSTOM) {
        val base = if (dark) DarkColors else LightColors
        val seed = Color(customAccentRgb or 0xFF000000.toInt())
        val canvas = lerp(base.background, seed, 0.06f)
        val panel = lerp(base.surface, seed, 0.04f)
        val accent = readableAccent(seed, canvas, panel, dark)
        val foreground = if (contrast(accent, Color.White) >= 4.5f) Color.White else Color.Black
        return base.tinted(accent, canvas, panel).copy(
            onPrimary = foreground,
            onSecondary = foreground,
            onTertiary = foreground,
        )
    }
    val colors = PaletteColors[palette]
    return if (dark) colors?.second ?: DarkColors else colors?.first ?: LightColors
}

private fun readableAccent(seed: Color, canvas: Color, panel: Color, dark: Boolean): Color {
    fun isReadable(accent: Color): Boolean =
        contrast(accent, canvas) >= 4.5f && contrast(accent, lerp(panel, accent, 0.16f)) >= 4.5f

    if (isReadable(seed)) return seed
    var readable = if (dark) Color.White else Color.Black
    var unreadable = seed
    repeat(10) {
        val tone = lerp(readable, unreadable, 0.5f)
        if (isReadable(tone)) readable = tone else unreadable = tone
    }
    return readable
}

private fun contrast(first: Color, second: Color): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
        (minOf(firstLuminance, secondLuminance) + 0.05f)
}

@Composable
internal fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

private val MonochromeShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(6.dp),
)

private val SheetTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun SheetSetTheme(
    darkTheme: Boolean = false,
    palette: ThemePalette = ThemePalette.MONOCHROME,
    customAccentRgb: Int = DEFAULT_CUSTOM_ACCENT_RGB,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = remember(palette, darkTheme, customAccentRgb) {
            themeColors(palette, darkTheme, customAccentRgb)
        },
        typography = SheetTypography,
        shapes = MonochromeShapes,
        content = content,
    )
}
