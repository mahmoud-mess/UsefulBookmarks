package com.example.ithinkicanchangethislater.viewmodel // Or your package structure

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PdfViewModel : ViewModel() {

    // Holds the URI of the currently opened PDF
    private val _pdfUri = MutableStateFlow<Uri?>(null)
    val pdfUri: StateFlow<Uri?> = _pdfUri.asStateFlow()

    // Holds the last viewed page index
    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    fun setPdfUri(uri: Uri?) {
        _pdfUri.value = uri
        if (uri == null) {
            // Reset page if URI is cleared
            _currentPage.value = 0
        }
    }

    fun setCurrentPage(page: Int) {
        _currentPage.value = page
    }
}