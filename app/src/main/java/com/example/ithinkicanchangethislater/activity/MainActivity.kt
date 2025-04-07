package com.example.ithinkicanchangethislater.activity


// Removed EditText import as it's now in Dialog Binding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ithinkicanchangethislater.R
import com.example.ithinkicanchangethislater.adapter.RecentFileItem
import com.example.ithinkicanchangethislater.adapter.RecentFilesAdapter
import com.example.ithinkicanchangethislater.data.database.AppDatabase
import com.example.ithinkicanchangethislater.data.database.BookmarkDao
import com.example.ithinkicanchangethislater.data.entity.Bookmark
import com.example.ithinkicanchangethislater.databinding.ActivityMainBinding
import com.example.ithinkicanchangethislater.databinding.DialogAddBookmarkBinding
import com.example.ithinkicanchangethislater.databinding.DialogSearchBookmarksBinding
import com.example.ithinkicanchangethislater.util.DateUtils
import com.example.ithinkicanchangethislater.viewmodel.PdfViewModel
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream


// SharedPreferences Constants

const val PREFS_NAME = "PdfReaderPrefs"

const val KEY_RECENT_FILES = "recentFiles"

const val MAX_RECENT_FILES = 20 // Increased limit slightly



class MainActivity : AppCompatActivity(), OnPageChangeListener, OnLoadCompleteListener {


    private lateinit var binding: ActivityMainBinding

    private lateinit var bookmarkDao: BookmarkDao

    private val viewModel: PdfViewModel by viewModels()

    private var bookmarksForCurrentPdf: List<Bookmark> = emptyList()


    private lateinit var sharedPreferences: SharedPreferences // Added

    private lateinit var recentFilesAdapter: RecentFilesAdapter // Added

    private var currentRecentFiles = mutableListOf<RecentFileItem>() // Added


// Enum to manage UI State

    private enum class UiMode { LIBRARY, READER_LOADING, READER_LOADED }


    private val pdfPickerLauncher = registerForActivityResult(

        ActivityResultContracts.StartActivityForResult()

    ) { result ->

        if (result.resultCode == Activity.RESULT_OK) {

            result.data?.data?.let { uri ->

                handleUriPermissions(uri, result.data?.flags)

// Process the selected URI (triggers ViewModel observer)

                viewModel.setPdfUri(uri)

            }

        }

    }


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)


        WindowCompat.setDecorFitsSystemWindows(window, false)

// Note: Immersive mode (hiding system bars) might conflict with showing AppBar consistently.

// Consider removing hide() if AppBar behavior is problematic.

        WindowInsetsControllerCompat(window, window.decorView).let { controller ->

            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

// controller.hide(WindowInsetsCompat.Type.systemBars()) // Maybe remove this line

        }


        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)


        setSupportActionBar(binding.toolbar)

        supportActionBar?.setDisplayShowTitleEnabled(true) // Ensure title is shown


        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets -> // Apply to AppBarLayout

            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.updatePadding(top = systemBarsInsets.top) // Apply top padding for status bar

// Consume only the top inset for the AppBar, let others pass through

            WindowInsetsCompat.Builder(insets).setInsets(

                WindowInsetsCompat.Type.statusBars(),

                Insets.of(systemBarsInsets.left, 0, systemBarsInsets.right, systemBarsInsets.bottom)

            ).build()

        }

// Apply bottom padding for navigation bar to main content areas if needed (e.g., RecyclerView)

        ViewCompat.setOnApplyWindowInsetsListener(binding.libraryViewContainer) { view, insets ->

            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            view.updatePadding(bottom = navBarInsets.bottom)

            insets // Return original insets

        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.readerViewContainer) { view, insets ->

            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            view.updatePadding(bottom = navBarInsets.bottom)

            insets // Return original insets

        }



        bookmarkDao = AppDatabase.getDatabase(applicationContext).bookmarkDao()

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


        setupRecyclerView() // Setup RecyclerView first

        loadRecentFiles() // Load data for RecyclerView


        setupViews()

        observeViewModel()

        setupBackPressedHandler() // Handle back press logic


// Initial UI state: Show Library

        updateUiState(UiMode.LIBRARY)

    }


