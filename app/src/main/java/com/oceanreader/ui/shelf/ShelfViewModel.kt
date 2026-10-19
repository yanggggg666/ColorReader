package com.oceanreader.ui.shelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oceanreader.AppContainer
import com.oceanreader.data.db.BookEntity
import com.oceanreader.data.ImportProgress
import com.oceanreader.data.ImportStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shelf view state. */
data class ShelfUiState(
    val loading: Boolean = true,
    val books: List<BookEntity> = emptyList(),
    val continueReading: BookEntity? = null,
    val searchQuery: String = "",
    val importing: Boolean = false,
    val importFileName: String = "",
    val importStep: ImportStep = ImportStep.Done,
    val importErrors: List<String> = emptyList(),
    /** Progress of the running batch import (0 until the first file starts). */
    val importTotal: Int = 0,
    val importCompleted: Int = 0,
    val importSucceeded: Int = 0,
    /** One-shot summary of the finished batch; the UI consumes it once. */
    val importResultMessage: String? = null,
    val selection: Set<String> = emptySet(),
    val sortMode: Int = 0,
    val gridColumns: Int = 0,
    val gridCover: Boolean = true
)

class ShelfViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppContainer.from(app).repository
    private val settings = AppContainer.from(app).settings

    private val _ui = MutableStateFlow(ShelfUiState())
    val ui: StateFlow<ShelfUiState> = _ui.asStateFlow()

    private val search = MutableStateFlow("")
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val sort = MutableStateFlow(com.oceanreader.data.model.ShelfSort.RECENT)

    init {
        viewModelScope.launch {
            settings.prefs.collect { p -> sort.value = p.shelfSort }
        }
        viewModelScope.launch {
            combine(
                repo.books(),
                repo.continueReading(),
                search,
                selection,
                sort
            ) { books, continueBook, query, sel, shelfSort ->
                val filtered = if (query.isBlank()) books else books.filter {
                    it.title.contains(query, true) || it.author.contains(query, true)
                }
                // The DAO returns "recently read first"; other orderings are
                // applied in memory, which is cheap for a shelf-sized list.
                val ordered = when (shelfSort) {
                    com.oceanreader.data.model.ShelfSort.RECENT -> filtered
                    com.oceanreader.data.model.ShelfSort.ADDED ->
                        filtered.sortedByDescending { it.addedAt }
                    com.oceanreader.data.model.ShelfSort.TITLE ->
                        filtered.sortedBy { it.title }
                    com.oceanreader.data.model.ShelfSort.AUTHOR ->
                        filtered.sortedBy { it.author }
                    com.oceanreader.data.model.ShelfSort.PROGRESS ->
                        filtered.sortedByDescending { it.percent }
                }
                _ui.value.copy(
                    loading = false,
                    books = ordered,
                    continueReading = continueBook,
                    searchQuery = query,
                    selection = sel,
                    sortMode = shelfSort.ordinal
                )
            }.collect { _ui.value = it }
        }
        viewModelScope.launch {
            settings.prefs.collect { p ->
                _ui.value = _ui.value.copy(
                    sortMode = p.shelfSort.ordinal,
                    gridColumns = p.gridColumns,
                    gridCover = p.showCoverGrid
                )
            }
        }
    }

    fun setSearch(q: String) { search.value = q }

    /** Changes shelf ordering; persisted so it survives restarts. */
    fun setSort(shelfSort: com.oceanreader.data.model.ShelfSort) {
        viewModelScope.launch { settings.setShelfSort(shelfSort) }
    }

    fun toggleSelection(id: String) {
        val current = selection.value.toMutableSet()
        if (!current.add(id)) current.remove(id)
        selection.value = current
        _ui.value = _ui.value.copy(selection = current)
    }

    fun clearSelection() {
        selection.value = emptySet()
        _ui.value = _ui.value.copy(selection = emptySet())
    }

    /** Imports one or many picked documents (multi-select). */
    fun importFiles(uris: List<android.net.Uri>) {
        if (_ui.value.importing || uris.isEmpty()) return
        _ui.value = _ui.value.copy(
            importing = true,
            importErrors = emptyList(),
            importStep = ImportStep.Copying,
            importTotal = uris.size,
            importCompleted = 0,
            importSucceeded = 0
        )
        viewModelScope.launch {
            val progress = repo.importAll(uris) { p ->
                _ui.value = _ui.value.copy(
                    importing = p.running,
                    importFileName = p.currentName,
                    importStep = if (p.running) ImportStep.Parsing else ImportStep.Done,
                    importTotal = p.total,
                    importCompleted = p.completed,
                    importSucceeded = p.succeeded,
                    importErrors = p.failures
                )
            }
            _ui.value = _ui.value.copy(
                importing = false,
                importStep = ImportStep.Done,
                importTotal = progress.total,
                importCompleted = progress.completed,
                importSucceeded = progress.succeeded,
                importErrors = progress.failures,
                importFileName = "",
                importResultMessage = summaryOf(progress)
            )
        }
    }

    /** Builds the one-shot snackbar text for a finished batch. */
    private fun summaryOf(p: ImportProgress): String {
        if (p.total <= 0) return ""
        val ok = "成功导入 ${p.succeeded} 本"
        val bad = if (p.failures.isEmpty()) {
            ""
        } else {
            val first = p.failures.first()
            val more = if (p.failures.size > 1) " 等 ${p.failures.size} 个" else ""
            "，失败：$first$more"
        }
        return ok + bad
    }

    fun consumeImportResult() {
        _ui.value = _ui.value.copy(importResultMessage = null)
    }

    /** Keeps the single-file entry point working (shares, “open with”). */
    fun import(file: android.net.Uri) = importFiles(listOf(file))

    /**
     * Walks a picked folder and imports every supported document inside it.
     * The scan itself is fast (metadata only); the UI reports how many books
     * were found before the copying starts.
     */
    fun importFolder(treeUri: android.net.Uri) {
        if (_ui.value.importing) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { repo.documentsInTree(treeUri) }.getOrDefault(emptyList())
            }
            if (found.isEmpty()) {
                _ui.value = _ui.value.copy(
                    importing = false,
                    importResultMessage = "文件夹里没有可导入的书籍（支持 TXT/EPUB/PDF/MOBI/Word/MD/RTF/HTML/FB2）"
                )
                return@launch
            }
            importFiles(found)
        }
    }

    fun deleteSelected() {
        val ids = _ui.value.selection.toList()
        viewModelScope.launch {
            ids.forEach { id ->
                repo.bookOnce(id)?.let { repo.delete(it) }
            }
            clearSelection()
        }
    }

    fun pinSelected(pinned: Boolean) {
        val ids = _ui.value.selection.toList()
        viewModelScope.launch {
            ids.forEach { id -> repo.bookOnce(id)?.let { repo.setPinned(it, pinned) } }
            clearSelection()
        }
    }
}