# SeliaLists

Offline Android PDF organizer for musicians. Import scores, build unlimited setlists, write annotations, and export a marked-up copy. No account, ads, analytics, cloud service, or Internet permission.

[![Android CI](https://github.com/Majkey25/selia-lists/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Majkey25/selia-lists/actions/workflows/android-ci.yml)
[![Latest release](https://img.shields.io/github/v/release/Majkey25/selia-lists?include_prereleases)](https://github.com/Majkey25/selia-lists/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-111111.svg)](LICENSE)
![Android 10+](https://img.shields.io/badge/Android-10%2B-111111.svg)
![Offline only](https://img.shields.io/badge/offline-only-111111.svg)

<p align="center">
  <img src="site/assets/home.png" alt="SeliaLists phone library with Menu and Import PDF actions" width="260">
  <img src="site/assets/reader.png" alt="SeliaLists phone PDF editor with compact annotation tools and one color control" width="260">
  <img src="site/assets/tablet.png" alt="SeliaLists tablet library with a left navigation rail" width="420">
</p>

## What it does

- Imports one or more PDFs through the Android file picker.
- Stores private offline copies and validates each file before adding it.
- Creates unlimited setlists with direct drag ordering and no duplicated PDFs.
- Reads a setlist continuously across score boundaries.
- Resumes the last page, stores searchable bookmarks, and jumps directly to bookmarked passages.
- Offers Single, Half, and tablet Two-page layouts plus Bluetooth pedal and keyboard page turns.
- Keeps Bookmark, Page, Gesture, Tools, and Annotation actions visible in the reader.
- Organizes scores and setlists with labels, label search, and title/date sorting.
- Supports colored pen, highlight, underline, strike-through, text boxes, lines, arrows, rectangles, and ellipses.
- Selects, moves, resizes, deletes, erases, undoes, and redoes annotations.
- Uses one compact editor on phones and tablets with direct core tools, one color control, and fixed undo, redo, and Done actions.
- Supports persistent Light and Dark themes with readable neutral-gray reader and editor controls.
- Preserves the imported original and exports a new annotated PDF.
- Backs up and safely restores PDFs, setlists, annotations, settings, and language in a validated ZIP.
- Imports ScorePDF backup ZIPs as a non-destructive merge and shares SeliaLists backups through Android.
- Uses English, Czech, Slovak, German, Polish, or the Android device language.
- Opens an installed SeliaScan app from the import sheet and uses its Google Play listing when absent.

## Install

Download test builds from [GitHub Releases](https://github.com/Majkey25/selia-lists/releases). Google Play closed-test builds use the same source and the Play Console preparation documented in this repository.

SeliaLists requires Android 10 or newer.

## Build

Requirements: JDK 17 and Android SDK 36.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain
.\gradlew.bat assembleDebugAndroidTest --no-daemon --console=plain
```

Install the debug APK:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Design

SeliaLists uses one Android application module, Jetpack Compose, and platform PDF APIs. `LibraryRepository` stores PDFs and an atomic JSON catalog. Versioned typed annotations migrate old pen and highlighter data. `PdfPageView` and `PdfExporter` share one renderer so the on-screen page matches the non-destructive exported copy. Restore validates the complete ZIP in staging before an atomic directory swap with rollback.

The app requests no runtime or sensitive platform permissions. Imported files stay in app-private storage. Export writes only to the location selected in the Android document picker.

## Current limits

- Android only.
- PDF text and vector objects are not edited. Annotations are a separate layer drawn by touch.
- Underline and strike-through use manual drag bounds. Scanned PDFs have no OCR.
- Export rasterizes source pages at up to 144 dpi and 12 MP per page. Before opening the output, it rejects documents over 1,000 pages or a total raster estimate above half the currently available Java heap, including one extra live page bitmap (at most 64 MP). This is an export-only admission limit, not a guarantee against native PDF parser exhaustion; imported originals remain readable and unchanged.
- Backup restore accepts archives up to 1 GiB. There is no cloud sync.
- Setlists support 10,000 score occurrences each and 100,000 across the library. Repeated songs remain distinct and ordered; the number of setlists is not capped.
- Annotation work is limited to 64,000 units per page and 1,000,000 per document: a text box uses 256 units plus its character count, ink uses one plus its point count, markup uses one plus four per rectangle, and other objects use one. Existing per-object limits remain. Restore and edits reject oversized content without truncation or replacing the existing library.
- Scanning is delegated to the separate SeliaScan app; SeliaLists has no built-in camera scanner.
- Common page-turn pedals work as keyboards; custom MIDI mappings, audio tools, and a metronome are not included.

See the [adaptive editor specification](docs/superpowers/specs/2026-08-20-sheetset-editor-settings-adaptive-design.md) and [PDF editor plan](docs/superpowers/plans/2026-08-20-sheetset-pdf-editor.md).
The [v0.4 performance specification](docs/superpowers/specs/2026-08-24-sheetset-performance-essentials-design.md) documents bookmarks, layouts, pedals, auto-scroll, labels, migration, and release acceptance.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md). Report security problems through [GitHub private vulnerability reporting](https://github.com/Majkey25/selia-lists/security/advisories/new).

## License

[MIT](LICENSE)
