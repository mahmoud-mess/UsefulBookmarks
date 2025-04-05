package com.example.ithinkicanchangethislater.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
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

        // Enable edge-to-edge layout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars()) // Immersive: hide status & nav bars
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup toolbar
        setSupportActionBar(binding.toolbar)

        // Padding for status bar so Toolbar doesn't get overlapped
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = statusBarHeight)
            insets
        }

        bookmarkDao = AppDatabase.getDatabase(applicationContext).bookmarkDao()

        setupViews()
        observeViewModel()
        updateUiState(isLoading = false, pdfLoaded = false, hasBookmarks = false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
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

    private fun setupViews() {
        binding.fabAddBookmark.setOnClickListener {
            viewModel.pdfUri.value?.let {
                showAddBookmarkDialog(it.toString(), viewModel.currentPage.value)
            } ?: Toast.makeText(this, R.string.load_pdf_first, Toast.LENGTH_SHORT).show()
        }

        binding.buttonShowBookmarks.setOnClickListener {
            if (bookmarksForCurrentPdf.isEmpty()) {
                Toast.makeText(this, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
            } else {
                showBookmarksListDialog()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.pdfUri
            .onEach { uri ->
                if (uri != null) {
                    updateUiState(true, false)
                    setToolbarTitle(uri)
                    loadPdf(uri)
                } else {
                    updateUiState(false, false)
                    setToolbarTitle(null)
                    bookmarksForCurrentPdf = emptyList()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun openPdfChooser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        pdfPickerLauncher.launch(intent)
    }

    private fun loadPdf(pdfUri: Uri) {
        loadBookmarksForPdf(pdfUri.toString())

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
            .load()
    }

    private fun onPdfError(t: Throwable?) {
        Log.e("MainActivity", "Error loading PDF", t)
        Toast.makeText(this, getString(R.string.error_loading_pdf) + ": ${t?.message}", Toast.LENGTH_LONG).show()
        updateUiState(false, false)
        viewModel.setPdfUri(null)
        setToolbarTitle(null)
    }

    private fun loadBookmarksForPdf(pdfUriString: String) {
        lifecycleScope.launch {
            bookmarkDao.getBookmarksForPdf(pdfUriString).collectLatest { bookmarks ->
                bookmarksForCurrentPdf = bookmarks
                if (viewModel.pdfUri.value != null) {
                    updateUiState(false, true, bookmarks.isNotEmpty())
                }
            }
        }
    }

    private fun updateUiState(isLoading: Boolean, pdfLoaded: Boolean, hasBookmarks: Boolean? = null) {
        binding.progressBar.isVisible = isLoading
        binding.textViewOpenFilePrompt.isVisible = !isLoading && !pdfLoaded
        binding.pdfView.isVisible = !isLoading && pdfLoaded
        binding.fabAddBookmark.isVisible = !isLoading && pdfLoaded
        binding.buttonShowBookmarks.isEnabled = !isLoading && pdfLoaded && (hasBookmarks ?: true)
        binding.buttonShowBookmarks.alpha = if (binding.buttonShowBookmarks.isEnabled) 1.0f else 0.5f
    }

    private fun setToolbarTitle(uri: Uri?) {
        binding.toolbar.title = uri?.let { getFileName(it) } ?: getString(R.string.app_name)
        binding.toolbar.subtitle = null
    }

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) fileName = cursor.getString(index)
            }
        }
        return fileName ?: uri.path?.substringAfterLast('/')
    }

    private fun showBookmarksListDialog() {
        val items = bookmarksForCurrentPdf.map {
            "${it.title} (${DateUtils.formatDate(it.creationDate)})"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks)
            .setItems(items) { _, which ->
                showBookmarkSummaryDialog(bookmarksForCurrentPdf[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBookmarkSummaryDialog(bookmark: Bookmark) {
        val summary = if (bookmark.summary.isBlank()) getString(R.string.no_summary) else bookmark.summary
        val message = "Date: ${DateUtils.formatDate(bookmark.creationDate)}\n\n$summary"

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
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_bookmark_dialog_title, pageIndex + 1))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { dialog, _ ->
                val title = dialogBinding.editTextBookmarkTitle.text.toString().trim()
                val summary = dialogBinding.editTextBookmarkSummary.text.toString().trim()
                if (title.isNotEmpty()) {
                    val bookmark = Bookmark(
                        pdfUri = pdfUriString,
                        pageIndex = pageIndex,
                        title = title,
                        summary = summary,
                        creationDate = System.currentTimeMillis()
                    )
                    saveBookmark(bookmark)
                } else {
                    Toast.makeText(this, R.string.bookmark_title_empty, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    override fun onPageChanged(page: Int, pageCount: Int) {
        viewModel.setCurrentPage(page)
        binding.toolbar.subtitle = "Page ${page + 1} of $pageCount"
    }

    override fun loadComplete(nbPages: Int) {
        updateUiState(false, true, bookmarksForCurrentPdf.isNotEmpty())
        val restoredPage = viewModel.currentPage.value
        binding.toolbar.subtitle = "Page ${restoredPage + 1} of $nbPages"
        if (binding.pdfView.currentPage != restoredPage) {
            binding.pdfView.jumpTo(restoredPage, false)
        }
    }
}
