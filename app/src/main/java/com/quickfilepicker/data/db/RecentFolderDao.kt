package com.quickfilepicker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.quickfilepicker.data.model.RecentFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFolderDao {

    @Query("SELECT * FROM recent_folders ORDER BY lastAccessed DESC LIMIT 50")
    fun getRecentFoldersFlow(): Flow<List<RecentFolder>>

    @Query("SELECT * FROM recent_folders ORDER BY lastAccessed DESC LIMIT 50")
    fun getRecentFoldersSync(): List<RecentFolder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recentFolder: RecentFolder): Long

    @Query("DELETE FROM recent_folders WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM recent_folders")
    suspend fun deleteAll()
}
