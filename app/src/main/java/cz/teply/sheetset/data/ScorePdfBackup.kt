package cz.teply.sheetset.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val MAX_HIVE_BYTES = 16L * 1024L * 1024L
private const val MAX_LOCK_BYTES = 4L * 1024L

internal data class PreparedScorePdfImport(
    val catalog: LibraryCatalog,
    val createdFiles: List<File>,
)

internal fun prepareScorePdfImport(
    root: File,
    current: LibraryCatalog,
    archive: File,
): PreparedScorePdfImport {
    val parent = root.parentFile ?: throw BackupException("Library has no parent directory")
    val staging = File(parent, ".scorepdf-import-${UUID.randomUUID()}")
    val createdFiles = mutableListOf<File>()
    try {
        if (!staging.mkdirs()) throw BackupException("Could not create import directory")
        ZipFile(archive).use { zip ->
            val entries = validateEntries(zip)
            val scoreEntry = entries["newscorebox.hive"]
                ?: throw BackupException("ScorePDF score metadata is missing")
            val setlistEntry = entries["setlistbox.hive"]
                ?: throw BackupException("ScorePDF setlist metadata is missing")
            val scoreRecords = ScorePdfHive.readScores(zip.readBounded(scoreEntry, MAX_HIVE_BYTES))
            val setlistRecords = ScorePdfHive.readSetlists(zip.readBounded(setlistEntry, MAX_HIVE_BYTES))
            require(setlistRecords.all { it.scoreKeys.size <= MAX_SETLIST_ENTRIES }) {
                "Setlist has too many entries"
            }
            require(
                current.setlists.sumOf { it.scoreIds.size.toLong() } +
                    setlistRecords.sumOf { it.scoreKeys.size.toLong() } <= MAX_CATALOG_SETLIST_ENTRIES,
            ) { "Library has too many setlist entries" }
            val pdfEntries = entries.filterValues { entry ->
                entry.name.endsWith(".pdf", ignoreCase = true)
            }
            val referencedNames = scoreRecords.mapTo(linkedSetOf(), ScorePdfScore::fileName)
            if (!pdfEntries.keys.containsAll(referencedNames)) {
                throw BackupException("ScorePDF backup is missing a referenced PDF")
            }
            val scoreKeys = scoreRecords.mapTo(mutableSetOf(), ScorePdfScore::key)
            if (setlistRecords.any { setlist -> setlist.scoreKeys.any { it !in scoreKeys } }) {
                throw BackupException("ScorePDF setlist references a missing score")
            }

            val stagedPdfs = linkedMapOf<String, Pair<File, Int>>()
            pdfEntries.toSortedMap().forEach { (name, entry) ->
                val file = File(staging, "${stagedPdfs.size}.pdf")
                zip.getInputStream(entry).use { input -> input.copyBounded(file, entry) }
                stagedPdfs[name] = file to validatePdfFile(file)
            }

            val importNames = scoreRecords.map(ScorePdfScore::fileName) +
                (pdfEntries.keys - referencedNames).sorted()
            val importBytes = importNames.sumOf { name -> stagedPdfs.getValue(name).first.length() }
            if (importBytes > MAX_BACKUP_BYTES) {
                throw BackupException("ScorePDF import is too large")
            }

            val scoresDirectory = File(root, "scores")
            scoresDirectory.mkdirsOrThrow()
            val importedAt = System.currentTimeMillis()
            val scoresByKey = linkedMapOf<Long, Score>()
            val importedScores = scoreRecords.map { record ->
                val score = copyScore(
                    scoresDirectory = scoresDirectory,
                    source = stagedPdfs.getValue(record.fileName),
                    title = record.title,
                    importedAt = importedAt,
                    createdFiles = createdFiles,
                )
                scoresByKey[record.key] = score
                score
            }.toMutableList()
            (pdfEntries.keys - referencedNames).sorted().forEach { name ->
                importedScores += copyScore(
                    scoresDirectory = scoresDirectory,
                    source = stagedPdfs.getValue(name),
                    title = cleanPdfTitle(name),
                    importedAt = importedAt,
                    createdFiles = createdFiles,
                )
            }
            val importedSetlists = setlistRecords.map { record ->
                Setlist(
                    id = UUID.randomUUID().toString(),
                    name = record.name,
                    scoreIds = record.scoreKeys.map { key -> scoresByKey.getValue(key).id },
                    createdAtEpochMs = importedAt,
                )
            }
            return PreparedScorePdfImport(
                catalog = current.copy(
                    scores = current.scores + importedScores,
                    setlists = current.setlists + importedSetlists,
                ),
                createdFiles = createdFiles.toList(),
            )
        }
    } catch (error: BackupException) {
        createdFiles.forEach(File::delete)
        throw error
    } catch (error: Exception) {
        createdFiles.forEach(File::delete)
        throw BackupException("ScorePDF backup could not be imported", error)
    } finally {
        staging.deleteRecursively()
    }
}

