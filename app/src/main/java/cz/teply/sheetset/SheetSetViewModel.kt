package cz.teply.sheetset

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.teply.sheetset.data.LibraryRepository
import cz.teply.sheetset.data.Bookmark
import cz.teply.sheetset.data.PdfImport
import cz.teply.sheetset.data.Score
import cz.teply.sheetset.pdf.DocumentAnnotations
import cz.teply.sheetset.pdf.PageAnnotation
import cz.teply.sheetset.pdf.PdfExporter
import cz.teply.sheetset.pdf.PdfExportLimitException
import cz.teply.sheetset.settings.AppSettings
import cz.teply.sheetset.settings.AppLanguages
import cz.teply.sheetset.settings.SettingsStore
import cz.teply.sheetset.settings.ReaderLayout
import cz.teply.sheetset.ui.ReaderPosition
import cz.teply.sheetset.ui.nextPosition
import cz.teply.sheetset.ui.previousPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID

class SheetSetViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(File(application.filesDir, "library"))
    private val settingsStore = SettingsStore(
        application.getSharedPreferences("sheetset-settings", Context.MODE_PRIVATE),
    )
    private val symbolTypeface = requireNotNull(
        ResourcesCompat.getFont(application, R.font.noto_music_regular),
    )
    private val annotationSaveMutex = Mutex()
    private val annotationSaves = AnnotationSaveCoordinator(
        scope = viewModelScope,
        persistenceMutex = annotationSaveMutex,
        persist = repository::saveAnnotations,
    )
    private val libraryMutationMutex = Mutex()
    private var readerOpenJob: Job? = null
    private var restoringBackup = false
    private val mutableState = MutableStateFlow(
        LibraryUiState(loading = true, settings = settingsStore.load()),
    )
    val state = mutableState.asStateFlow()

    init {
        launchAction { }
    }

    fun importPdfs(uris: List<Uri>) = launchAction {
        uris.forEach { uri -> repository.importPdf(pdfImport(uri)) }
    }

    fun offerIncomingPdfs(uris: List<Uri>) {
        mutableState.update { if (it.incomingPdfs.isEmpty()) it.copy(incomingPdfs = uris) else it }
    }

    fun dismissIncomingPdfs() {
        mutableState.update { it.copy(incomingPdfs = emptyList()) }
    }

    fun confirmIncomingPdfs(uris: List<Uri>) {
        // A stale dialog callback must not approve different PDFs.
        if (state.value.incomingPdfs != uris) return
        dismissIncomingPdfs()
        if (uris.isNotEmpty()) importPdfs(uris)
    }

    fun createSetlist(name: String) = launchAction { repository.createSetlist(name) }

    fun renameScore(scoreId: String, title: String) = launchAction {
        repository.renameScore(scoreId, title)
    }

    fun deleteScore(scoreId: String) = launchAction {
        annotationSaves.discard(scoreId)
        repository.deleteScore(scoreId)
    }

    fun updateScoreLabels(scoreId: String, labels: List<String>) = launchAction {
        repository.updateScoreLabels(scoreId, labels)
    }

    fun renameSetlist(setlistId: String, name: String) = launchAction {
        repository.renameSetlist(setlistId, name)
    }

    fun deleteSetlist(setlistId: String) = launchAction { repository.deleteSetlist(setlistId) }

    fun updateSetlistLabels(setlistId: String, labels: List<String>) = launchAction {
        repository.updateSetlistLabels(setlistId, labels)
    }

    fun addScores(setlistId: String, scoreIds: List<String>) = launchAction {
        repository.addScoresToSetlist(setlistId, scoreIds)
    }

    fun removeScore(setlistId: String, index: Int) = launchAction {
        repository.removeScoreFromSetlist(setlistId, index)
    }

    fun reorderScores(setlistId: String, scoreIds: List<String>) = launchAction {
        repository.reorderScores(setlistId, scoreIds)
    }

    fun updateSettings(settings: AppSettings) {
        if (restoringBackup) return
        settingsStore.save(settings)
        mutableState.update { it.copy(settings = settings) }
    }

    fun openScore(score: Score) {
        openReader(
            listOf(score.id),
            scoreIndex = 0,
            pageIndex = score.lastPageIndex,
            pagePart = score.lastPagePart,
        )
    }

    fun openScoreAt(score: Score, pageIndex: Int) {
        openReader(listOf(score.id), scoreIndex = 0, pageIndex = pageIndex, pagePart = 0)
    }

    fun jumpToPage(pageIndex: Int) {
        val reader = state.value.reader ?: return
        if (pageIndex !in 0 until reader.score.pageCount) return
        applyReaderPosition(reader, ReaderPosition(reader.scoreIndex, pageIndex, 0))
    }

    fun addBookmark(title: String) {
        val reader = state.value.reader ?: return
        launchAction {
            repository.addBookmark(
                reader.score.id,
                Bookmark(UUID.randomUUID().toString(), title, reader.pageIndex),
            )
        }
    }

    fun renameBookmark(bookmarkId: String, title: String) {
        val scoreId = state.value.reader?.score?.id ?: return
        launchAction { repository.renameBookmark(scoreId, bookmarkId, title) }
    }

    fun deleteBookmark(bookmarkId: String) {
        val scoreId = state.value.reader?.score?.id ?: return
        launchAction { repository.deleteBookmark(scoreId, bookmarkId) }
    }

    fun openSetlistScore(setlistId: String, scoreIndex: Int) {
        val ids = state.value.catalog.setlists.firstOrNull { it.id == setlistId }?.scoreIds ?: return
        openReader(ids, scoreIndex, pageIndex = 0, pagePart = 0)
    }

    fun closeReader() {
        val opening = readerOpenJob?.isActive == true
        readerOpenJob?.cancel()
        readerOpenJob = null
        mutableState.update { it.copy(reader = null, loading = if (opening) false else it.loading) }
    }

    fun nextPage(layout: ReaderLayout) {
        movePage(layout, forward = true)
    }

    fun previousPage(layout: ReaderLayout) {
        movePage(layout, forward = false)
    }

    private fun movePage(layout: ReaderLayout, forward: Boolean) {
        val reader = state.value.reader ?: return
        val catalog = state.value.catalog
        val scores = catalog.scores.associateBy(Score::id)
        val pageCounts = reader.scoreIds.map { id -> scores[id]?.pageCount ?: return }
        val current = ReaderPosition(reader.scoreIndex, reader.pageIndex, reader.pagePart)
        val target = if (forward) {
            nextPosition(current, pageCounts, layout)
        } else {
            previousPosition(current, pageCounts, layout)
        } ?: return
        applyReaderPosition(reader, target)
    }

    private fun applyReaderPosition(reader: ReaderUiState, target: ReaderPosition) {
        if (restoringBackup) return
        val opening = readerOpenJob?.isActive == true
        readerOpenJob?.cancel()
        readerOpenJob = null
        if (target.scoreIndex == reader.scoreIndex) {
            val viewedAt = System.currentTimeMillis()
            val updatedCatalog = state.value.catalog.saveReaderPosition(
                reader.score.id,
                target.pageIndex,
                target.pagePart,
                viewedAt,
            )
            val updatedScore = updatedCatalog.scores.first { it.id == reader.score.id }
            mutableState.update {
                it.copy(
                    catalog = updatedCatalog,
                    loading = if (opening) false else it.loading,
                    reader = reader.copy(
                        score = updatedScore,
                        pageIndex = target.pageIndex,
                        pagePart = target.pagePart,
                    ),
                )
            }
            viewModelScope.launch {
                libraryMutationMutex.withLock {
                    try {
                        repository.saveReaderPosition(
                            reader.score.id,
                            target.pageIndex,
                            target.pagePart,
                            viewedAt,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        mutableState.update { it.copy(error = R.string.action_failed) }
                    }
                }
            }
            return
        }
        openReader(
            reader.scoreIds,
            target.scoreIndex,
            target.pageIndex,
            target.pagePart,
        )
    }

    fun saveAnnotations(
        scoreId: String,
        pageIndex: Int,
        annotations: DocumentAnnotations,
    ) {
        if (restoringBackup) return
        val pageAnnotations = annotations.pages[pageIndex].orEmpty()
        mutableState.update { current ->
            current.mergePageAnnotations(scoreId, pageIndex, pageAnnotations)
        }
        val result = annotationSaves.enqueue(
            scoreId,
            pageIndex,
            pageAnnotations,
            annotations,
        )
        result.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                viewModelScope.launch {
                    if (annotationSaves.isLatest(scoreId, result)) {
                        mutableState.update { it.copy(error = R.string.action_failed) }
                    }
                }
            }
        }
    }

    fun exportPdf(uri: Uri) {
        if (restoringBackup) return
        val reader = state.value.reader ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    PdfExporter.export(
                        reader.file,
                        { resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException(uri.toString()) },
                        reader.annotations,
                        symbolTypeface,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: PdfExportLimitException) {
                mutableState.update { it.copy(error = R.string.pdf_export_limit_reached) }
            } catch (_: Exception) {
                mutableState.update { it.copy(error = R.string.action_failed) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun createBackup(uri: Uri) {
        if (restoringBackup) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val application = getApplication<Application>()
                annotationSaves.awaitLatest()
                val languageTag = currentLanguageTag()
                val settings = state.value.settings
                withContext(Dispatchers.IO) {
                    application.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        repository.createBackup(output, settings, languageTag)
                    } ?: throw FileNotFoundException(uri.toString())
                }
                mutableState.update { it.copy(loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = R.string.action_failed) }
            }
        }
    }

    fun createSharedBackup(onReady: (Uri) -> Unit) {
        if (restoringBackup) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            val application = getApplication<Application>()
            try {
                annotationSaves.awaitLatest()
                val languageTag = currentLanguageTag()
                val settings = state.value.settings
                val uri = withContext(Dispatchers.IO) {
                    createSharedBackupFile(application, settings, languageTag)
                }
                mutableState.update { it.copy(loading = false) }
                onReady(uri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = R.string.action_failed) }
            }
        }
    }

    fun restoreBackup(uri: Uri) {
        if (restoringBackup) return
        restoringBackup = true
        readerOpenJob?.cancel()
        readerOpenJob = null
        mutableState.update { it.copy(loading = true, error = null, reader = null) }
        viewModelScope.launch {
            try {
                libraryMutationMutex.withLock {
                    val application = getApplication<Application>()
                    annotationSaves.awaitLatest()
                    val metadata = withContext(Dispatchers.IO) {
                        val input = application.contentResolver.openInputStream(uri)
                            ?: throw FileNotFoundException(uri.toString())
                        input.use { backup -> repository.restoreBackup(backup) }
                    }
                    annotationSaves.discardAll()
                    val catalog = repository.load()
                    mutableState.update {
                        it.copy(
                            catalog = catalog,
                            reader = null,
                            loading = false,
                            settings = metadata?.settings ?: it.settings,
                        )
                    }
                    metadata?.let {
                        settingsStore.save(it.settings)
                        AppLanguages.select(application, it.languageTag)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = R.string.action_failed) }
            } finally {
                restoringBackup = false
            }
        }
    }

    private fun launchAction(action: suspend () -> Unit) {
        if (restoringBackup) return
        viewModelScope.launch {
            libraryMutationMutex.withLock {
                mutableState.update { it.copy(loading = true, error = null) }
                try {
                    val failed = try {
                        action()
                        false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        true
                    }
                    val catalog = repository.load()
                    mutableState.update {
                        // Catalog edits leave positions unchanged; queued page saves may be newer.
                        val localScores = it.catalog.scores.associateBy(Score::id)
                        val updatedCatalog = catalog.copy(scores = catalog.scores.map { score ->
                            val local = localScores[score.id] ?: return@map score
                            score.copy(
                                lastPageIndex = local.lastPageIndex,
                                lastPagePart = local.lastPagePart,
                                lastViewedAtEpochMs = local.lastViewedAtEpochMs,
                            )
                        })
                        val reader = it.reader?.let { current ->
                            updatedCatalog.scores.firstOrNull { score -> score.id == current.score.id }
                                ?.let { score -> current.copy(score = score) }
                        }
                        it.copy(
                            catalog = updatedCatalog,
                            reader = reader,
                            loading = restoringBackup,
                            error = if (failed) R.string.action_failed else null,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutableState.update { it.copy(loading = restoringBackup, error = R.string.action_failed) }
                }
            }
        }
    }

    private fun openReader(
        scoreIds: List<String>,
        scoreIndex: Int,
        pageIndex: Int,
        pagePart: Int,
    ) {
        if (restoringBackup) return
        val scoreId = scoreIds.getOrNull(scoreIndex) ?: return
        readerOpenJob?.cancel()
        readerOpenJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                val score = state.value.catalog.scores.firstOrNull { it.id == scoreId }
                    ?: throw IllegalArgumentException("Score does not exist")
                val file = withContext(Dispatchers.IO) { repository.pdfFile(score) }
                val annotations = annotationSaveMutex.withLock {
                    annotationSaves.pendingAnnotations(score.id) ?: repository.loadAnnotations(score.id)
                }
                libraryMutationMutex.withLock {
                    repository.saveReaderPosition(
                        score.id,
                        page = pageIndex,
                        part = pagePart,
                        viewedAt = System.currentTimeMillis(),
                    )
                    val catalog = repository.load()
                    val savedScore = catalog.scores.first { it.id == score.id }
                    val pendingAnnotations = annotationSaves.pendingAnnotations(score.id)
                    mutableState.update {
                        it.copy(
                            catalog = catalog,
                            loading = false,
                            reader = ReaderUiState(
                                score = savedScore,
                                file = file,
                                scoreIds = scoreIds,
                                scoreIndex = scoreIndex,
                                pageIndex = pageIndex,
                                pagePart = pagePart,
                                annotations = it.reader?.takeIf { reader -> reader.score.id == score.id }?.annotations
                                    ?: pendingAnnotations ?: annotations,
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = R.string.action_failed) }
            }
        }
    }

    private suspend fun createSharedBackupFile(
        application: Application,
        settings: AppSettings,
        languageTag: String?,
    ): Uri = sharedBackupMutex.withLock {
        val directory = File(application.cacheDir, "shared-backups")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create share directory")
        }
        val files = directory.listFiles().orEmpty().filter(File::isFile)
        val oldestAllowed = System.currentTimeMillis() - SHARED_BACKUP_MAX_AGE_MS
        files.filter { it.name.startsWith("SheetSet-Backup") && it.extension == "zip" }
            .sortedByDescending(File::lastModified)
            .forEachIndexed { index, file ->
                if (index >= MAX_SHARED_BACKUPS - 1 || file.lastModified() < oldestAllowed) {
                    if (!file.delete()) throw IllegalStateException("Could not remove old shared backup")
                }
            }
        files.filter { it.name.startsWith(".SheetSet-Backup") && it.extension == "tmp" }
            .forEach { file ->
                if (!file.delete()) throw IllegalStateException("Could not remove unfinished shared backup")
            }
        val temporary = File.createTempFile(".SheetSet-Backup-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                repository.createBackup(output, settings, languageTag)
            }
            val destination = File(
                directory,
                temporary.name.removePrefix(".").removeSuffix(".tmp") + ".zip",
            )
            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("Could not prepare shared backup")
            }
            FileProvider.getUriForFile(
                application,
                "${application.packageName}.files",
                destination,
            )
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        // Cache cleanup must not delete another ViewModel's unfinished export.
        val sharedBackupMutex = Mutex()
        const val MAX_SHARED_BACKUPS = 3
        const val SHARED_BACKUP_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }

    private suspend fun pdfImport(uri: Uri): PdfImport = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val fallbackName = getApplication<Application>().getString(R.string.untitled_pdf_file)
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { fallbackName }
        var size: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        if (name.isBlank()) name = fallbackName
        PdfImport(name, resolver.getType(uri), size) {
            resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        }
    }
}

private fun AndroidViewModel.currentLanguageTag(): String? =
    AppLanguages.currentTag(getApplication())
