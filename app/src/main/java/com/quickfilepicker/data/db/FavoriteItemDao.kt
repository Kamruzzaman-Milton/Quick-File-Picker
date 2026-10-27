package com.quickfilepicker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.quickfilepicker.data.model.FavoriteItem
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteItemDao {

    @Query("SELECT * FROM favorite_items ORDER BY addedTime DESC")
    fun getAllFavoritesFlow(): Flow<List<FavoriteItem>>

    @Query("SELECT * FROM favorite_items ORDER BY addedTime DESC")
    fun getAllFavoritesSync(): List<FavoriteItem>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_items WHERE uriString = :uriString)")
    suspend fun isFavorite(uriString: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favoriteItem: FavoriteItem): Long

    @Query("DELETE FROM favorite_items WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)

    @Query("DELETE FROM favorite_items")
    suspend fun deleteAll()
}
