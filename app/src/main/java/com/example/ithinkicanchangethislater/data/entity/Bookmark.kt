package com.example.ithinkicanchangethislater.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.example.ithinkicanchangethislater.data.database.DateConverter // You'll create this

@Entity(tableName = "bookmarks")
@TypeConverters(DateConverter::class)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pdfUri: String, // Store the URI of the PDF as a String
    val pageIndex: Int, // 0-based index
    val title: String,
    val summary: String,
    val creationDate: Long // Store date as timestamp (Long)
)
