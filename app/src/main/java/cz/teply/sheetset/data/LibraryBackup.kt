package cz.teply.sheetset.data

import cz.teply.sheetset.pdf.AnnotationJson
import cz.teply.sheetset.pdf.AnnotationEditorSettingsJson
import cz.teply.sheetset.settings.AnnotationTextSize
import cz.teply.sheetset.settings.AppLanguages
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.PageFit
import cz.teply.sheetset.settings.ReaderDefaultTool
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.settings.ThemeMode
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val BACKUP_FORMAT = "sheetset-backup"
private const val BACKUP_VERSION = 2
// ponytail: 1 GiB bounds untrusted ZIPs; raise after testing larger real libraries.
internal const val MAX_BACKUP_BYTES = 1L * 1024L * 1024L * 1024L
internal const val MAX_BACKUP_ENTRIES = 20_000
internal const val MAX_JSON_BYTES = 16L * 1024L * 1024L

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class BackupMetadata(
    val settings: AppSettings,
    val languageTag: String?,
)

internal fun writeLibraryBackup(
    root: File,
    catalog: LibraryCatalog,
    destination: OutputStream,
    settings: AppSettings,
    languageTag: String?,
) {
    require(languageTag == null || languageTag in AppLanguages.supportedTags) {
        "Unsupported language"
    }
    if (catalog.scores.size > MAX_BACKUP_ENTRIES - 2) {
        throw BackupException("Backup has too many entries")
    }
    val textEntries = listOf(
        "manifest.json" to JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("version", BACKUP_VERSION)
            .put("createdAtEpochMs", System.currentTimeMillis())
            .put("languageTag", languageTag ?: JSONObject.NULL)
            .put("settings", settings.toJson())
            .toString().toByteArray(Charsets.UTF_8),
        "catalog.json" to CatalogJson.encode(catalog).toByteArray(Charsets.UTF_8),
    )
    if (textEntries.any { it.second.size > MAX_JSON_BYTES }) {
        throw BackupException("Backup entry is too large")
    }
    CatalogJson.decode(textEntries.last().second.toString(Charsets.UTF_8))
    val files = catalog.scores.sortedBy(Score::id).flatMap { score ->
        val pdf = File(root, "scores/${score.fileName}")
        if (!pdf.isFile) throw BackupException("Score file is missing")
        val annotation = File(root, "annotations/${score.id}.json")
        val hasAnnotations = openAtomicFile(annotation)?.use { true } ?: false
        listOfNotNull(pdf, annotation.takeIf { hasAnnotations })
    }
    if (files.size + textEntries.size > MAX_BACKUP_ENTRIES) {
        throw BackupException("Backup has too many entries")
    }
    if (files.any { it.length() > backupEntryLimit(it) }) {
        throw BackupException("Backup entry is too large")
    }
    if (textEntries.sumOf { it.second.size.toLong() } + files.sumOf(File::length) > MAX_BACKUP_BYTES) {
        throw BackupException("Backup is too large")
    }
    catalog.scores.forEach { score ->
        if (validatePdfFile(File(root, "scores/${score.fileName}")) != score.pageCount) {
            throw BackupException("Backup PDF page count changed")
        }
    }
    val scoresById = catalog.scores.associateBy(Score::id)
    files.filter { it.parentFile?.name == "annotations" }.forEach { file ->
        openAtomicFile(file)?.bufferedReader(Charsets.UTF_8)?.use {
            validateAnnotationPages(it.readText(), scoresById.getValue(file.nameWithoutExtension).pageCount)
        } ?: throw BackupException("Annotations file is missing")
    }
    var totalBytes = 0L
    ZipOutputStream(BufferedOutputStream(BackupOutputStream(destination))).use { zip ->
        fun writeEntry(name: String, input: InputStream, limit: Long) {
            input.use {
                zip.putNextEntry(ZipEntry(name))
                totalBytes += copyBounded(it, zip, limit, MAX_BACKUP_BYTES - totalBytes)
                zip.closeEntry()
            }
        }
        textEntries.forEach { (name, bytes) -> writeEntry(name, bytes.inputStream(), MAX_JSON_BYTES) }
        files.forEach { file ->
            val name = file.relativeTo(root).invariantSeparatorsPath
            writeEntry(name, file.inputStream(), backupEntryLimit(file))
        }
    }
}

private fun backupEntryLimit(file: File): Long =
    if (file.parentFile?.name == "scores") MAX_PDF_BYTES else MAX_JSON_BYTES