// --- SharedPreferences Handling ---


    private fun loadRecentFiles() {

        val savedSet = sharedPreferences.getStringSet(KEY_RECENT_FILES, emptySet()) ?: emptySet()

        currentRecentFiles = savedSet.mapNotNull { entry ->

            val parts = entry.split("|", limit = 2)

            if (parts.size == 2) RecentFileItem(parts[0], parts[1]) else null

        }.toMutableList()

// Sort by some logic if desired (e.g., last accessed, not stored here yet)

        recentFilesAdapter.updateData(currentRecentFiles)

        updateLibraryEmptyState()

    }


    private fun addRecentFile(uri: Uri, filename: String?) {

        val uriString = uri.toString()

        val name = filename ?: "Unknown File" // Use a default if name is null


// Remove if already exists to move it to the top (most recent)

        currentRecentFiles.removeAll { it.uriString == uriString }


// Add to the beginning of the list

        currentRecentFiles.add(0, RecentFileItem(uriString, name))


// Limit the list size

        while (currentRecentFiles.size > MAX_RECENT_FILES) {

            currentRecentFiles.removeLast()

        }


// Save updated list to SharedPreferences

        val newSet = currentRecentFiles.map { "${it.uriString}|${it.filename}" }.toSet()

        sharedPreferences.edit().putStringSet(KEY_RECENT_FILES, newSet).apply()


// Update adapter and empty state view

        recentFilesAdapter.updateData(currentRecentFiles)

        updateLibraryEmptyState()

    }


    private fun updateLibraryEmptyState() {

        binding.libraryViewContainer.findViewById<TextView>(R.id.textViewEmptyLibrary).isVisible =
            currentRecentFiles.isEmpty()

        binding.libraryViewContainer.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewRecentFiles).isVisible =
            currentRecentFiles.isNotEmpty()

    }


// --- RecyclerView Setup ---


    private fun setupRecyclerView() {

        recentFilesAdapter = RecentFilesAdapter(currentRecentFiles) { recentItem ->

// Handle click on a recent file item

            Log.d("MainActivity", "Recent item clicked: ${recentItem.filename}")

// Convert Uri string back to Uri

            val uri = recentItem.uriString.toUri()

// Check if we still have permission (important!)

            if (hasUriPermission(uri)) {

                viewModel.setPdfUri(uri) // This will trigger the observer to load the PDF

            } else {

                Log.w("MainActivity", "Permission lost for URI: ${recentItem.uriString}")

                Toast.makeText(this, R.string.permission_lost_reopen, Toast.LENGTH_LONG).show()

// Optional: Remove the item from recents if permission is lost permanently

                removeRecentFile(recentItem.uriString)

            }

        }

        binding.libraryViewContainer.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewRecentFiles)
            .apply {

                layoutManager = LinearLayoutManager(this@MainActivity)

                adapter = recentFilesAdapter

            }

    }


// --- Permission Handling ---


    private fun handleUriPermissions(uri: Uri, flags: Int?) {

        val takeFlags = flags?.and(

            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        ) ?: Intent.FLAG_GRANT_READ_URI_PERMISSION // Default to read if flags are null


        try {

            contentResolver.takePersistableUriPermission(uri, takeFlags)

            Log.d("MainActivity", "Successfully took persistable permission for $uri")

// Now add to recents only after confirming permission

            val filename = getFileName(uri)

            addRecentFile(uri, filename)


        } catch (e: SecurityException) {

            Log.e("MainActivity", "Failed to take persistable permission for $uri", e)

            Toast.makeText(this, R.string.could_not_get_permissions, Toast.LENGTH_SHORT).show()

// Clear URI if permission fails

            if (viewModel.pdfUri.value == uri) { // Check if this was the URI being set

                viewModel.setPdfUri(null)

            }

        }

    }


// Check if we still hold permission for a URI

    private fun hasUriPermission(uri: Uri): Boolean {

        val persistedUris = contentResolver.persistedUriPermissions

        return persistedUris.any { it.uri == uri && it.isReadPermission }

    }


// Optional: Remove a file from recents if permission is lost

    private fun removeRecentFile(uriString: String) {

        currentRecentFiles.removeAll { it.uriString == uriString }

        val newSet = currentRecentFiles.map { "${it.uriString}|${it.filename}" }.toSet()

        sharedPreferences.edit().putStringSet(KEY_RECENT_FILES, newSet).apply()

        recentFilesAdapter.updateData(currentRecentFiles)

        updateLibraryEmptyState()

    }


