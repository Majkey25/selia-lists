# Google Play Console publication worksheet

Use these answers for the SeliaLists `0.5.0-alpha.12` closed-test AAB after artifact verification. Re-audit the artifact if the manifest, dependencies, external links, or data handling change.

## App identity

| Field | Value |
|---|---|
| App name | `SeliaLists` |
| Package | `com.majkeylab.sheetset` |
| Version code | `17` |
| Version name | `0.5.0-alpha.12` |
| Default language | English (United States) |
| App or game | App |
| Category | Music & Audio |
| Free or paid | Free |
| Contact email | `majkeylab@gmail.com` |
| Website | `https://majkey25.github.io/selia-lists/` |
| Privacy policy | `https://majkey25.github.io/selia-lists/privacy.html` |
| Ads | No |

Use the verified legal name, address, phone, and account type already stored in the Play developer account. Do not invent or copy those values into the repository.

## English listing

### App name

> SeliaLists

### Short description

> Organize PDF scores, build setlists, and annotate music offline.

### Full description

> SeliaLists is a focused PDF score library for rehearsals, practice, and performance.
>
> Import one or more PDFs, organize them with labels and search, and build unlimited setlists with direct drag ordering. Open a score or setlist and move through every page without returning to the library.
>
> Reader features:
> - Single, Half, and tablet Two-page layouts
> - page turning by tap, swipe, Bluetooth pedal, or keyboard
> - saved reading position and searchable page bookmarks
> - keep-screen-awake and configurable reader controls
>
> Annotation features:
> - pen, highlighter, eraser, and selection tools
> - one compact color picker and six clear stroke widths
> - underline, strike-through, text, lines, arrows, rectangles, and ellipses
> - move, resize, delete, undo, and redo
> - export a separate PDF containing the visible annotations
>
> Library features:
> - unlimited PDF scores and setlists
> - title and label search
> - sorting by title, import date, creation date, or last viewed
> - validated ZIP backup, restore, and sharing
> - non-destructive import of compatible ScorePDF backup ZIPs
>
> SeliaLists works offline and has no account, ads, analytics, subscription, tracking SDK, cloud library, or app-owned Internet permission. Imported originals remain unchanged. Scanning is delegated to the separate SeliaScan app, which opens directly when installed or through its Google Play listing when absent.

## Czech listing

### App name

> SeliaLists

### Short description

> Organizujte PDF noty, setlisty a anotace jednoduše a offline.

### Full description

> SeliaLists je přehledná knihovna PDF not pro zkoušky, cvičení a vystoupení.
>
> Importujte jedno nebo více PDF, organizujte je pomocí štítků a vyhledávání a vytvářejte neomezené setlisty s přímým řazením přetažením. Otevřete skladbu nebo setlist a procházejte všechny stránky bez návratu do knihovny.
>
> Čtečka:
> - rozložení Jedna stránka, Půl stránky a dvě stránky na tabletu
> - otáčení stran klepnutím, tahem, Bluetooth pedálem nebo klávesnicí
> - uložená pozice čtení a vyhledatelné záložky stránek
> - udržení zapnuté obrazovky a nastavitelné ovládání
>
> Anotace:
> - pero, zvýrazňovač, guma a výběr
> - kompaktní volba barvy a šest jasných úrovní tloušťky
> - podtržení, přeškrtnutí, text, čáry, šipky, obdélníky a elipsy
> - přesun, změna velikosti, smazání, krok zpět a znovu
> - export samostatného PDF s viditelnými anotacemi
>
> Knihovna:
> - neomezený počet PDF a setlistů
> - hledání podle názvu a štítků
> - řazení podle názvu, importu, vytvoření nebo posledního otevření
> - ověřená ZIP záloha, obnovení a sdílení
> - nedestruktivní import kompatibilních ScorePDF záloh
>
> SeliaLists funguje offline a neobsahuje účet, reklamy, analytiku, předplatné, sledovací SDK, cloudovou knihovnu ani vlastní přístup k internetu. Importované originály zůstávají beze změny. Skenování je volitelně předáno samostatné aplikaci SeliaScan, která se otevře přímo po instalaci nebo přes svou stránku na Google Play.