@Synchronized
internal fun restoreLibraryBackup(root: File, source: InputStream): BackupMetadata = source.use { input ->
    recoverLibraryBackup(root)
    val parent = root.parentFile ?: throw BackupException("Library has no parent directory")
    if (!parent.exists() && !parent.mkdirs()) throw BackupException("Could not create restore directory")
    val staging = File(parent, ".${root.name}-restore")
    val previous = File(parent, ".${root.name}-previous")
    if (previous.exists()) throw BackupException("Could not remove previous library")
    try {
        extractArchive(input, staging)
        val metadata = validateStaging(staging)
        File(staging, "manifest.json").delete()
        swapDirectories(root, staging, previous)
        previous.deleteRecursively()
        metadata
    } catch (error: BackupException) {
        staging.deleteRecursively()
        throw error
    } catch (error: Exception) {
        staging.deleteRecursively()
        throw BackupException("Backup could not be restored", error)
    }
}

@Synchronized
internal fun recoverLibraryBackup(root: File) {
    val parent = root.parentFile ?: throw BackupException("Library has no parent directory")
    val previous = File(parent, ".${root.name}-previous")
    if (previous.exists()) {
        if (root.exists()) {
            previous.deleteRecursively()
        } else if (!previous.renameTo(root)) {
            throw BackupException("Could not recover previous library")
        }
    }
    val staging = File(parent, ".${root.name}-restore")
    if (staging.exists() && !staging.deleteRecursively()) {
        throw BackupException("Could not remove interrupted restore")
    }
}

private fun extractArchive(source: InputStream, staging: File) {
    if (!staging.mkdirs()) throw BackupException("Could not create staging directory")
    val names = mutableSetOf<String>()
    var totalBytes = 0L
    var entries = 0
    ZipInputStream(BufferedInputStream(source)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entries++
            if (entries > MAX_BACKUP_ENTRIES) throw BackupException("Backup has too many entries")
            val name = entry.name
            validateEntryName(name)
            if (!names.add(name)) throw BackupException("Backup contains duplicate entries")
            if (entry.isDirectory) {
                if (name !in setOf("scores/", "annotations/")) {
                    throw BackupException("Backup contains an unsupported directory")
                }
                if (zip.read() != -1) throw BackupException("Backup directory contains data")
                File(staging, name).mkdirsOrThrow()
            } else {
                if (!isAllowedEntry(name)) throw BackupException("Backup contains an unsupported entry")
                val destination = File(staging, name).canonicalFile
                if (!destination.path.startsWith(staging.canonicalPath + File.separator)) {
                    throw BackupException("Backup entry escapes staging")
                }
                destination.parentFile?.mkdirsOrThrow()
                val entryLimit = if (name.startsWith("scores/")) MAX_PDF_BYTES else MAX_JSON_BYTES
                FileOutputStream(destination).use { output ->
                    totalBytes += copyBounded(zip, output, entryLimit, MAX_BACKUP_BYTES - totalBytes)
                    output.fd.sync()
                }
            }
            zip.closeEntry()
        }
    }
    if (totalBytes > MAX_BACKUP_BYTES) throw BackupException("Backup is too large")
}

private fun validateStaging(staging: File): BackupMetadata {
    val manifestFile = File(staging, "manifest.json")
    val catalogFile = File(staging, "catalog.json")
    if (!manifestFile.isFile || !catalogFile.isFile) {
        throw BackupException("Backup manifest or catalog is missing")
    }
    val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
    val version = manifest.requiredInt("version")
    if (manifest.optString("format") != BACKUP_FORMAT || version !in 1..BACKUP_VERSION) {
        throw BackupException("Unsupported backup format")
    }
    val languageTag = if (manifest.isNull("languageTag")) {
        null
    } else {
        manifest.getString("languageTag")
    }
    if (languageTag != null && languageTag !in AppLanguages.supportedTags) {
        throw BackupException("Backup contains an unsupported language")
    }
    val settings = manifest.getJSONObject("settings").toSettings(version)
    val catalog = CatalogJson.decode(catalogFile.readText(Charsets.UTF_8))
    val scoreDirectory = File(staging, "scores")
    val expectedScores = catalog.scores.map(Score::fileName).toSet()
    val actualScores = scoreDirectory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
    if (expectedScores != actualScores) throw BackupException("Backup score files do not match catalog")
    catalog.scores.forEach { score ->
        val file = File(scoreDirectory, score.fileName)
        val pageCount = validatePdfFile(file)
        if (pageCount != score.pageCount) throw BackupException("Backup PDF page count changed")
    }
    val scoresById = catalog.scores.associateBy(Score::id)
    File(staging, "annotations").listFiles().orEmpty().filter(File::isFile).forEach { file ->
        val scoreId = file.name.removeSuffix(".json")
        val score = scoresById[scoreId]
        if (file.name != "$scoreId.json" || score == null) {
            throw BackupException("Backup contains orphan annotations")
        }
        validateAnnotationPages(file.readText(Charsets.UTF_8), score.pageCount)
    }
    return BackupMetadata(settings, languageTag)
}