// --- Back Press Handling ---

    private fun setupBackPressedHandler() {

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {

            override fun handleOnBackPressed() {

// If PDF reader is visible, go back to library view

                if (binding.readerViewContainer.isVisible) {

                    viewModel.setPdfUri(null) // This triggers UI update back to library

// Reset PDFView state if needed

                    binding.pdfView.recycle()

                } else {

// If already in library view, perform default back action (exit app)

                    isEnabled = false // Disable this callback

                    onBackPressedDispatcher.onBackPressed() // Call default handler

                    isEnabled = true // Re-enable for next time

                }

            }

        })

    }


// --- Options Menu ---

    override fun onCreateOptionsMenu(menu: Menu): Boolean {

        menuInflater.inflate(R.menu.main_menu, menu)

// Conditionally show/hide items based on state if needed

        return true

    }


    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {

// Hide "Open PDF" when reader is visible? Or keep always visible? Your choice.

// Example: menu?.findItem(R.id.action_open_pdf)?.isVisible = !binding.readerViewContainer.isVisible

        return super.onPrepareOptionsMenu(menu)

    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        return when (item.itemId) {

            R.id.action_open_pdf -> {

                openPdfChooser()

                true

            }

// Handle other menu items if you add them

            else -> super.onOptionsItemSelected(item)

        }

    }


// --- View Setup and Observation ---

    private fun setupViews() {

// FAB listener remains the same

        binding.readerViewContainer.findViewById<View>(R.id.fabAddBookmark).setOnClickListener {

            viewModel.pdfUri.value?.let {

                showAddBookmarkDialog(it.toString(), viewModel.currentPage.value)

            } ?: Toast.makeText(this, R.string.load_pdf_first, Toast.LENGTH_SHORT).show()

        }


// Bookmarks button listener remains the same (but button is in Toolbar now)

        binding.toolbar.findViewById<View>(R.id.buttonShowBookmarks).setOnClickListener {

            if (viewModel.pdfUri.value == null) {

                Toast.makeText(this, R.string.open_pdf_to_see_bookmarks, Toast.LENGTH_SHORT).show()

                return@setOnClickListener

            }

            if (bookmarksForCurrentPdf.isEmpty()) {

                Toast.makeText(this, R.string.no_bookmarks_for_this_pdf, Toast.LENGTH_SHORT).show()

            } else {

                showBookmarksListDialog()

            }

        }

    }


    private fun observeViewModel() {

        viewModel.pdfUri

            .onEach { uri ->

                if (uri != null) {

// A PDF URI is set (either from picker or recent list)

                    if (hasUriPermission(uri)) { // Double-check permission before loading

                        updateUiState(UiMode.READER_LOADING) // Show loading state

                        loadBookmarksForPdf(uri.toString()) // Start loading bookmarks

                        loadPdf(uri) // Start loading PDF

                    } else {

                        Log.w("MainActivity", "Permission lost before loading URI: $uri")

                        Toast.makeText(this, R.string.permission_lost_reopen, Toast.LENGTH_LONG)
                            .show()

                        removeRecentFile(uri.toString())

                        viewModel.setPdfUri(null) // Reset viewModel state

                        updateUiState(UiMode.LIBRARY) // Go back to library

                    }


                } else {

// PDF URI is cleared (e.g., by back press from reader)

                    updateUiState(UiMode.LIBRARY)

                    binding.pdfView.recycle() // Clear PDF view resources

                    bookmarksForCurrentPdf = emptyList() // Clear bookmarks

                }

            }

            .launchIn(lifecycleScope)

    }


