# Changelog

## Unreleased

## [0.5.0-alpha.10] - 2026-09-22

### Fixed

- Matched the launcher, themed launcher, store icon, and website favicon to SeliaScan's white document artwork on a black background.

## [0.5.0-alpha.9] - 2026-08-29

### Changed

- Replaced annotation width buttons with one six-step slider and a live thickness preview.
- Unified pen, marker, shape, and highlighter widths from the thinnest pen stroke to the widest highlight.
- Made dragged setlist rows scale as one unit and preserved stable identities for repeated PDFs.

### Fixed

- Reduced setlist reorder work to one moved row instead of rebuilding the full list at every crossed position.

## [0.5.0-alpha.8] - 2026-08-29

### Changed

- Added consistent leading icons to Reader, Backup and restore, and App details settings so actions are faster to recognize without adding extra controls.
- Added a touch color spectrum and exact six-digit HEX input for custom annotation colors.
- Made pen and highlighter icons use their exact selected color.

### Fixed

- Fixed the crash when drawing with the widest highlighter setting.

## [0.5.0-alpha.6] - 2026-08-28

### Changed

- Reduced the default drawing toolbar to one pen, one highlighter, and one eraser with a single color control.
- Expanded the highlighter's ten width levels from a usable thin mark to a clearly broad text highlight while keeping pen widths precise.
- Replaced the square launcher artwork with a transparent adaptive notebook icon and a rounded black fallback.

### Fixed

- Removed color controls while the eraser is active and replaced duplicate selection cursors with a clear hand tool.
- Migrated older editor settings and backups to the compact tool layout without losing saved colors, order, or opacity.

## [0.5.0-alpha.5] - 2026-08-26

### Added

- Added the ScorePDF-inspired Bookmark, Page, Gesture, Tools, and Annotation reader destinations with focused controls for each workflow.
- Added persistent Light and Dark appearance choices, including backup and restore of the selected theme.

### Changed

- Replaced black reader and annotation chrome with theme-aware neutral gray surfaces so every icon stays readable in both themes.
- Added a direct page slider while retaining Previous and Next actions for touch, keyboard, and pedal workflows.
- Included the appearance setting in native backups without breaking format 1 or 2 restore compatibility.

## [0.5.0-alpha.4] - 2026-08-26

### Changed

- Reworked the phone PDF editor into a compact ScorePDF-inspired two-row toolbar with one mode switch, color-tinted presets, fixed undo/redo/Done actions, and horizontal access to secondary tools.
- Simplified color and text editing so quick choices are visible first while HSV, line height, and alignment remain available under optional advanced controls.

### Fixed

- Corrected the scanner branding to SeliaScan across the app, website, and release documentation.

## [0.5.0-alpha.3] - 2026-08-26

### Added

- Contextual Draw and Objects tool groups with reusable pen presets, lasso selection, musical symbols, text editing, duplicate/delete actions, and one compact color control.
- Quick, recent, and custom HSV colors with opacity, live preview, and an eyedropper that samples the rendered PDF.
- Persisted annotation-tool order and visibility, drag-handle customization, palm rejection, and accessible move actions.
- Direct SeliaScan launch plus Android PDF share/open intake for validated content URI documents.
- Version 3 annotation storage for per-object opacity, text alignment and line height, and musical symbols rendered consistently in the reader and exported PDF.

### Changed

- Renamed the user-facing product to SeliaLists while preserving repository URLs and backup compatibility.
- Changed the Google Play application ID to `com.majkeylab.sheetset`; the new package installs separately, so migrate an existing library with ZIP backup and restore.
- Replaced the crowded annotation palette with a responsive two-row toolbar that keeps navigation and Done fixed while tools scroll.
- Grouped settings into Library, Reading, Data, and App pages with current-value rows and bounded choice dialogs.
- Replaced the SeliaLists launcher and Play Store artwork with the current first-party SeliaScan icon.
- Added Single, Half, and Two-page defaults to the main Reader settings page.
- Lowered the minimum version to Android 10 and moved per-app language handling to the AndroidX compatibility path.

### Fixed

