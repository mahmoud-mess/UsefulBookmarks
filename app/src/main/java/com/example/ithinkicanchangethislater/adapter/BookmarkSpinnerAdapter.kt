package com.example.ithinkicanchangethislater.adapter

import android.content.Context
import android.util.Log // Import Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.example.ithinkicanchangethislater.R
import com.example.ithinkicanchangethislater.data.entity.Bookmark
import com.example.ithinkicanchangethislater.util.DateUtils

class BookmarkSpinnerAdapter(
    context: Context,
    val bookmarks: List<Bookmark> // Made public for getItemId safety or keep private
) : ArrayAdapter<Bookmark>(context, 0, bookmarks) { // Pass 0 for resource ID, we handle it

    private val layoutInflater = LayoutInflater.from(context)

    // *** Use the CORRECTED createView function below ***
    private fun createView(position: Int, convertView: View?, parent: ViewGroup, layoutResId: Int): View {
        val view = convertView ?: layoutInflater.inflate(layoutResId, parent, false)
        // Use safe getItem call
        val bookmark = getItem(position) // Get the bookmark data for this position

        // Find the TextView based on the layout resource used
        val textView: TextView? = try {
            if (layoutResId == R.layout.spinner_item_bookmark) {
                // Use the ID from your custom layout XML (spinner_item_bookmark.xml)
                view.findViewById(R.id.textViewBookmarkSpinnerItem)
            } else {
                // Use the standard Android ID for default layouts like simple_spinner_dropdown_item
                view.findViewById(android.R.id.text1)
            }
        } catch (e: Exception) {
            Log.e("AdapterError", "Error finding TextView in layout $layoutResId", e)
            null // Ensure textView is null if findViewById fails
        }


        // Safely set the text if both bookmark and textView are not null
        if (bookmark != null && textView != null) {
            try {
                val dateString = DateUtils.formatDate(bookmark.creationDate) // Format the date
                textView.text = "${bookmark.title} (${dateString})"
            } catch (e: Exception) {
                Log.e("AdapterError", "Error formatting/setting text for position $position", e)
                textView.text = "Error Loading Bookmark" // Show error in UI
            }
        } else {
            // Log if either data or the view is missing
            Log.w("AdapterWarning", "Bookmark ($bookmark) or TextView ($textView) is null at position $position for layout $layoutResId")
            textView?.text = "" // Clear text or show placeholder
        }

        return view
    }

    // Ensure getView and getDropDownView call createView with the CORRECT layout IDs
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // This is the view shown when the spinner is collapsed.
        // Use your custom layout here.
        return createView(position, convertView, parent, R.layout.spinner_item_bookmark)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        // This is the view for items shown in the dropdown list.
        // Using a standard Android layout is often simpler and robust.
        return createView(position, convertView, parent, android.R.layout.simple_spinner_dropdown_item)
        // OR if you want the dropdown items to use your custom layout too:
        // return createView(position, convertView, parent, R.layout.spinner_item_bookmark)
    }

    // getItemId with safety check
    override fun getItemId(position: Int): Long {
        // Use 0 or a default value if bookmarks might be empty or position invalid
        // Access bookmarks directly or ensure it's accessible if kept private
        return bookmarks.getOrNull(position)?.id?.toLong() ?: 0L
    }

    // getItem with safety check (ArrayAdapter might already do this, but explicit is safe)
    override fun getItem(position: Int): Bookmark? {
        return if (position >= 0 && position < count) { // Use count from ArrayAdapter
            super.getItem(position) // Prefer superclass method which uses internal list
            // OR if you must use your list: bookmarks.getOrNull(position)
        } else {
            null
        }
    }
}