// --- PDF Loading and Callbacks ---


    private fun openPdfChooser() {

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {

            addCategory(Intent.CATEGORY_OPENABLE)

            type = "application/pdf"

            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) // Request persistable permission

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        }

        pdfPickerLauncher.launch(intent)

    }


    private fun loadPdf(pdfUri: Uri) {
        Log.d("MainActivity", "Loading PDF: $pdfUri")
        setToolbarTitle(getFileName(pdfUri)) // Make sure this handles exceptions

        var inputStream: InputStream? = null // Declare outside try
        try {
            Log.d("MainActivity", "Attempting to open InputStream...")
            inputStream = contentResolver.openInputStream(pdfUri) // Assign here

            if (inputStream == null) {
                Log.e("MainActivity", "ContentResolver returned null InputStream for URI: $pdfUri")
                onPdfError(RuntimeException("Failed to get InputStream from ContentResolver for $pdfUri"))
                return // Stop loading process
            }
            Log.d(
                "MainActivity",
                "Successfully opened InputStream. Will attempt loading via fromStream()."
            )

            Log.d("MainActivity", "Recycling old PDFView state...")
            binding.pdfView.recycle()

            Log.d("MainActivity", "Calling pdfView.fromStream()...")
            binding.pdfView.fromStream(inputStream) // *** USE fromStream ***
                .defaultPage(viewModel.currentPage.value)
                .onPageChange(this)
                .onLoad { pages -> // Use a different param name
                    Log.d("MainActivity", "*** fromStream -> onLoad CALLED! Pages: $pages ***")
                    // Important: The library might keep the stream open. Don't close it here.
                    loadComplete(pages) // Call your original loadComplete
                }
                .onError { throwable -> // Use a different param name
                    Log.e("MainActivity", "*** fromStream -> onError CALLED! ***", throwable)
                    // Attempt to close the stream on error
                    try {
                        inputStream.close()
                    } catch (e: IOException) {
                        Log.w("MainActivity", "Error closing stream in onError", e)
                    }
                    onPdfError(throwable) // Call your original onError
                }
                // Optional: Add onFinally if your library version supports it for cleanup
                // .onFinally {
                //     Log.d("MainActivity", "fromStream() finished.")
                //     // This is a safer place to close if needed and not handled by library
                //     try { inputStream.close() } catch (e: IOException) { Log.w("MainActivity", "Error closing stream in onFinally", e) }
                // }
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableAntialiasing(true)
                .spacing(10)
                .load() // Start loading from the stream

        } catch (e: Exception) {
            // Catch errors during opening stream or configuring fromStream
            Log.e("MainActivity", "Failed to open or load from InputStream for URI: $pdfUri", e)
            // Ensure stream is closed if it was opened before the error
            try {
                inputStream?.close()
            } catch (ioe: IOException) { /* Ignore */
            }
            onPdfError(e)
        }
        // Note: Be cautious about closing the inputStream. The PDF library often needs to
        // read from it on demand as you scroll. Closing it too early (e.g., in onLoad)
        // will break rendering later. Usually, you only close it safely in onError
        // or if the view is destroyed/recycled. Check library docs if possible.
    }


    private fun onPdfError(t: Throwable?) {

        Log.e("MainActivity", "Error loading PDF", t)

        Toast.makeText(this, getString(R.string.error_loading_pdf) + ": ${t?.message}", Toast.LENGTH_LONG).show()

        viewModel.setPdfUri(null) // Clear URI on error

        updateUiState(UiMode.LIBRARY) // Go back to library on error

    }


    override fun loadComplete(nbPages: Int) {

        Log.d(
            "MainActivity",
            "PDF Load Complete. Pages: $nbPages. Calling updateUiState."
        ) // Add this

        Log.d(
            "MainActivity",
            "updateUiState(READER_LOADED) called. pdfView visibility: ${binding.pdfView.isVisible}"
        ) // Add this

        Log.d("MainActivity", "PDF Load Complete. Pages: $nbPages")

// PDF is loaded, update state

        updateUiState(UiMode.READER_LOADED)


        val restoredPage = viewModel.currentPage.value

        binding.toolbar.subtitle = "Page ${restoredPage + 1} of $nbPages"

        if (binding.pdfView.currentPage != restoredPage) {

            binding.pdfView.jumpTo(restoredPage, false)

        }

    }


    override fun onPageChanged(page: Int, pageCount: Int) {

        viewModel.setCurrentPage(page)

        binding.toolbar.subtitle = "Page ${page + 1} of $pageCount"

    }


// --- Bookmark Handling (Mostly Unchanged, ensure context/bindings are correct) ---


    private fun loadBookmarksForPdf(pdfUriString: String) {

        lifecycleScope.launch {

            bookmarkDao.getBookmarksForPdf(pdfUriString).collectLatest { bookmarks ->

                bookmarksForCurrentPdf = bookmarks

// Update bookmark button state only when reader is potentially visible

                if (binding.readerViewContainer.isVisible) {

                    binding.toolbar.findViewById<View>(R.id.buttonShowBookmarks).apply {

                        isEnabled = bookmarks.isNotEmpty()

                        alpha = if (bookmarks.isNotEmpty()) 1.0f else 0.5f

                    }

                }

            }

        }

    }


