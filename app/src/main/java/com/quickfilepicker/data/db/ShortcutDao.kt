package com.quickfilepicker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.quickfilepicker.data.model.Shortcut
import com.quickfilepicker.data.model.ShortcutWithChildren
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortcutDao {

    @Transaction
    @Query("SELECT * FROM shortcuts WHERE parentId IS NULL ORDER BY sortOrder ASC, id ASC")
    fun getPrimaryShortcutsWithChildrenFlow(): Flow<List<ShortcutWithChildren>>

    @Transaction
    @Query("SELECT * FROM shortcuts WHERE parentId IS NULL ORDER BY sortOrder ASC, id ASC")
    fun getPrimaryShortcutsWithChildrenSync(): List<ShortcutWithChildren>

    @Query("SELECT * FROM shortcuts WHERE parentId IS NULL ORDER BY sortOrder ASC, id ASC")
    fun getAllPrimaryShortcutsFlow(): Flow<List<Shortcut>>

    @Query("SELECT * FROM shortcuts WHERE parentId IS NULL ORDER BY sortOrder ASC, id ASC")
    fun getAllPrimaryShortcutsSync(): List<Shortcut>

    @Query("SELECT * FROM shortcuts WHERE parentId IS NULL AND isHidden = 0 ORDER BY sortOrder ASC, id ASC")
    fun getVisiblePrimaryShortcutsSync(): List<Shortcut>

    @Query("SELECT * FROM shortcuts WHERE parentId = :parentId AND isHidden = 0 ORDER BY sortOrder ASC, id ASC")
    fun getSecondaryShortcutsSync(parentId: Long): List<Shortcut>

    @Query("SELECT * FROM shortcuts WHERE parentId = :parentId ORDER BY sortOrder ASC, id ASC")
    fun getAllSecondaryShortcutsSync(parentId: Long): List<Shortcut>

    @Query("SELECT * FROM shortcuts WHERE id = :id LIMIT 1")
    suspend fun getShortcutById(id: Long): Shortcut?

    @Query("SELECT * FROM shortcuts WHERE id = :id LIMIT 1")
    fun getShortcutByIdSync(id: Long): Shortcut?

    @Query("SELECT * FROM shortcuts ORDER BY parentId ASC, isPinned DESC, sortOrder ASC, id ASC")
    fun getAllShortcutsSync(): List<Shortcut>

    @Query("SELECT * FROM shortcuts ORDER BY parentId ASC, isPinned DESC, sortOrder ASC, id ASC")
    fun getAllShortcutsFlow(): Flow<List<Shortcut>>

    @Query("SELECT * FROM shortcuts WHERE isHidden = 0 ORDER BY parentId ASC, isPinned DESC, sortOrder ASC, id ASC")
    fun getVisibleShortcutsSync(): List<Shortcut>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shortcut: Shortcut): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shortcuts: List<Shortcut>)

    @Update
    suspend fun update(shortcut: Shortcut)

    @Update
    suspend fun updateAll(shortcuts: List<Shortcut>)

    @Delete
    suspend fun delete(shortcut: Shortcut)

    @Query("DELETE FROM shortcuts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM shortcuts WHERE parentId = :parentId")
    suspend fun deleteSecondaryShortcutsForParent(parentId: Long)

    @Query("DELETE FROM shortcuts")
    suspend fun deleteAll()
}