## Closed-test release notes

### English

> Improved backup and deletion recovery, annotation saving, selection, erasing, two-finger zoom, and PDF export. Fixed setlist dragging, search, accessibility, and restoration of unfinished dialogs. Updated repository and privacy links.

### Čeština

> Vylepšená obnova záloh a přerušeného mazání, ukládání a výběr anotací, guma, přiblížení dvěma prsty a export PDF. Opravené přetahování setlistů, hledání, přístupnost a obnova rozpracovaných dialogů. Aktualizované odkazy na repozitář a zásady soukromí.

## App content

| Declaration | Answer |
|---|---|
| App access | All functions work without an account or special access |
| Ads | No |
| Target audience | 18 and over |
| Designed for children | No |
| Account creation | No |
| Account deletion | Not applicable |
| News or magazine | No |
| Government | No |
| Health | No |
| Financial features | No |
| Gambling | No |
| Social or user communication | No |
| User-generated content published by the app | No |

Reviewer note:

> SeliaLists requires no account. Tap Import PDF to select a PDF through Android's document picker, or restore a compatible backup from Menu. The app works offline. App details, Privacy policy, language, reader, annotation, backup, and restore settings are available from the top-left menu.

## Data safety

| Question | Answer |
|---|---|
| Does the app collect user data? | No |
| Does the app share user data with the developer or third parties? | No |
| Is data encrypted in transit? | Not applicable; SeliaLists has no app-owned network transmission |
| Can users request account deletion? | Not applicable; SeliaLists has no accounts |

User-selected exports and shares are not developer collection. Android document providers, share recipients, browsers, Google Play, GitHub, SeliaScan, and Buy Me a Coffee operate under their own terms after the user explicitly chooses them.

## Payments and support

App details contains an optional external Buy Me a Coffee support link. It provides no feature, entitlement, badge, priority, digital content, or service. SeliaLists has no billing SDK and no paid functionality.

## Asset checklist

- `assets/icon.png`: 512 × 512 RGBA PNG, 32-bit with alpha, under 1,024 KB.
- `assets/feature-graphic.png`: 1024 × 500 RGB PNG without alpha.
- `assets/en-US/phone/01-library.png` through `06-reader-settings.png`: six 1080-pixel-wide RGB PNG screenshots from Android 10 QA.
- The screenshots show only SeliaLists and the synthetic `SheetSet QA Score`; no private or copyrighted music is present.
- No copyrighted scores, rankings, prices, Play badges, donation promotion, device frames, or unfinished features.

Upload phone screenshots in this order: library, clean reader, compact annotation toolbar, color picker, App details, Reader settings.

Alt text:

1. `SeliaLists PDF library with search, sorting, labels, and one synthetic QA score.`
2. `A synthetic music score open in the SeliaLists reader.`
3. `Compact SeliaLists annotation toolbar with direct pen, highlighter, eraser, selection, color, undo, and redo controls.`
4. `SeliaLists color picker with eight clear annotation colors.`
5. `App details with version, Android requirement, Privacy policy, source links, and optional Buy Me a Coffee support.`
6. `Reader settings for page layout, page turning, control visibility, and page fit.`

## Submission gate

- [ ] Verify the connected physical test phone; do not use an emulator for this release.
- [ ] Signed AAB package/version/certificate/permissions are verified.
- [ ] Unit, lint, build, and physical-device checks pass; record the tested Android API level.
- [ ] GitHub Pages root and Privacy policy return HTTP 200 over HTTPS.
- [ ] Store text and screenshots match the exact submitted AAB.
- [ ] Play App Signing is enabled and the verified AAB is uploaded only to Closed testing.
- [ ] Tester list, feedback channel, opt-in URL, and release processing state are verified.