- Preserved pinch focus, pan position, stylus ownership, and palm rejection across mixed-pointer editor gestures.
- Serialized per-score annotation saves so stale or failed writes cannot overwrite newer pages or enter a backup.
- Made native and shared backups wait for the latest successful annotation save and round-trip editor settings and version 3 annotations.
- Kept Back inside the reader, settings page, or drawer before reaching the app root.

### Removed

- Removed automatic scrolling and its speed setting to keep reader controls focused on manual and pedal page turns.

## [0.4.0-alpha.1] - 2026-08-24

### Added

- ScorePDF backup import that preserves every score record, PDF, setlist order, and duplicate occurrence without deleting the current library.
- Direct backup sharing through Android's share sheet and an optional Buy Me a Coffee link in About.
- Per-score bookmarks, bookmark search and direct jumps, saved reading position, and last-viewed sorting.
- Single, Half, and tablet Two-page reader layouts with layout-aware touch and pedal navigation.
- Page turning with common Bluetooth pedal and keyboard keys plus automatic fit-width scrolling at three speeds.
- Score and setlist labels, label search, and title/date sorting.

### Changed

- Catalog and native backup formats now write version 2 while retaining validated version 1 migration.
- The reader keeps one navigation row and puts layouts, automatic scrolling, and bookmarks in one performance-tools sheet.

### Fixed

- Android Back closes the active reader, detail, settings page, or drawer before reaching the root screen; root Back no longer exits SeliaLists.
- Automatic scrolling waits for the new PDF page to finish rendering before continuing.

## [0.3.0-alpha.2] - 2026-08-23

### Added

- Drag handles for direct setlist reordering, including edge scrolling and screen-reader move actions.
- Freehand highlighter strokes and a straight-line mode for pen and highlighter tools.
- Direct color swatches and in-editor stroke-width controls.

### Changed

- Replaced the crowded annotation strip and tablet side rail with a two-row bottom dock inspired by musician-first score readers.
- Split annotation tools into focused Draw and Add groups while keeping page navigation, undo, redo, and Done fixed.

### Fixed

- Long setlist drags now move across multiple rows and persist the final position.
- Freehand stroke commits keep an immutable snapshot of their points.

## [0.3.0-alpha.1] - 2026-08-22

### Added

- Hamburger menu with reader, annotation, language, backup, restore, and about settings.
- English, Czech, Slovak, German, Polish, and device-language selection.
- Tablet navigation rail and expanded setlist list-detail layout.
- Colored pen, highlight, underline, strike-through, text box, line, arrow, rectangle, and ellipse annotations.
- Selection, move, resize, delete, eraser, undo, redo, pinch zoom, and horizontal panning.
- Versioned ZIP backup and validated rollback-safe restore for PDFs, setlists, annotations, settings, and language.
- Optional ScanIt handoff from the PDF import sheet.

### Changed

- Android 13 is now the minimum supported version.
- Reader tools use a horizontally scrollable phone palette and a vertical tablet palette.
- Import and Create remain visible as contextual actions in the upper-right corner.

### Fixed

- Version 1 pen and highlighter annotations migrate without data loss.
- Replaced text glyph controls with accessible vector icons.
- Hidden drawer destinations no longer leak into screen-reader semantics.

## [0.2.0-alpha.1] - 2026-08-20

### Changed

- Reworked the library, setlist, dialogs, and reader controls into one restrained monochrome interface.
- Moved import and search actions into one header and removed the duplicate PDF heading.
- Replaced the filled tab bar with a lighter paper-style selection indicator.
- Tightened spacing and control bars so scores keep more of the screen.

### Fixed

- Tabs and annotation tools now report their selected state to screen readers.

## [0.1.0-alpha.1] - 2026-08-20

### Added

- Offline PDF import with MIME, size, signature, and `PdfRenderer` validation.
- Unlimited setlists with accessible ordering controls.
- Full-page reader with setlist continuation, zoom, taps, and swipes.
- Persistent pen and highlighter annotations with eraser, undo, and redo.
- Export of annotated PDF copies without changing imported originals.
- English, Czech, Slovak, German, and Polish resources.
- Android CI, release automation, and a static GitHub Pages site.
