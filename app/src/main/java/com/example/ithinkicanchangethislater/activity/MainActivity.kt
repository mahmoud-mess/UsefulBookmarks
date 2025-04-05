package com.example.ithinkicanchangethislater.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns // Import for getting filename
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu // Import Menu
import android.view.MenuItem // Import MenuItem
import android.view.View // Import View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible // Import isVisible extension
import androidx.lifecycle.lifecycleScope
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder // Import Material Dialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.example.ithinkicanchangethislater.R
import com.example.ithinkicanchangethislater.data.database.AppDatabase
import com.example.ithinkicanchangethislater.data.database.BookmarkDao
import com.example.ithinkicanchangethislater.data.entity.Bookmark
import com.example.ithinkicanchangethislater.databinding.ActivityMainBinding
import com.example.ithinkicanchangethislater.databinding.DialogAddBookmarkBinding
import com.example.ithinkicanchangethislater.util.DateUtils
import com.example.ithinkicanchangethislater.viewmodel.PdfViewModel

class MainActivity : AppCompatActivity(), OnPageChangeListener, OnLoadCompleteListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bookmarkDao: BookmarkDao
    private var bookmarksForCurrentPdf: List<Bookmark> = emptyList()
    private val viewModel: PdfViewModel by viewModels()

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.setPdfUri(uri) // Update ViewModel
                // Persist permission
                val contentResolver = applicationContext.contentResolver
                val takeFlags: Int = result.data?.flags?.and(
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                ) ?: 0
                try {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: SecurityException) {
                    Log.e("MainActivity", "Failed to take persistable permission", e)
                    Toast.makeText(this, R.string.could_not_get_permissions, Toast.LENGTH_SHORT).show()
                    viewModel.setPdfUri(null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar) // Essential for Toolbar

        bookmarkDao = AppDatabase.getDatabase(applicationContext).bookmarkDao()

        setupViews()
        observeViewModel()

        // Initial UI State is handled by observeViewModel based on URI
        updateUiState(isLoading = false, pdfLoaded = false, hasBookmarks = false)
    }

    // Inflate the overflow menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    // Handle menu item clicks
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_pdf -> {
                openPdfChooser()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun observeViewModel() {
        viewModel.pdfUri
            .onEach { uri ->
                if (uri != null) {
                    Log.d("MainActivity", "ViewModel emitted URI: $uri. Loading PDF.")
                    updateUiState(isLoading = true, pdfLoaded = false) // Show loading state
                    setToolbarTitle(uri) // Set filename as title
                    loadPdf(uri) // Start loading
                } else {
                    Log.d("MainActivity", "ViewModel URI is null.")
                    updateUiState(isLoading = false, pdfLoaded = false) // Show initial prompt state
                    setToolbarTitle(null) // Reset title
                    bookmarksForCurrentPdf = emptyList() // Clear bookmark list
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun setupViews() {
        // FAB listener
        binding.fabAddBookmark.setOnClickListener {
            viewModel.pdfUri.value?.let { uri ->
                showAddBookmarkDialog(uri.toString(), viewModel.currentPage.value)
            } ?: Toast.makeText(this, R.string.load_pdf_first, Toast.LENGTH_SHORT).show()
        }

        // Show Bookmarks Button listener
        binding.buttonShowBookmarks.setOnClickListener {
            if (bookmarksForCurrentPdf.isEmpty()) {
                Toast.makeText(this, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
            } else {
                showBookmarksListDialog()
            }
        }

        // Removed old button setup
    }

    // *** ADDED: Helper function to manage UI visibility states ***
    private fun updateUiState(isLoading: Boolean, pdfLoaded: Boolean, hasBookmarks: Boolean? = null) {
        binding.progressBar.isVisible = isLoading
        binding.textViewOpenFilePrompt.isVisible = !isLoading && !pdfLoaded
        binding.pdfView.isVisible = !isLoading && pdfLoaded

        // Only show FAB if PDF is loaded and not currently loading
        binding.fabAddBookmark.isVisible = !isLoading && pdfLoaded

        // Update visibility/state of bookmark button if needed
        if (hasBookmarks != null) {
            binding.buttonShowBookmarks.isEnabled = !isLoading && pdfLoaded && hasBookmarks
            binding.buttonShowBookmarks.alpha = if (binding.buttonShowBookmarks.isEnabled) 1.0f else 0.5f // Visual cue
        } else {
            // If hasBookmarks not provided, base it on pdfLoaded state temporarily
            binding.buttonShowBookmarks.isEnabled = !isLoading && pdfLoaded
            binding.buttonShowBookmarks.alpha = if (binding.buttonShowBookmarks.isEnabled) 1.0f else 0.5f
        }
    }

    // *** ADDED: Helper to get filename from URI ***
    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        // Try to query the filename from the content resolver
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        // Fallback if query fails (might just show the last path segment)
        if (fileName == null) {
            fileName = uri.path?.substringAfterLast('/')
        }
        return fileName
    }

    // *** ADDED: Helper to set Toolbar title ***
    private fun setToolbarTitle(uri: Uri?) {
        binding.toolbar.title = if (uri != null) {
            getFileName(uri) ?: getString(R.string.app_name) // Show filename or default
        } else {
            getString(R.string.app_name) // Default title
        }
        binding.toolbar.subtitle = null // Clear subtitle initially
    }


    private fun openPdfChooser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        pdfPickerLauncher.launch(intent)
    }

    private fun loadPdf(pdfUri: Uri) {
        Log.d("MainActivity", "loadPdf called for: $pdfUri")
        // Load bookmarks first
        loadBookmarksForPdf(pdfUri.toString())

        // PDFView setup
        binding.pdfView.recycle()
        binding.pdfView.fromUri(pdfUri)
            .defaultPage(viewModel.currentPage.value)
            .onPageChange(this)
            .onLoad(this)
            .onError(this::onPdfError)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableAntialiasing(true)
            .spacing(10)
            .load() // This is asynchronous
    }

    private fun onPdfError(t: Throwable?) {
        Log.e("MainActivity", "Error loading PDF", t)
        Toast.makeText(this, getString(R.string.error_loading_pdf) + ": ${t?.message}", Toast.LENGTH_LONG).show()
        updateUiState(isLoading = false, pdfLoaded = false, hasBookmarks = false) // Show prompt state on error
        viewModel.setPdfUri(null) // Clear ViewModel state on error
        setToolbarTitle(null) // Reset title
    }

    private fun loadBookmarksForPdf(pdfUriString: String) {
        lifecycleScope.launch {
            bookmarkDao.getBookmarksForPdf(pdfUriString).collectLatest { bookmarks ->
                Log.d("MainActivity", "Loaded ${bookmarks.size} bookmarks for $pdfUriString")
                bookmarksForCurrentPdf = bookmarks
                // Update UI state regarding bookmarks (only if PDF is considered loaded)
                if (viewModel.pdfUri.value != null) { // Check if we still have a valid PDF loaded
                    updateUiState(isLoading = false, pdfLoaded = true, hasBookmarks = bookmarks.isNotEmpty())
                }
            }
        }
    }

    private fun showBookmarksListDialog() {
        val items = bookmarksForCurrentPdf.map {
            "${it.title} (${DateUtils.formatDate(it.creationDate)})"
        }.toTypedArray()

        // Use MaterialAlertDialogBuilder
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks)
            .setItems(items) { dialog, which ->
                val selectedBookmark = bookmarksForCurrentPdf[which]
                showBookmarkSummaryDialog(selectedBookmark)
                // Dialog dismisses automatically on item click for setItems
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBookmarkSummaryDialog(bookmark: Bookmark) {
        val summaryText = if (bookmark.summary.isBlank()) {
            getString(R.string.no_summary)
        } else {
            bookmark.summary
        }
        val message = "Date: ${DateUtils.formatDate(bookmark.creationDate)}\n\n$summaryText"

        // Use MaterialAlertDialogBuilder
        MaterialAlertDialogBuilder(this)
            .setTitle(bookmark.title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.go_to_page, bookmark.pageIndex + 1)) { dialog, _ ->
                binding.pdfView.jumpTo(bookmark.pageIndex, true)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }


    private fun showAddBookmarkDialog(pdfUriString: String, pageIndex: Int) {
        val dialogBinding = DialogAddBookmarkBinding.inflate(LayoutInflater.from(this))

        // Use MaterialAlertDialogBuilder
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_bookmark_dialog_title, pageIndex + 1))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val title = dialogBinding.editTextBookmarkTitle.text.toString().trim()
                val summary = dialogBinding.editTextBookmarkSummary.text.toString().trim()

                if (title.isNotEmpty()) {
                    val newBookmark = Bookmark(
                        pdfUri = pdfUriString,          // Use the URI passed to the function
                        pageIndex = pageIndex,          // Use the page index passed to the function
                        title = title,                  // Use the title from the dialog input
                        summary = summary,              // Use the summary from the dialog input
                        creationDate = System.currentTimeMillis() // Get current time as timestamp (Long)
                    )

                    saveBookmark(newBookmark)
                } else {
                    Toast.makeText(this, R.string.bookmark_title_empty, Toast.LENGTH_SHORT).show()
                }
                // Keep dismiss logic as preferred
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create() // Needed if using setView
            .show()
    }

    private fun saveBookmark(bookmark: Bookmark) {
        lifecycleScope.launch {
            try {
                bookmarkDao.insertBookmark(bookmark)
                Log.d("MainActivity", "Bookmark saved: ${bookmark.title}")
                Toast.makeText(this@MainActivity, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error saving bookmark", e)
                Toast.makeText(this@MainActivity, R.string.error_saving_bookmark, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- PDFView Listeners ---

    override fun onPageChanged(page: Int, pageCount: Int) {
        viewModel.setCurrentPage(page)
        val currentPageString = "Page ${page + 1} of $pageCount"
        binding.toolbar.subtitle = currentPageString // Keep subtitle update
        Log.d("MainActivity", "Current Page: $page saved to ViewModel.")
    }

    override fun loadComplete(nbPages: Int) {
        Log.d("MainActivity", "PDF Load Complete. Total Pages: $nbPages")
        // Update UI state to show PDF, hide loading, manage buttons
        updateUiState(isLoading = false, pdfLoaded = true, hasBookmarks = bookmarksForCurrentPdf.isNotEmpty())

        val restoredPage = viewModel.currentPage.value
        binding.toolbar.subtitle = "Page ${restoredPage + 1} of $nbPages" // Set subtitle correctly

        Toast.makeText(this, getString(R.string.pdf_load_complete, nbPages), Toast.LENGTH_SHORT).show()

        // Jump logic remains the same
        if (binding.pdfView.currentPage != restoredPage) {
            Log.d("MainActivity", "Jumping to restored page $restoredPage after load complete.")
            binding.pdfView.jumpTo(restoredPage, false)
        }
    }
}