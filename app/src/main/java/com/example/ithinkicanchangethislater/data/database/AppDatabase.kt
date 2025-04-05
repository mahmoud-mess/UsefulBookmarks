package com.example.ithinkicanchangethislater.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.ithinkicanchangethislater.data.entity.Bookmark

@Database(entities = [Bookmark::class], version = 1, exportSchema = false)
@TypeConverters(DateConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pdf_reader_database"
                )
                    // Add migrations here if you change the schema later
                    .fallbackToDestructiveMigration() // Simple strategy for example purposes
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}