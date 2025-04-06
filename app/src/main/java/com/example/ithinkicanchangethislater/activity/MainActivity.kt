package com.example.ithinkicanchangethislater.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView // Added import
import android.widget.TextView // Added import
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.*
import androidx.lifecycle.lifecycleScope
import com.example.ithinkicanchangethislater.R
import com.example.ithinkicanchangethislater.data.database.AppDatabase
import com.example.ithinkicanchangethislater.data.database.BookmarkDao
import com.example.ithinkicanchangethislater.data.entity.Bookmark
import com.example.ithinkicanchangethislater.databinding.ActivityMainBinding
import com.example.ithinkicanchangethislater.databinding.DialogAddBookmarkBinding
import com.example.ithinkicanchangethislater.databinding.DialogSearchBookmarksBinding // Added import
import com.example.ithinkicanchangethislater.util.DateUtils
import com.example.ithinkicanchangethislater.viewmodel.PdfViewModel
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), OnPageChangeListener, OnLoadCompleteListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bookmarkDao: BookmarkDao
    private val viewModel: PdfViewModel by viewModels()
    private var bookmarksForCurrentPdf: List<Bookmark> = emptyList()

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.setPdfUri(uri)
                val takeFlags = result.data?.flags?.and(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                ) ?: 0
                try {
                    // Persist permission to access the URI across device restarts
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: SecurityException) {
                    Log.e("MainActivity", "Failed to take persistable permission", e)
                    Toast.makeText(this, R.string.could_not_get_permissions, Toast.LENGTH_SHORT).show()
                    viewModel.setPdfUri(null) // Clear URI if permission fails
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // Hide status and navigation bars for an immersive experience
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup the Toolbar
        setSupportActionBar(binding.toolbar)

        // Apply padding to the Toolbar to avoid overlapping with the status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = statusBarHeight)
            insets // Return the insets unmodified for other children
        }

        // Initialize database access object
        bookmarkDao = AppDatabase.getDatabase(applicationContext).bookmarkDao()

        setupViews()
        observeViewModel()
        // Initial UI state: No PDF loaded yet
        updateUiState(isLoading = false, pdfLoaded = false, hasBookmarks = false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here.
        return when (item.itemId) {
            R.id.action_open_pdf -> {
                openPdfChooser() // Launch the PDF file picker
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Setup listeners for UI elements like buttons
    private fun setupViews() {
        binding.fabAddBookmark.setOnClickListener {
            viewModel.pdfUri.value?.let { uri ->
                // Show dialog to add a bookmark for the current page
                showAddBookmarkDialog(uri.toString(), viewModel.currentPage.value)
            } ?: Toast.makeText(this, R.string.load_pdf_first, Toast.LENGTH_SHORT).show()
        }

        binding.buttonShowBookmarks.setOnClickListener {
            if (bookmarksForCurrentPdf.isEmpty()) {
                Toast.makeText(this, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
            } else {
                // Show the searchable list of bookmarks
                showBookmarksListDialog()
            }
        }
    }

    // Observe changes in the ViewModel (PDF URI and current page)
    private fun observeViewModel() {
        viewModel.pdfUri
            .onEach { uri ->
                if (uri != null) {
                    updateUiState(isLoading = true, pdfLoaded = false) // Show loading indicator
                    setToolbarTitle(uri) // Update toolbar title with filename
                    loadPdf(uri)         // Load the PDF document
                } else {
                    // No PDF loaded or cleared
                    updateUiState(isLoading = false, pdfLoaded = false)
                    setToolbarTitle(null) // Reset toolbar title
                    binding.pdfView.recycle() // Clear the PDFView
                    bookmarksForCurrentPdf = emptyList() // Clear bookmarks
                }
            }
            .launchIn(lifecycleScope) // Collect the flow within the activity's lifecycle

        // You might want to observe currentPage changes too if needed elsewhere
        // viewModel.currentPage.onEach { page -> ... }.launchIn(lifecycleScope)
    }

    // Starts an intent to let the user pick a PDF file
    private fun openPdfChooser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE) // Ensure the file is openable
            type = "application/pdf" // Filter for PDF files
            // Optionally add FLAG_GRANT_PERSISTABLE_URI_PERMISSION if needed,
            // handled by takePersistableUriPermission after result
        }
        pdfPickerLauncher.launch(intent)
    }

    // Loads the selected PDF into the PDFView
    private fun loadPdf(pdfUri: Uri) {
        // Load bookmarks associated with this specific PDF URI
        loadBookmarksForPdf(pdfUri.toString())

        binding.pdfView.recycle() // Ensure previous PDF resources are cleared
        binding.pdfView.fromUri(pdfUri)
            .defaultPage(viewModel.currentPage.value) // Start at the last viewed page (or 0)
            .onPageChange(this) // Set listener for page changes
            .onLoad(this)       // Set listener for load completion
            .onError(this::onPdfError) // Set listener for load errors
            .enableSwipe(true)       // Allow swiping between pages
            .swipeHorizontal(false)  // Use vertical scrolling
            .enableAntialiasing(true)// Improve rendering quality
            .spacing(10)             // Add spacing between pages
            .load()                  // Start loading the PDF
    }

    // Handles errors during PDF loading
    private fun onPdfError(t: Throwable?) {
        Log.e("MainActivity", "Error loading PDF", t)
        Toast.makeText(this, getString(R.string.error_loading_pdf) + ": ${t?.message}", Toast.LENGTH_LONG).show()
        updateUiState(isLoading = false, pdfLoaded = false) // Reset UI state
        viewModel.setPdfUri(null) // Clear the problematic URI
        setToolbarTitle(null)     // Reset toolbar title
    }

    // Fetches bookmarks for the given PDF URI from the database
    private fun loadBookmarksForPdf(pdfUriString: String) {
        lifecycleScope.launch {
            bookmarkDao.getBookmarksForPdf(pdfUriString).collectLatest { bookmarks ->
                bookmarksForCurrentPdf = bookmarks
                // Update UI state only if a PDF is currently loaded
                if (viewModel.pdfUri.value != null) {
                    updateUiState(isLoading = false, pdfLoaded = true, hasBookmarks = bookmarks.isNotEmpty())
                }
            }
        }
    }

    // Updates the visibility and enabled state of UI elements based on current state
    private fun updateUiState(isLoading: Boolean, pdfLoaded: Boolean, hasBookmarks: Boolean? = null) {
        binding.progressBar.isVisible = isLoading
        binding.textViewOpenFilePrompt.isVisible = !isLoading && !pdfLoaded
        binding.pdfView.isVisible = !isLoading && pdfLoaded
        binding.fabAddBookmark.isVisible = !isLoading && pdfLoaded

        // Determine if the bookmarks button should be enabled
        val bookmarksButtonEnabled = !isLoading && pdfLoaded && (hasBookmarks ?: bookmarksForCurrentPdf.isNotEmpty())
        binding.buttonShowBookmarks.isEnabled = bookmarksButtonEnabled
        // Visually indicate if the button is disabled
        binding.buttonShowBookmarks.alpha = if (bookmarksButtonEnabled) 1.0f else 0.5f
    }

    // Sets the title of the Toolbar, using the PDF filename or the app name
    private fun setToolbarTitle(uri: Uri?) {
        binding.toolbar.title = uri?.let { getFileName(it) } ?: getString(R.string.app_name)
        // Clear subtitle when PDF changes or is cleared
        binding.toolbar.subtitle = null
    }

    // Utility function to get the display name of a file from its URI
    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        // Query the ContentResolver to get the file's display name
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    fileName = cursor.getString(index)
                }
            }
        }
        // Fallback if display name is not available (should be rare with ACTION_OPEN_DOCUMENT)
        return fileName ?: uri.path?.substringAfterLast('/')
    }

    // --- UPDATED: Shows a dialog with a search bar to filter bookmarks ---
    private fun showBookmarksListDialog() {
        // Inflate the custom layout using ViewBinding
        val dialogBinding = DialogSearchBookmarksBinding.inflate(LayoutInflater.from(this))
        val searchEditText = dialogBinding.editTextSearchBookmarks
        val bookmarksListView = dialogBinding.listViewBookmarks
        val noResultsTextView = dialogBinding.textViewNoResults

        // Keep a mutable list for filtering
        var filteredBookmarks = bookmarksForCurrentPdf.toMutableList()

        // Create the adapter to display bookmarks (Title + Date)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1, // Use a simple layout for list items
            filteredBookmarks.map { "${it.title} (${DateUtils.formatDate(it.creationDate)})" } // Map to display string
        )
        bookmarksListView.adapter = adapter

        // Function to update the list and "no results" text visibility
        fun updateListVisibility() {
            if (filteredBookmarks.isEmpty()) {
                bookmarksListView.visibility = View.GONE
                noResultsTextView.visibility = View.VISIBLE
            } else {
                bookmarksListView.visibility = View.VISIBLE
                noResultsTextView.visibility = View.GONE
            }
        }

        // Initial visibility check
        updateListVisibility()

        // --- Filtering Logic ---
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim().lowercase() // Get search query

                // Filter the original list based on title or summary
                filteredBookmarks = if (query.isEmpty()) {
                    bookmarksForCurrentPdf.toMutableList() // Show all if query is empty
                } else {
                    bookmarksForCurrentPdf.filter {
                        it.title.lowercase().contains(query) || it.summary.lowercase().contains(query)
                    }.toMutableList()
                }

                // Update the adapter's data
                adapter.clear()
                adapter.addAll(filteredBookmarks.map { "${it.title} (${DateUtils.formatDate(it.creationDate)})" })
                adapter.notifyDataSetChanged() // Refresh the ListView

                // Update visibility based on filter results
                updateListVisibility()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // --- Dialog Creation ---
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks)
            .setView(dialogBinding.root) // Set the custom view
            .setNegativeButton(R.string.cancel, null)
            .create() // Create the dialog instance

        // --- Handle Item Clicks ---
        bookmarksListView.setOnItemClickListener { _, _, position, _ ->
            // Get the clicked bookmark from the *filtered* list
            val selectedBookmark = filteredBookmarks[position]
            showBookmarkSummaryDialog(selectedBookmark) // Show the summary dialog for the selected bookmark
            dialog.dismiss() // Close the search dialog
        }

        dialog.show() // Display the dialog
    }


    // Shows a dialog displaying the details of a specific bookmark
    private fun showBookmarkSummaryDialog(bookmark: Bookmark) {
        val summary = if (bookmark.summary.isBlank()) getString(R.string.no_summary) else bookmark.summary
        val message = "Date: ${DateUtils.formatDate(bookmark.creationDate)}\n\n$summary"

        MaterialAlertDialogBuilder(this)
            .setTitle(bookmark.title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.go_to_page, bookmark.pageIndex + 1)) { dialog, _ ->
                // Jump to the bookmarked page in the PDFView
                binding.pdfView.jumpTo(bookmark.pageIndex, true) // Animate the jump
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // Shows a dialog to add a new bookmark for the current page
    private fun showAddBookmarkDialog(pdfUriString: String, pageIndex: Int) {
        // Inflate the dialog layout using ViewBinding
        val dialogBinding = DialogAddBookmarkBinding.inflate(LayoutInflater.from(this))

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_bookmark_dialog_title, pageIndex + 1)) // Show current page number
            .setView(dialogBinding.root) // Set the custom layout
            .setPositiveButton(R.string.save) { dialog, _ ->
                val title = dialogBinding.editTextBookmarkTitle.text.toString().trim()
                val summary = dialogBinding.editTextBookmarkSummary.text.toString().trim()

                if (title.isNotEmpty()) {
                    // Create the bookmark object
                    val bookmark = Bookmark(
                        pdfUri = pdfUriString,
                        pageIndex = pageIndex,
                        title = title,
                        summary = summary,
                        creationDate = System.currentTimeMillis() // Record creation time
                    )
                    saveBookmark(bookmark) // Save to database
                } else {
                    // Show error if title is empty
                    Toast.makeText(this, R.string.bookmark_title_empty, Toast.LENGTH_SHORT).show()
                    // Note: Consider preventing dialog dismissal here or re-showing if validation fails
                }
                // Dialog dismisses automatically unless handled otherwise
            }
            .setNegativeButton(R.string.cancel, null) // Just dismisses the dialog
            .show()
    }

    // Saves a bookmark object to the database using a coroutine
    private fun saveBookmark(bookmark: Bookmark) {
        lifecycleScope.launch { // Launch coroutine in the activity's scope
            try {
                bookmarkDao.insertBookmark(bookmark)
                Toast.makeText(this@MainActivity, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
                // No need to manually reload bookmarks here, loadBookmarksForPdf's collectLatest will trigger
            } catch (e: Exception) {
                Log.e("MainActivity", "Error saving bookmark", e)
                Toast.makeText(this@MainActivity, R.string.error_saving_bookmark, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- PDFView Listener Callbacks ---

    // Called when the user swipes to a new page
    override fun onPageChanged(page: Int, pageCount: Int) {
        viewModel.setCurrentPage(page) // Update the ViewModel with the new current page
        // Update the Toolbar subtitle to show current page info
        binding.toolbar.subtitle = "Page ${page + 1} of $pageCount"
    }

    // Called when the PDF document has finished loading
    override fun loadComplete(nbPages: Int) {
        // Update UI state now that PDF is loaded
        // Pass the current state of bookmarks (already loaded or loading)
        updateUiState(isLoading = false, pdfLoaded = true, hasBookmarks = bookmarksForCurrentPdf.isNotEmpty())

        // Ensure the correct page is displayed and subtitle is updated
        val restoredPage = viewModel.currentPage.value
        binding.toolbar.subtitle = "Page ${restoredPage + 1} of $nbPages"

        // If the PDFView somehow loaded on a different page, jump to the correct one
        // This might happen if defaultPage() is called before loadComplete() finishes setting the page
        if (binding.pdfView.currentPage != restoredPage) {
            // Jump without animation as it's part of the initial load sequence
            binding.pdfView.jumpTo(restoredPage, false)
        }
    }
}