private fun validateEntries(zip: ZipFile): Map<String, ZipEntry> {
    val entries = linkedMapOf<String, ZipEntry>()
    val normalizedNames = mutableSetOf<String>()
    val enumeration = zip.entries()
    var count = 0
    var totalSize = 0L
    while (enumeration.hasMoreElements()) {
        val entry = enumeration.nextElement()
        count++
        if (count > MAX_BACKUP_ENTRIES) throw BackupException("Backup has too many entries")
        val name = entry.name
        validateFlatEntryName(name)
        if (entry.isDirectory) throw BackupException("ScorePDF backup contains a directory")
        if (!normalizedNames.add(name.lowercase(Locale.ROOT))) {
            throw BackupException("Backup contains duplicate entries")
        }
        val limit = when {
            name.endsWith(".pdf", ignoreCase = true) -> MAX_PDF_BYTES
            name.endsWith(".hive", ignoreCase = true) -> MAX_HIVE_BYTES
            name.endsWith(".lock", ignoreCase = true) -> MAX_LOCK_BYTES
            else -> throw BackupException("ScorePDF backup contains an unsupported entry")
        }
        if (entry.size !in 0..limit) throw BackupException("Backup entry size is invalid")
        if (entry.size > MAX_BACKUP_BYTES - totalSize) {
            throw BackupException("Backup is too large")
        }
        totalSize += entry.size
        entries[name] = entry
    }
    return entries
}

private fun validateFlatEntryName(name: String) {
    if (
        name.isBlank() ||
        name.length > 255 ||
        name.startsWith("/") ||
        '/' in name ||
        '\\' in name ||
        name.any { it.code == 0 } ||
        name == "." ||
        name == ".."
    ) {
        throw BackupException("Backup contains an unsafe path")
    }
}

private fun ZipFile.readBounded(entry: ZipEntry, limit: Long): ByteArray =
    getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream()
        input.copyBounded(output::write, entry, limit)
        output.toByteArray()
    }

private fun InputStream.copyBounded(destination: File, entry: ZipEntry) {
    FileOutputStream(destination).use { output ->
        copyBounded(output::write, entry, MAX_PDF_BYTES)
        output.fd.sync()
    }
}

private inline fun InputStream.copyBounded(
    write: (ByteArray, Int, Int) -> Unit,
    entry: ZipEntry,
    limit: Long,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val checksum = CRC32()
    var copied = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        copied += read
        if (copied > limit || copied > entry.size) throw BackupException("Backup entry is too large")
        checksum.update(buffer, 0, read)
        write(buffer, 0, read)
    }
    if (copied != entry.size) throw BackupException("Backup entry size does not match metadata")
    if (checksum.value != entry.crc) throw BackupException("Backup entry checksum is invalid")
}

private fun copyScore(
    scoresDirectory: File,
    source: Pair<File, Int>,
    title: String,
    importedAt: Long,
    createdFiles: MutableList<File>,
): Score {
    val id = UUID.randomUUID().toString()
    val destination = File(scoresDirectory, "$id.pdf")
    createdFiles += destination
    FileOutputStream(destination).use { output ->
        source.first.inputStream().use { input -> input.copyTo(output) }
        output.fd.sync()
    }
    return Score(id, title, destination.name, source.second, importedAt)
}

private fun cleanPdfTitle(fileName: String): String = fileName
    .replace(Regex("(?i)\\.pdf$"), "")
    .trim()
    .take(MAX_TITLE_LENGTH)
    .ifEmpty { "Untitled PDF" }

private fun File.mkdirsOrThrow() {
    if (!exists() && !mkdirs()) throw BackupException("Could not create directory")
}
