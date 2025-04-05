package com.example.ithinkicanchangethislater.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {
    // Example format: "yyyy-MM-dd HH:mm"
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatDate(timestamp: Long): String {
        return formatter.format(Date(timestamp))
    }
}