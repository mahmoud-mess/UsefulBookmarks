package com.example.ithinkicanchangethislater.activity

// import com.example.ithinkicanchangethislater.databinding.ItemRecentFileBinding // Not directly used here
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
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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


// SharedPreferences Constants
const val PREFS_NAME = "PdfReaderPrefs"
const val KEY_RECENT_FILES = "recentFiles"
const val MAX_RECENT_FILES = 20 // Limit for recent files


class MainActivity : AppCompatActivity(), OnPageChangeListener, OnLoadCompleteListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bookmarkDao: BookmarkDao
    private val viewModel: PdfViewModel by viewModels()
    private var bookmarksForCurrentPdf: List<Bookmark> = emptyList()

    // --- Library / Recent Files ---
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var recentFilesAdapter: RecentFilesAdapter
    private var currentRecentFiles = mutableListOf<RecentFileItem>()

    // Enum to manage UI State more clearly
    private enum class UiMode { LIBRARY, READER_LOADING, READER_LOADED }

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Handle permissions FIRST, then add to recents, then set ViewModel
                handleUriPermissions(uri, result.data?.flags)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        // Apply window insets handling
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { view, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = systemBarsInsets.top)
            WindowInsetsCompat.Builder(insets).setInsets(
                WindowInsetsCompat.Type.statusBars(),
                Insets.of(systemBarsInsets.left, 0, systemBarsInsets.right, systemBarsInsets.bottom)
            ).build()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.libraryViewContainer) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.readerViewContainer) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBarInsets.bottom)
            insets
        }


        bookmarkDao = AppDatabase.getDatabase(applicationContext).bookmarkDao()
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupRecyclerView() // Setup RecyclerView for recent files
        loadRecentFiles()   // Load data for RecyclerView

        setupViews()        // Setup button listeners etc.
        observeViewModel()  // Observe ViewModel changes
        setupBackPressedHandler() // Handle back press logic

        // Initial UI state: Show Library
        updateUiState(UiMode.LIBRARY)
    }

    // --- SharedPreferences & Recent Files Handling ---

    private fun loadRecentFiles() {
        val savedSet = sharedPreferences.getStringSet(KEY_RECENT_FILES, emptySet()) ?: emptySet()
        currentRecentFiles = savedSet.mapNotNull { entry ->
            val parts = entry.split("|", limit = 3)
            if (parts.size >= 2) {
                // Try to parse timestamp, default to 0 if missing/invalid
                val timestamp = if (parts.size == 3) parts[2].toLongOrNull() ?: 0L else 0L
                RecentFileItem(parts[0], parts[1], timestamp)
            } else null
        }.sortedByDescending { it.lastAccessed }.toMutableList() // Sort by timestamp

        Log.d("MainActivity", "Loaded ${currentRecentFiles.size} recent files.")
        recentFilesAdapter.updateData(currentRecentFiles) // Update adapter with loaded data
        updateLibraryEmptyState()
    }

    private fun addRecentFile(uri: Uri, filename: String?) {
        val uriString = uri.toString()
        val name = filename ?: "Unknown File"
        val timestamp = System.currentTimeMillis()

        currentRecentFiles.removeAll { it.uriString == uriString }
        currentRecentFiles.add(0, RecentFileItem(uriString, name, timestamp))
        while (currentRecentFiles.size > MAX_RECENT_FILES) {
            currentRecentFiles.removeLast()
        }

        // Store as "URI|Filename|Timestamp"
        val newSet =
            currentRecentFiles.map { "${it.uriString}|${it.filename}|${it.lastAccessed}" }.toSet()
        sharedPreferences.edit().putStringSet(KEY_RECENT_FILES, newSet).apply()
        Log.d("MainActivity", "Added recent file: $name. Total: ${currentRecentFiles.size}")

        recentFilesAdapter.updateData(currentRecentFiles)
        updateLibraryEmptyState()
    }

    private fun updateLibraryEmptyState() {
        binding.libraryViewContainer.findViewById<TextView>(R.id.textViewEmptyLibrary)?.isVisible =
            currentRecentFiles.isEmpty()
        binding.libraryViewContainer.findViewById<RecyclerView>(R.id.recyclerViewRecentFiles)?.isVisible =
            currentRecentFiles.isNotEmpty()
    }

    private fun removeRecentFile(uriString: String) {
        val removed = currentRecentFiles.removeAll { it.uriString == uriString }
        if (removed) {
            val newSet =
                currentRecentFiles.map { "${it.uriString}|${it.filename}|${it.lastAccessed}" }
                    .toSet()
            sharedPreferences.edit().putStringSet(KEY_RECENT_FILES, newSet).apply()
            recentFilesAdapter.updateData(currentRecentFiles)
            updateLibraryEmptyState()
            Log.d("MainActivity", "Removed recent file: $uriString")
        }
    }


    // --- RecyclerView Setup ---

    private fun setupRecyclerView() {
        recentFilesAdapter = RecentFilesAdapter(currentRecentFiles) { recentItem ->
            Log.d("MainActivity", "Recent item clicked: ${recentItem.filename}")
            val uri = recentItem.uriString.toUri()
            if (hasUriPermission(uri)) {
                Log.d("MainActivity", "Permission OK for recent item. Setting ViewModel.")
                // Update timestamp on click before loading
                addRecentFile(uri, recentItem.filename)
                viewModel.setPdfUri(uri)
            } else {
                Log.w("MainActivity", "Permission lost for recent URI: ${recentItem.uriString}")
                Toast.makeText(this, R.string.permission_lost_reopen, Toast.LENGTH_LONG).show()
                removeRecentFile(recentItem.uriString)
            }
        }
        binding.libraryViewContainer.findViewById<RecyclerView>(R.id.recyclerViewRecentFiles)
            ?.apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                adapter = recentFilesAdapter
            } ?: Log.e("MainActivity", "RecyclerView R.id.recyclerViewRecentFiles not found!")
    }

    // --- Permission Handling ---

    private fun handleUriPermissions(uri: Uri, flags: Int?) {
        val takeFlags = flags?.and(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ?: Intent.FLAG_GRANT_READ_URI_PERMISSION

        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            Log.d("MainActivity", "Successfully took persistable read permission for $uri")

            val filename = getFileName(uri)
            addRecentFile(uri, filename) // Add/Update in recents list
            viewModel.setPdfUri(uri) // Trigger loading

        } catch (e: SecurityException) {
            Log.e("MainActivity", "Failed to take persistable permission for $uri", e)
            Toast.makeText(this, R.string.could_not_get_permissions, Toast.LENGTH_SHORT).show()
            viewModel.setPdfUri(null)
            removeRecentFile(uri.toString())
        } catch (e: Exception) {
            Log.e("MainActivity", "Error handling URI permissions for $uri", e)
            Toast.makeText(this, "Error processing file URI.", Toast.LENGTH_SHORT).show()
            viewModel.setPdfUri(null)
        }
    }

    private fun hasUriPermission(uri: Uri): Boolean {
        val persistedUris = contentResolver.persistedUriPermissions
        val hasPermission = persistedUris.any { it.uri == uri && it.isReadPermission }
        Log.d("MainActivity", "Checking permission for $uri: $hasPermission")
        return hasPermission
    }


    // --- Back Press Handling ---
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.readerViewContainer.isVisible) {
                    Log.d("MainActivity", "Back pressed in reader view, returning to library.")
                    viewModel.setPdfUri(null)
                } else {
                    Log.d("MainActivity", "Back pressed in library view, finishing activity.")
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // --- Options Menu ---
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        // menu?.findItem(R.id.action_open_pdf)?.isVisible = binding.libraryViewContainer.isVisible
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_pdf -> {
                openPdfChooser()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // --- View Setup and Observation ---
    private fun setupViews() {
        binding.readerViewContainer.findViewById<View>(R.id.fabAddBookmark)?.setOnClickListener {
            viewModel.pdfUri.value?.let {
                showAddBookmarkDialog(it.toString(), viewModel.currentPage.value)
            } ?: Toast.makeText(this, R.string.load_pdf_first, Toast.LENGTH_SHORT).show()
        } ?: Log.e("MainActivity", "FAB R.id.fabAddBookmark not found!")

        binding.toolbar.findViewById<View>(R.id.buttonShowBookmarks)?.setOnClickListener {
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
                    if (hasUriPermission(uri)) {
                        Log.d(
                            "MainActivity",
                            "ViewModel observed URI change (non-null), permission OK."
                        )
                        updateUiState(UiMode.READER_LOADING)
                        setToolbarTitle(uri)
                        loadBookmarksForPdf(uri.toString())
                        loadPdf(uri)
                    } else {
                        Log.w(
                            "MainActivity",
                            "ViewModel observed URI change, but permission check FAILED for $uri"
                        )
                        Toast.makeText(this, R.string.permission_lost_reopen, Toast.LENGTH_LONG)
                            .show()
                        removeRecentFile(uri.toString())
                        viewModel.setPdfUri(null) // Triggers UI update back to library
                    }

                } else {
                    Log.d(
                        "MainActivity",
                        "ViewModel observed URI change (null). Switching to Library."
                    )
                    updateUiState(UiMode.LIBRARY)
                    setToolbarTitle(null)
                    binding.pdfView.recycle()
                    bookmarksForCurrentPdf = emptyList()
                }
            }
            .launchIn(lifecycleScope)
    }


    // --- PDF Loading and Callbacks ---

    private fun openPdfChooser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            pdfPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to launch PDF chooser", e)
            Toast.makeText(this, "Cannot open file chooser.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPdf(pdfUri: Uri) {
        Log.d("MainActivity", "loadPdf called for: $pdfUri")

        // ** WARNING: If PDF loading still hangs here, the root cause is likely **
        // ** elsewhere (library bug, device graphics issue, OS incompatibility). **
        // ** Re-adding the library feature might not fix it. Next steps would be **
        // ** testing on a different device/emulator or trying another PDF library. **

        binding.pdfView.recycle()
        Log.d("MainActivity", "Calling pdfView.fromUri().load() for $pdfUri")
        binding.pdfView.fromUri(pdfUri)
            .defaultPage(viewModel.currentPage.value)
            .onPageChange(this)
            .onLoad(this)
            .onError(this::onPdfError)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableAntialiasing(true)
            .spacing(10)
            // .enableDebugging(true) // Uncomment for more library logs if needed
            .load()
    }

    private fun onPdfError(t: Throwable?) {
        Log.e("MainActivity", "****** PDF Load Error ******", t)
        Toast.makeText(
            this,
            getString(R.string.error_loading_pdf) + ": ${t?.localizedMessage}",
            Toast.LENGTH_LONG
        ).show()
        viewModel.setPdfUri(null) // Trigger observeViewModel to go back to Library
    }

    override fun loadComplete(nbPages: Int) {
        Log.d("MainActivity", "****** PDF Load Complete! Pages: $nbPages ******")
        updateUiState(UiMode.READER_LOADED)

        val restoredPage = viewModel.currentPage.value
        binding.toolbar.subtitle = "Page ${restoredPage + 1} of $nbPages"
        if (binding.pdfView.currentPage != restoredPage) {
            binding.pdfView.jumpTo(restoredPage, false)
        }
        Log.d("MainActivity", "UI updated for Load Complete.")
    }

    override fun onPageChanged(page: Int, pageCount: Int) {
        viewModel.setCurrentPage(page)
        binding.toolbar.subtitle = "Page ${page + 1} of $pageCount"
    }

    // --- Bookmark Handling ---

    private fun loadBookmarksForPdf(pdfUriString: String) {
        lifecycleScope.launch {
            bookmarkDao.getBookmarksForPdf(pdfUriString).collectLatest { bookmarks ->
                bookmarksForCurrentPdf = bookmarks
                // Update bookmark button state only when reader is loaded
                if (binding.readerViewContainer.isVisible && viewModel.pdfUri.value != null) {
                    updateUiState(UiMode.READER_LOADED) // Refresh UI state
                }
            }
        }
    }

    private fun showBookmarksListDialog() {
        val dialogBinding = DialogSearchBookmarksBinding.inflate(LayoutInflater.from(this))
        val searchEditText = dialogBinding.editTextSearchBookmarks
        val bookmarksListView = dialogBinding.listViewBookmarks
        val noResultsTextView = dialogBinding.textViewNoResults
        var filteredBookmarks = bookmarksForCurrentPdf.toMutableList()
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            filteredBookmarks.map { "${it.title} (${DateUtils.formatDate(it.creationDate)})" })
        bookmarksListView.adapter = adapter
        fun updateListVisibility() {
            bookmarksListView.isVisible = filteredBookmarks.isNotEmpty()
            noResultsTextView.isVisible = filteredBookmarks.isEmpty()
        }
        updateListVisibility()
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim().lowercase()
                filteredBookmarks = if (query.isEmpty()) bookmarksForCurrentPdf.toMutableList()
                else bookmarksForCurrentPdf.filter {
                    it.title.lowercase().contains(query) || it.summary.lowercase().contains(query)
                }.toMutableList()
                adapter.clear()
                adapter.addAll(filteredBookmarks.map { "${it.title} (${DateUtils.formatDate(it.creationDate)})" })
                adapter.notifyDataSetChanged()
                updateListVisibility()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.bookmarks)
            .setView(dialogBinding.root).setNegativeButton(R.string.cancel, null).create()
        bookmarksListView.setOnItemClickListener { _, _, position, _ ->
            showBookmarkSummaryDialog(filteredBookmarks[position])
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showBookmarkSummaryDialog(bookmark: Bookmark) {
        val summary = if (bookmark.summary.isBlank()) getString(R.string.no_summary) else bookmark.summary
        val message = "Date: ${DateUtils.formatDate(bookmark.creationDate)}\n\n$summary"
        MaterialAlertDialogBuilder(this).setTitle(bookmark.title).setMessage(message)
            .setPositiveButton(getString(R.string.go_to_page, bookmark.pageIndex + 1)) { dialog, _ ->
                binding.pdfView.jumpTo(bookmark.pageIndex, true)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun showAddBookmarkDialog(pdfUriString: String, pageIndex: Int) {
        val dialogBinding = DialogAddBookmarkBinding.inflate(LayoutInflater.from(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_bookmark_dialog_title, pageIndex + 1))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = dialogBinding.editTextBookmarkTitle.text.toString().trim()
                val summary = dialogBinding.editTextBookmarkSummary.text.toString().trim()
                if (title.isNotEmpty()) {
                    saveBookmark(
                        Bookmark(
                            pdfUri = pdfUriString, pageIndex = pageIndex, title = title,
                            summary = summary, creationDate = System.currentTimeMillis()
                        )
                    )
                } else {
                    Toast.makeText(this, R.string.bookmark_title_empty, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun saveBookmark(bookmark: Bookmark) {
        lifecycleScope.launch {
            try {
                bookmarkDao.insertBookmark(bookmark)
                Toast.makeText(this@MainActivity, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error saving bookmark", e)
                Toast.makeText(this@MainActivity, R.string.error_saving_bookmark, Toast.LENGTH_SHORT).show()
            }
        }
    }


    // --- UI State Management ---

    private fun updateUiState(mode: UiMode) {
        Log.d("MainActivity", "Updating UI State to: $mode")

        binding.libraryViewContainer.isVisible = mode == UiMode.LIBRARY
        binding.readerViewContainer.isVisible =
            mode == UiMode.READER_LOADING || mode == UiMode.READER_LOADED

        binding.readerViewContainer.findViewById<View>(R.id.progressBar)?.isVisible =
            mode == UiMode.READER_LOADING
        binding.pdfView.isVisible = mode == UiMode.READER_LOADED
        binding.readerViewContainer.findViewById<View>(R.id.fabAddBookmark)?.isVisible =
            mode == UiMode.READER_LOADED

        if (mode == UiMode.LIBRARY) {
            setToolbarTitle(null)
            binding.toolbar.subtitle = null
            binding.toolbar.findViewById<View>(R.id.buttonShowBookmarks)?.visibility = View.GONE
            updateLibraryEmptyState() // Update empty state view when showing library
        } else {
            // Title is set when URI observed/PDF loaded
            val bookmarksButton = binding.toolbar.findViewById<View>(R.id.buttonShowBookmarks)
            bookmarksButton?.visibility = View.VISIBLE
            val hasBookmarks = bookmarksForCurrentPdf.isNotEmpty()
            val isEnabled = mode == UiMode.READER_LOADED && hasBookmarks
            bookmarksButton?.isEnabled = isEnabled
            bookmarksButton?.alpha = if (isEnabled) 1.0f else 0.5f
        }

        invalidateOptionsMenu()
    }

    private fun setToolbarTitle(uri: Uri?) {
        binding.toolbar.title = uri?.let { getFileName(it) } ?: getString(R.string.app_name)
        if (uri == null) binding.toolbar.subtitle = null
    }

    // --- Utility ---
    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) fileName = cursor.getString(index)
                }
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "getFileName failed for $uri due to SecurityException", e)
            removeRecentFile(uri.toString())
            Toast.makeText(this, R.string.permission_lost_file_removed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "getFileName failed for $uri", e)
        }
        return fileName ?: uri.path?.substringAfterLast('/')
    }
}