package com.oceanreader.ui.shelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oceanreader.AppContainer
import com.oceanreader.data.BookEntity
import com.oceanreader.data.ImportResult
import com.oceanreader.data.ImportStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    init {
        viewModelScope.launch {
            combine(
                repo.books(),
                repo.continueReading(),
                search,
                selection
            ) { books, continueBook, query, sel ->
                val filtered = if (query.isBlank()) books else books.filter {
                    it.title.contains(query, true) || it.author.contains(query, true)
                }
                _ui.value.copy(
                    loading = false,
                    books = filtered,
                    continueReading = continueBook,
                    searchQuery = query,
                    selection = sel
                )
            }.collect { _ui.value = it }
        }
        viewModelScope.launch {
            settings.prefs.collect { p ->
                _ui.value = _ui.value.copy(
                    sortMode = p.sortMode,
                    gridColumns = p.gridColumns,
                    gridCover = p.showCoverGrid
                )
            }
        }
    }

    fun setSearch(q: String) { search.value = q }

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

    fun import(file: android.net.Uri) {
        if (_ui.value.importing) return
        _ui.value = _ui.value.copy(importing = true, importErrors = emptyList(), importStep = ImportStep.Copying)
        viewModelScope.launch {
            val result = repo.import(file) { step ->
                _ui.value = _ui.value.copy(importStep = step)
            }
            when (result) {
                is ImportResult.Success -> {
                    _ui.value = _ui.value.copy(importing = false, importStep = ImportStep.Done)
                }
                is ImportResult.Failure -> {
                    _ui.value = _ui.value.copy(
                        importing = false,
                        importErrors = _ui.value.importErrors + "${result.fileName}: ${result.reason}",
                        importStep = ImportStep.Done
                    )
                }
            }
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

    /** Opens the system file picker for the activity. */
    fun openImportPicker(onPicked: (android.net.Uri) -> Unit) {
        val activity = repo.appContext() as? com.oceanreader.MainActivity ?: return
        activity.requestImport(onPicked)
    }
}