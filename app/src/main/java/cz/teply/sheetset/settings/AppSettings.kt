package cz.teply.sheetset.settings

import cz.teply.sheetset.pdf.AnnotationEditorSettings

enum class PageFit { PAGE, WIDTH }
enum class ReaderDefaultTool { VIEW, PEN, HIGHLIGHTER }
enum class ToolSize { THIN, MEDIUM, THICK }
enum class HighlightStrength { LIGHT, MEDIUM, STRONG }
enum class AnnotationTextSize { SMALL, MEDIUM, LARGE }
enum class ReaderLayout { SINGLE, HALF, TWO_PAGE }
enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class ThemePalette { MONOCHROME, OCEAN, GROVE, EMBER, IRIS, LAGOON, ROSE, SLATE, CUSTOM }

const val DEFAULT_PDF_ZOOM_PERCENT = 100
const val MAX_PDF_ZOOM_PERCENT = 500
internal const val DEFAULT_CUSTOM_ACCENT_RGB = 0x247668
internal const val MAX_ACCENT_RGB = 0xFFFFFF

data class AppSettings(
    val keepScreenAwake: Boolean = true,
    val pageFit: PageFit = PageFit.PAGE,
    val defaultZoomPercent: Int = DEFAULT_PDF_ZOOM_PERCENT,
    val pageTurnTaps: Boolean = true,
    val pageTurnSwipes: Boolean = true,
    val autoHideControls: Boolean = true,
    val defaultTool: ReaderDefaultTool = ReaderDefaultTool.PEN,
    val textSize: AnnotationTextSize = AnnotationTextSize.MEDIUM,
    val readerLayout: ReaderLayout = ReaderLayout.SINGLE,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val themePalette: ThemePalette = ThemePalette.MONOCHROME,
    val editor: AnnotationEditorSettings = AnnotationEditorSettings.defaults(),
    val customAccentRgb: Int = DEFAULT_CUSTOM_ACCENT_RGB,
) {
    init {
        require(defaultZoomPercent in DEFAULT_PDF_ZOOM_PERCENT..MAX_PDF_ZOOM_PERCENT) { "Invalid PDF zoom" }
        require(customAccentRgb in 0..MAX_ACCENT_RGB) { "Invalid theme accent" }
    }
}
