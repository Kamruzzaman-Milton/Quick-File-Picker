package com.quickfilepicker.data.repository

import android.content.Context
import android.provider.DocumentsContract
import com.quickfilepicker.data.db.FavoriteItemDao
import com.quickfilepicker.data.db.RecentFolderDao
import com.quickfilepicker.data.db.ShortcutDao
import com.quickfilepicker.data.model.FavoriteItem
import com.quickfilepicker.data.model.RecentFolder
import com.quickfilepicker.data.model.Shortcut
import com.quickfilepicker.data.model.ShortcutWithChildren
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ShortcutRepository(
    private val context: Context,
    private val shortcutDao: ShortcutDao,
    private val recentFolderDao: RecentFolderDao? = null,
    private val favoriteItemDao: FavoriteItemDao? = null
) {
    val shortcutsWithChildrenFlow: Flow<List<ShortcutWithChildren>> = shortcutDao.getPrimaryShortcutsWithChildrenFlow()
    val shortcutsFlow: Flow<List<Shortcut>> = shortcutDao.getAllPrimaryShortcutsFlow()

    suspend fun getAllShortcuts(): List<Shortcut> = withContext(Dispatchers.IO) {
        shortcutDao.getAllShortcutsSync()
    }

    fun getAllShortcutsSync(): List<Shortcut> {
        return shortcutDao.getAllShortcutsSync()
    }

    fun getVisiblePrimaryShortcutsSync(): List<Shortcut> {
        return shortcutDao.getVisiblePrimaryShortcutsSync()
    }

    fun getAllPrimaryShortcutsSync(): List<Shortcut> {
        return shortcutDao.getAllPrimaryShortcutsSync()
    }

    fun getSecondaryShortcutsSync(parentId: Long): List<Shortcut> {
        return shortcutDao.getSecondaryShortcutsSync(parentId)
    }

    fun getShortcutByIdSync(id: Long): Shortcut? {
        return shortcutDao.getShortcutByIdSync(id)
    }

    suspend fun insert(shortcut: Shortcut): Long = withContext(Dispatchers.IO) {
        val id = shortcutDao.insert(shortcut)
        notifyRootsChanged()
        id
    }

    suspend fun addSecondaryShortcut(parentId: Long, name: String, treeUri: String, icon: String): Long = withContext(Dispatchers.IO) {
        val existing = shortcutDao.getAllSecondaryShortcutsSync(parentId)
        val shortcut = Shortcut(
            name = name,
            treeUri = treeUri,
            icon = icon,
            sortOrder = existing.size,
            parentId = parentId
        )
        val id = shortcutDao.insert(shortcut)
        notifyRootsChanged()
        id
    }

    suspend fun moveToParent(shortcut: Shortcut, targetParentId: Long?) = withContext(Dispatchers.IO) {
        shortcutDao.update(shortcut.copy(parentId = targetParentId))
        notifyRootsChanged()
    }

    suspend fun update(shortcut: Shortcut) = withContext(Dispatchers.IO) {
        shortcutDao.update(shortcut)
        notifyRootsChanged()
    }

    suspend fun togglePin(shortcut: Shortcut) = withContext(Dispatchers.IO) {
        shortcutDao.update(shortcut.copy(isPinned = !shortcut.isPinned))
        notifyRootsChanged()
    }

    suspend fun toggleHide(shortcut: Shortcut) = withContext(Dispatchers.IO) {
        shortcutDao.update(shortcut.copy(isHidden = !shortcut.isHidden))
        notifyRootsChanged()
    }

    suspend fun toggleFavorite(shortcut: Shortcut) = withContext(Dispatchers.IO) {
        shortcutDao.update(shortcut.copy(isFavorite = !shortcut.isFavorite))
        notifyRootsChanged()
    }

    suspend fun delete(shortcut: Shortcut) = withContext(Dispatchers.IO) {
        if (shortcut.parentId == null) {
            shortcutDao.deleteSecondaryShortcutsForParent(shortcut.id)
        }
        shortcutDao.delete(shortcut)
        notifyRootsChanged()
    }

    suspend fun updateOrder(shortcuts: List<Shortcut>) = withContext(Dispatchers.IO) {
        val updatedList = shortcuts.mapIndexed { index, shortcut ->
            shortcut.copy(sortOrder = index)
        }
        shortcutDao.updateAll(updatedList)
        notifyRootsChanged()
    }

    suspend fun restoreAll(shortcuts: List<Shortcut>) = withContext(Dispatchers.IO) {
        shortcutDao.deleteAll()
        shortcutDao.insertAll(shortcuts)
        notifyRootsChanged()
    }

    // Recent Folders
    fun getRecentFoldersSync(): List<RecentFolder> {
        return recentFolderDao?.getRecentFoldersSync() ?: emptyList()
    }

    suspend fun addRecentFolder(name: String, path: String, treeUri: String) = withContext(Dispatchers.IO) {
        recentFolderDao?.deleteByPath(path)
        recentFolderDao?.insert(RecentFolder(name = name, path = path, treeUri = treeUri))
    }

    // Favorite Items
    fun getFavoriteItemsSync(): List<FavoriteItem> {
        return favoriteItemDao?.getAllFavoritesSync() ?: emptyList()
    }

    suspend fun addFavoriteItem(name: String, uriString: String, isDirectory: Boolean, mimeType: String) = withContext(Dispatchers.IO) {
        favoriteItemDao?.insert(FavoriteItem(name = name, uriString = uriString, isDirectory = isDirectory, mimeType = mimeType))
    }

    suspend fun removeFavoriteItem(uriString: String) = withContext(Dispatchers.IO) {
        favoriteItemDao?.deleteByUri(uriString)
    }

    fun notifyRootsChanged() {
        try {
            val rootsUri = DocumentsContract.buildRootsUri(com.quickfilepicker.provider.QuickFilePickerDocumentsProvider.AUTHORITY)
            context.contentResolver.notifyChange(rootsUri, null)
            val docsUri = android.net.Uri.parse("content://${com.quickfilepicker.provider.QuickFilePickerDocumentsProvider.AUTHORITY}/document")
            context.contentResolver.notifyChange(docsUri, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
