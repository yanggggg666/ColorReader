package com.oceanreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.oceanreader.reader.ReaderViewModel

class ReaderViewModelFactory(
    private val bookId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ReaderViewModel(
            app = androidx.compose.ui.platform.LocalContext.current.applicationContext
                as android.app.Application,
            bookId = bookId
        ) as T
    }
}