package com.example.ithinkicanchangethislater.adapter // Create adapter package if needed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.ithinkicanchangethislater.databinding.ItemRecentFileBinding

// Data class to hold recent file info
data class RecentFileItem(val uriString: String, val filename: String)

class RecentFilesAdapter(
    private var recentFiles: List<RecentFileItem>,
    private val onItemClick: (RecentFileItem) -> Unit // Lambda for click events
) : RecyclerView.Adapter<RecentFilesAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemRecentFileBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecentFileItem) {
            binding.textViewFileName.text = item.filename
            // Set the generic PDF icon (assuming you have ic_pdf_file.xml)
            // binding.imageViewPdfIcon.setImageResource(R.drawable.ic_pdf_file) // Already set in XML

            // Set click listener for the whole item view
            binding.root.setOnClickListener {
                onItemClick(item)
            }
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

    // Function to update the list data and refresh the adapter
    fun updateData(newRecentFiles: List<RecentFileItem>) {
        recentFiles = newRecentFiles
        notifyDataSetChanged() // Consider using DiffUtil for better performance later
    }
}