private fun validateAnnotationPages(raw: String, pageCount: Int) {
    if (AnnotationJson.decode(raw).pages.keys.any { it !in 0 until pageCount }) {
        throw BackupException("Backup contains annotations outside PDF pages")
    }
}

private fun swapDirectories(root: File, staging: File, previous: File) {
    val hadExisting = root.exists()
    if (hadExisting && !root.renameTo(previous)) {
        throw BackupException("Could not prepare current library for restore")
    }
    if (!staging.renameTo(root)) {
        if (hadExisting && !previous.renameTo(root)) {
            throw BackupException("Restore failed and current library could not be rolled back")
        }
        throw BackupException("Could not activate restored library")
    }
}

private fun validateEntryName(name: String) {
    if (
        name.isBlank() ||
        name.startsWith("/") ||
        '\\' in name ||
        name.split('/').any { it == "." || it == ".." }
    ) {
        throw BackupException("Backup contains an unsafe path")
    }
}

private fun isAllowedEntry(name: String): Boolean =
    name == "manifest.json" ||
        name == "catalog.json" ||
        name.matches(Regex("scores/[A-Za-z0-9-]+\\.pdf")) ||
        name.matches(Regex("annotations/[A-Za-z0-9-]+\\.json"))

private fun copyBounded(
    input: InputStream,
    output: OutputStream,
    entryLimit: Long,
    remainingTotal: Long,
): Long {
    var copied = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        copied += read
        if (copied > entryLimit || copied > remainingTotal) {
            throw BackupException("Backup entry is too large")
        }
        output.write(buffer, 0, read)
    }
    return copied
}

private class BackupOutputStream(output: OutputStream) : FilterOutputStream(output) {
    private var written = 0L

    override fun write(value: Int) {
        checkSize(1)
        out.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkSize(length)
        out.write(bytes, offset, length)
    }

    private fun checkSize(length: Int) {
        written += length
        if (written > MAX_BACKUP_BYTES) throw BackupException("Backup is too large")
    }
}

private fun AppSettings.toJson(): JSONObject = JSONObject()
    .put("keepScreenAwake", keepScreenAwake)
    .put("pageFit", pageFit.name)
    .put("pageTurnTaps", pageTurnTaps)
    .put("pageTurnSwipes", pageTurnSwipes)
    .put("autoHideControls", autoHideControls)
    .put("defaultTool", defaultTool.name)
    .put("textSize", textSize.name)
    .put("readerLayout", readerLayout.name)
    .put("themeMode", themeMode.name)
    .put("editor", JSONObject(AnnotationEditorSettingsJson.encode(editor)))

private fun JSONObject.toSettings(version: Int): AppSettings = try {
    val defaults = AppSettings()
    val editorRaw = if (has("editor") && !isNull("editor")) {
        getJSONObject("editor").toString()
    } else {
        null
    }
    val legacyEditorUi = editorRaw?.let(AnnotationEditorSettingsJson::isLegacy) ?: false
    val defaultTool = ReaderDefaultTool.valueOf(getString("defaultTool"))
    AppSettings(
        keepScreenAwake = getBoolean("keepScreenAwake"),
        pageFit = PageFit.valueOf(getString("pageFit")),
        pageTurnTaps = getBoolean("pageTurnTaps"),
        pageTurnSwipes = getBoolean("pageTurnSwipes"),
        autoHideControls = getBoolean("autoHideControls"),
        defaultTool = if (legacyEditorUi && defaultTool == ReaderDefaultTool.VIEW) {
            ReaderDefaultTool.PEN
        } else {
            defaultTool
        },
        textSize = AnnotationTextSize.valueOf(getString("textSize")),
        readerLayout = if (version >= 2) {
            ReaderLayout.valueOf(getString("readerLayout"))
        } else {
            defaults.readerLayout
        },
        themeMode = if (has("themeMode")) {
            ThemeMode.valueOf(getString("themeMode"))
        } else {
            defaults.themeMode
        },
        editor = editorRaw?.let(AnnotationEditorSettingsJson::decode) ?: defaults.editor,
    )
} catch (error: Exception) {
    throw BackupException("Backup settings are invalid", error)
}

private fun File.mkdirsOrThrow() {
    if (!exists() && !mkdirs()) throw BackupException("Could not create ${name}")
}
