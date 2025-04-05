package com.example.ithinkicanchangethislater.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.example.ithinkicanchangethislater.data.entity.Bookmark

@Dao
interface BookmarkDao {
    // Get all bookmarks for a specific PDF, ordered by date
    @Query("SELECT * FROM bookmarks WHERE pdfUri = :pdfUri ORDER BY creationDate DESC")
    fun getBookmarksForPdf(pdfUri: String): Flow<List<Bookmark>> // Use Flow for reactive updates

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark) // Use suspend for coroutines

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: Int)

    // Add other queries if needed (e.g., update)
}