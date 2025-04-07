package com.example.ithinkicanchangethislater.adapter

// Data class to hold information about a recently opened file
data class RecentFileItem(
    val uriString: String,
    val filename: String,
    val lastAccessed: Long = System.currentTimeMillis() // Timestamp for sorting
)