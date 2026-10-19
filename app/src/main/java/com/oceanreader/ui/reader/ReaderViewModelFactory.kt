package com.oceanreader.ui.reader

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.oceanreader.reader.ReaderViewModel

/**
 * Builds a [ReaderViewModel] for a specific book. The Application is supplied
 * by the caller (the composable passes `LocalContext.current.applicationContext`)
 * so this factory stays free of Compose dependencies.
 */
class ReaderViewModelFactory(
    private val app: Application,
    private val bookId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ReaderViewModel(app = app, bookId = bookId) as T
    }
}