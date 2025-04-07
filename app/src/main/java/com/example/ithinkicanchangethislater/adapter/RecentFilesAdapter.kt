package com.example.ithinkicanchangethislater.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.ithinkicanchangethislater.R // Import R for drawable
import com.example.ithinkicanchangethislater.databinding.ItemRecentFileBinding // Import generated binding class
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentFilesAdapter(
    private var recentFiles: MutableList<RecentFileItem>,
    private val onItemClick: (RecentFileItem) -> Unit // Lambda for click events
) : RecyclerView.Adapter<RecentFilesAdapter.ViewHolder>() {

    // Formatter for the last accessed timestamp
    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    // ViewHolder holds references to the views in item_recent_file.xml
    inner class ViewHolder(val binding: ItemRecentFileBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecentFileItem) {
            binding.textViewFilename.text = item.filename
            binding.textViewLastAccessed.text = dateFormat.format(Date(item.lastAccessed))
            binding.root.setOnClickListener { onItemClick(item) }
            // Set a default PDF icon (replace R.drawable.ic_pdf with your actual drawable)
            binding.imageViewIcon.setImageResource(R.drawable.ic_pdf)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(recentFiles[position])
    }

    override fun getItemCount(): Int = recentFiles.size

    // Updates the data list and notifies the adapter
    fun updateData(newFiles: List<RecentFileItem>) {
        // Ensure list is sorted by last accessed time (most recent first)
        recentFiles = newFiles.sortedByDescending { it.lastAccessed }.toMutableList()
        // Using notifyDataSetChanged() for simplicity here.
        // For better performance with large lists, consider implementing DiffUtil.
        notifyDataSetChanged()
    }
}