// (showBookmarksListDialog, showBookmarkSummaryDialog, showAddBookmarkDialog, saveBookmark remain the same -

// just ensure they use correct bindings if needed, e.g. LayoutInflater.from(this))


// --- UI State Management ---


    private fun updateUiState(mode: UiMode) {


        Log.d("MainActivity", "Updating UI State to: $mode")

        binding.libraryViewContainer.isVisible = mode == UiMode.LIBRARY

        binding.readerViewContainer.isVisible =
            mode == UiMode.READER_LOADING || mode == UiMode.READER_LOADED

        binding.readerViewContainer.findViewById<View>(R.id.progressBar).isVisible =
            mode == UiMode.READER_LOADING

        val shouldBeVisible = mode == UiMode.READER_LOADED

        binding.pdfView.isVisible = mode == UiMode.READER_LOADED

        binding.readerViewContainer.findViewById<View>(R.id.fabAddBookmark).isVisible =
            mode == UiMode.READER_LOADED


        Log.d("MainActivity", "Setting pdfView visibility to: $shouldBeVisible") // Add this log

// Update Toolbar Title and Bookmark button visibility

        if (mode == UiMode.LIBRARY) {

            setToolbarTitle(null) // Show app name

            binding.toolbar.subtitle = null

            binding.toolbar.findViewById<View>(R.id.buttonShowBookmarks).visibility = View.GONE

        } else {

// Title is set during loadPdf/loadComplete

            binding.toolbar.findViewById<View>(R.id.buttonShowBookmarks).visibility = View.VISIBLE

// Enable/disable bookmark button based on current PDF's bookmarks

            val hasBookmarks = bookmarksForCurrentPdf.isNotEmpty()

            binding.toolbar.findViewById<View>(R.id.buttonShowBookmarks).apply {

// Only enable if reader is fully loaded and has bookmarks

                isEnabled = mode == UiMode.READER_LOADED && hasBookmarks

                alpha = if (isEnabled) 1.0f else 0.5f

            }

        }

        invalidateOptionsMenu() // Refresh menu items based on state if needed (using onPrepareOptionsMenu)

        updateLibraryEmptyState() // Ensure library empty text is correct if switching back

    }


    private fun setToolbarTitle(pdfName: String?) {

        supportActionBar?.title = pdfName ?: getString(R.string.app_name)

// Subtitle is handled in page change/load complete

    }


// --- Utility ---

    private fun getFileName(uri: Uri): String? {

        var fileName: String? = null

        try { // Add try-catch for potential SecurityException if permission revoked between check and query

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->

                if (cursor.moveToFirst()) {

                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                    if (index != -1) {

                        fileName = cursor.getString(index)

                    }

                }

            }

        } catch (e: SecurityException) {

            Log.e("MainActivity", "getFileName failed for $uri", e)

// Attempt to remove this problematic URI from recents

            removeRecentFile(uri.toString())

        }

// Fallback if display name is not available

        return fileName ?: uri.path?.substringAfterLast('/')

    }


// (Make sure showBookmarksListDialog, showBookmarkSummaryDialog, showAddBookmarkDialog, saveBookmark are here and correct)

// ... (Copy the existing bookmark dialog functions here) ...

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

                }

// Dialog dismisses automatically

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

// Bookmarks list will auto-update due to collectLatest in loadBookmarksForPdf

            } catch (e: Exception) {

                Log.e("MainActivity", "Error saving bookmark", e)

                Toast.makeText(this@MainActivity, R.string.error_saving_bookmark, Toast.LENGTH_SHORT).show()

            }

        }

    }


// Add necessary string resources:

// R.string.permission_lost_reopen = "Permission for this file was lost. Please reopen it."

// R.string.open_pdf_to_see_bookmarks = "Open a PDF to view its bookmarks"

// R.string.no_bookmarks_for_this_pdf = "No bookmarks saved for this PDF"

// R.string.pdf_file_icon = "PDF file icon" (content description for the image view)

}